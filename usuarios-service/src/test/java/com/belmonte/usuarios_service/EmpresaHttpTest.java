package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.belmonte.usuarios_service.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "jwt.secret=test-jwt-secret-not-for-deployment",
        "internal.api.secret=test-internal-secret",
        "spring.datasource.url=jdbc:h2:mem:empresas;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class EmpresaHttpTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM pasajeros");
        jdbc.update("DELETE FROM empresas");
    }

    @Test
    void administraYConsultaCatalogoConPermisos() throws Exception {
        String admin = iniciarSesion("ADMINISTRADOR");
        String solicitante = iniciarSesion("SOLICITANTE");
        String conductor = iniciarSesion("CONDUCTOR");
        String body = """
                {"rutEmpresa":" TEST-1 ","razonSocial":" Empresa A ",
                 "telefono":"123456","email":"empresa@example.invalid"}
                """;
        mvc.perform(get("/api/empresas")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/empresas").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/empresas").header("Authorization", solicitante)
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/empresas").header("Authorization", conductor)).andExpect(status().isForbidden());
        String created = mvc.perform(post("/api/empresas").header("Authorization", admin)
                .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rutEmpresa").value("TEST-1"))
                .andExpect(jsonPath("$.razonSocial").value("Empresa A"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("idEmpresa").asLong();
        String otra = mvc.perform(post("/api/empresas").header("Authorization", admin)
                .contentType("application/json")
                .content("{\"rutEmpresa\":\"TEST-3\",\"razonSocial\":\"Empresa 0\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long otroId = json.readTree(otra).get("idEmpresa").asLong();
        mvc.perform(get("/api/empresas").header("Authorization", solicitante))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].idEmpresa").value(otroId))
                .andExpect(jsonPath("$[1].idEmpresa").value(id))
                .andExpect(jsonPath("$[1].rutEmpresa").value("TEST-1"))
                .andExpect(jsonPath("$[0].telefono").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist());
        mvc.perform(get("/api/empresas/{id}", id).header("Authorization", solicitante))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/empresas/{id}", id).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.telefono").value("123456"))
                .andExpect(jsonPath("$.email").value("empresa@example.invalid"));
        mvc.perform(get("/api/empresas/999999").header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/empresas/0").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/empresas").header("Authorization", admin)
                .contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(post("/api/auth/logout").header("Authorization", solicitante)).andExpect(status().isOk());
        mvc.perform(get("/api/empresas").header("Authorization", solicitante)).andExpect(status().isUnauthorized());
    }

    @Test
    void validaDatosYEvitaDuplicadosConcurrentes() throws Exception {
        String admin = iniciarSesion("ADMINISTRADOR");
        for (String invalid : List.of(
                "{",
                "{\"rutEmpresa\":\" \",\"razonSocial\":\"Empresa\"}",
                "{\"rutEmpresa\":\"TEST-2\",\"razonSocial\":\" \"}",
                "{\"rutEmpresa\":\"TEST-2\",\"razonSocial\":\"Empresa\",\"email\":\"no-es-correo\"}",
                "{\"rutEmpresa\":\"" + "A".repeat(21) + "\",\"razonSocial\":\"Empresa\"}")) {
            mvc.perform(post("/api/empresas").header("Authorization", admin)
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Datos de empresa invalidos"));
        }
        String body = "{\"rutEmpresa\":\"TEST-2\",\"razonSocial\":\"Empresa\"}";
        CountDownLatch inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tarea = (java.util.concurrent.Callable<Integer>) () -> {
                inicio.await();
                return mvc.perform(post("/api/empresas").header("Authorization", admin)
                        .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(tarea);
            var second = executor.submit(tarea);
            inicio.countDown();
            assertEquals(List.of(201, 409), java.util.stream.Stream.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM empresas", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT telefono IS NULL AND email IS NULL FROM empresas", Boolean.class));
    }

    @Test
    void protegePersonasRolesYEscrituraDeCatalogos() throws Exception {
        String admin = iniciarSesion("ADMINISTRADOR");
        String solicitante = iniciarSesion("SOLICITANTE");
        String conductor = iniciarSesion("CONDUCTOR");
        for (String ruta : List.of("/api/personas", "/api/roles")) {
            mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
            mvc.perform(post(ruta).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            for (String auth : List.of(solicitante, conductor)) {
                mvc.perform(get(ruta).header("Authorization", auth)).andExpect(status().isForbidden());
                mvc.perform(post(ruta).header("Authorization", auth).contentType("application/json").content("{}"))
                        .andExpect(status().isForbidden());
            }
            mvc.perform(get(ruta).header("Authorization", admin)).andExpect(status().isOk());
        }
        for (String ruta : List.of("/api/paises", "/api/tipos-documento")) {
            mvc.perform(get(ruta).header("Authorization", solicitante)).andExpect(status().isOk());
            mvc.perform(post(ruta).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            for (String auth : List.of(solicitante, conductor)) {
                mvc.perform(post(ruta).header("Authorization", auth).contentType("application/json").content("{}"))
                        .andExpect(status().isForbidden());
            }
        }
        mvc.perform(post("/api/roles").header("Authorization", admin)
                .contentType("application/json").content("{\"nombreRol\":\"ROL_NUEVO_TEST\"}"))
                .andExpect(status().isOk());
        String pais = mvc.perform(post("/api/paises").header("Authorization", admin)
                .contentType("application/json").content("{\"nombrePais\":\"Pais protegido\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String tipo = mvc.perform(post("/api/tipos-documento").header("Authorization", admin)
                .contentType("application/json").content("{\"codigoTipo\":\"PROTEGIDO\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/personas").header("Authorization", admin)
                .contentType("application/json").content("""
                        {"paisDocumento":{"idPais":%d},"tipoDocumento":{"idTipoDocumento":%d},
                         "rutDni":"PROTEGIDO","nombreCompleto":"Prueba","email":"protegido@example.invalid"}
                        """.formatted(json.readTree(pais).get("idPais").asLong(),
                        json.readTree(tipo).get("idTipoDocumento").asLong())))
                .andExpect(status().isOk());
        for (String[] caso : List.of(
                new String[]{"/api/personas", "{\"idPersona\":1}"},
                new String[]{"/api/paises", "{\"idPais\":1}"},
                new String[]{"/api/tipos-documento", "{\"idTipoDocumento\":1}"})) {
            mvc.perform(post(caso[0]).header("Authorization", admin)
                    .contentType("application/json").content(caso[1])).andExpect(status().isBadRequest());
        }
        String sesion = jwt.parseToken(admin.substring(7)).get("sessionId", String.class);
        String identidadAjena = "Bearer " + jwt.createToken("otro@example.invalid", 999999999L,
                sesion, List.of("ADMINISTRADOR"));
        mvc.perform(get("/api/personas").header("Authorization", identidadAjena))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/personas").header("Authorization", admin)).andExpect(status().isUnauthorized());
    }

    @Test
    void validaPasajerosInternamenteSinExponerDatosPersonales() throws Exception {
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES ('Pais pasajeros')");
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES ('PASAJEROS')");
        jdbc.update("INSERT INTO empresas (rut_empresa, razon_social) VALUES ('PASS-1', 'Empresa pasajeros')");
        long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = 'Pais pasajeros'", Long.class);
        long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = 'PASAJEROS'", Long.class);
        long empresa = jdbc.queryForObject("SELECT id_empresa FROM empresas WHERE rut_empresa = 'PASS-1'", Long.class);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) "
                + "VALUES (?, ?, 'PASS1', 'Pasajero', 'pasajero@example.invalid', CURRENT_TIMESTAMP)", pais, tipo);
        long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = 'pasajero@example.invalid'", Long.class);
        jdbc.update("INSERT INTO pasajeros (persona_id, empresa_id) VALUES (?, ?)", persona, empresa);
        long pasajero = jdbc.queryForObject("SELECT id_pasajero FROM pasajeros WHERE persona_id = ?", Long.class, persona);
        String body = "{\"ids\":[" + pasajero + "]}";
        mvc.perform(post("/internal/pasajeros/validar").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/pasajeros/validar").header("X-Belmonte-Internal-Secret", "incorrecta")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/internal/pasajeros/validar").header("X-Belmonte-Internal-Secret", "test-internal-secret")
                .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ids[0]").value(pasajero))
                .andExpect(jsonPath("$.length()").value(1));
        for (String invalid : List.of("{\"ids\":[]}", "{\"ids\":[0]}", "{\"ids\":[null]}",
                "{\"ids\":[" + java.util.stream.IntStream.rangeClosed(1, 51)
                        .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(",")) + "]}")) {
            mvc.perform(post("/internal/pasajeros/validar").header("X-Belmonte-Internal-Secret", "test-internal-secret")
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/internal/pasajeros/validar").header("X-Belmonte-Internal-Secret", "test-internal-secret")
                .contentType("application/json").content("{\"ids\":[" + pasajero + ",999999999]}"))
                .andExpect(status().isNotFound());
        String cincuenta = java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
        mvc.perform(post("/internal/pasajeros/validar").header("X-Belmonte-Internal-Secret", "test-internal-secret")
                .contentType("application/json").content("{\"ids\":[" + cincuenta + "]}"))
                .andExpect(status().isNotFound());
    }

    private String iniciarSesion(String rol) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("MERGE INTO roles (nombre_rol) KEY (nombre_rol) VALUES (?)", rol);
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES (?)", "Pais " + suffix);
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES (?)", suffix);
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = ?", Long.class, "Pais " + suffix);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = ?", Long.class, suffix);
        Long rolId = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, rol);
        String email = suffix + "@example.invalid";
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) "
                + "VALUES (?, ?, ?, 'Prueba', ?, CURRENT_TIMESTAMP)", pais, tipo, suffix, email);
        Long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, ?, 1)",
                persona, rolId, new BCryptPasswordEncoder().encode("password"));
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asString();
    }
}
