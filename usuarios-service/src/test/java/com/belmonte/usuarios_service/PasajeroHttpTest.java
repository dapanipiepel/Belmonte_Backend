package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.belmonte.usuarios_service.dto.BusquedaPasajeroRequest;
import com.belmonte.usuarios_service.security.IdentidadSesion;
import com.belmonte.usuarios_service.service.JwtService;
import com.belmonte.usuarios_service.service.PasajeroService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "jwt.secret=test-jwt-secret-not-for-deployment",
        "internal.api.secret=test-internal-secret",
        "spring.datasource.url=jdbc:h2:mem:pasajerostest;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class PasajeroHttpTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PasajeroService pasajeros;
    @Autowired private JwtService jwt;
    private String sufijo;
    private long pais;
    private long tipo;
    private long empresa;

    @BeforeEach
    void preparar() {
        sufijo = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES (?)", "Pais " + sufijo);
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES (?)", sufijo);
        jdbc.update("INSERT INTO empresas (rut_empresa, razon_social) VALUES (?, ?)", sufijo, "Empresa " + sufijo);
        pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = ?", Long.class, "Pais " + sufijo);
        tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = ?", Long.class, sufijo);
        empresa = jdbc.queryForObject("SELECT id_empresa FROM empresas WHERE rut_empresa = ?", Long.class, sufijo);
    }

    @Test
    void registraConPermisosYDetectaDuplicados() throws Exception {
        Sesion solicitante = iniciarSesion("SOLICITANTE");
        Sesion admin = iniciarSesion("ADMINISTRADOR");
        Sesion conductor = iniciarSesion("CONDUCTOR");
        String body = pasajero("DOC-A", "Nombre A", "a" + sufijo + "@example.invalid", "111");
        mvc.perform(post("/api/pasajeros").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(crear(conductor, body)).andExpect(status().isForbidden());
        mvc.perform(crear(solicitante, body)).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.numeroDocumento").value("DOC-A"))
                .andExpect(jsonPath("$.empresaId").value(empresa));
        mvc.perform(crear(admin, body)).andExpect(status().isConflict());
        mvc.perform(crear(admin, pasajero("DOC-A", "Otro", "b" + sufijo + "@example.invalid", "222")))
                .andExpect(status().isConflict());
        mvc.perform(crear(admin, pasajero("DOC-B", "Nombre", "a" + sufijo + "@example.invalid", "111")))
                .andExpect(status().isConflict());
        mvc.perform(crear(admin, pasajero("DOC-C", "Nombre", "c" + sufijo + "@example.invalid", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(crear(admin, body.replace("\"empresaId\":" + empresa, "\"empresaId\":999999999")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/logout").header("Authorization", solicitante.token()))
                .andExpect(status().isOk());
        mvc.perform(crear(solicitante, body)).andExpect(status().isUnauthorized());
    }

    @Test
    void asociaPersonaExistenteSoloSiTodosLosDatosCoinciden() throws Exception {
        Sesion admin = iniciarSesion("ADMINISTRADOR");
        String email = "existente" + sufijo + "@example.invalid";
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, telefono, fecha_creacion) "
                + "VALUES (?, ?, 'DOC-X', 'Persona X', ?, '123', CURRENT_TIMESTAMP)", pais, tipo, email);
        long idPersona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
        mvc.perform(crear(admin, pasajero("DOC-X", "Persona X", email, "999"))).andExpect(status().isConflict());
        assertEquals("123", jdbc.queryForObject("SELECT telefono FROM personas WHERE id_persona = ?", String.class, idPersona));
        mvc.perform(crear(admin, pasajero("DOC-X", "Persona X", email, "123"))).andExpect(status().isCreated());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pasajeros WHERE persona_id = ?", Integer.class, idPersona));
        mvc.perform(crear(admin, pasajero("DOC-X", "Persona X", email, "123"))).andExpect(status().isConflict());
    }

    @Test
    void evitaDuplicadosEnCarrera() throws Exception {
        Sesion admin = iniciarSesion("ADMINISTRADOR");
        Sesion otro = iniciarSesion("SOLICITANTE");
        String email = "carrera" + sufijo + "@example.invalid";
        String body = pasajero("CARRERA", "Pasajero carrera", email, "123");
        CountDownLatch inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tareaAdmin = (java.util.concurrent.Callable<Integer>) () -> {
                inicio.await();
                return mvc.perform(crear(admin, body)).andReturn().getResponse().getStatus();
            };
            var tareaOtro = (java.util.concurrent.Callable<Integer>) () -> {
                inicio.await();
                return mvc.perform(crear(otro, body)).andReturn().getResponse().getStatus();
            };
            var a = executor.submit(tareaAdmin);
            var b = executor.submit(tareaOtro);
            inicio.countDown();
            assertEquals(List.of(201, 409), java.util.stream.Stream.of(a.get(20, TimeUnit.SECONDS),
                    b.get(20, TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM personas WHERE email = ?", Integer.class, email));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pasajeros pa JOIN personas p ON p.id_persona = pa.persona_id "
                + "WHERE p.email = ?", Integer.class, email));
        CountDownLatch inicioDocumento = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primera = executor.submit(() -> {
                inicioDocumento.await();
                return mvc.perform(crear(admin, pasajero("CARRERA-DOC", "Uno", "uno" + sufijo + "@example.invalid", "123")))
                        .andReturn().getResponse().getStatus();
            });
            var segunda = executor.submit(() -> {
                inicioDocumento.await();
                return mvc.perform(crear(otro, pasajero("CARRERA-DOC", "Dos", "dos" + sufijo + "@example.invalid", "123")))
                        .andReturn().getResponse().getStatus();
            });
            inicioDocumento.countDown();
            assertEquals(List.of(201, 409), java.util.stream.Stream.of(primera.get(20, TimeUnit.SECONDS),
                    segunda.get(20, TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM personas WHERE pais_documento_id = ? "
                + "AND tipo_documento_id = ? AND rut_dni = 'CARRERA-DOC'", Integer.class, pais, tipo));
    }

    @Test
    void buscaPorCamposPaginaEscapaComodinesYAudita() throws Exception {
        Sesion solicitante = iniciarSesion("SOLICITANTE");
        Sesion conductor = iniciarSesion("CONDUCTOR");
        for (int i = 0; i < 51; i++) {
            String email = "pas" + sufijo + i + "@example.invalid";
            jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, telefono, fecha_creacion) "
                    + "VALUES (?, ?, ?, ?, ?, '123', CURRENT_TIMESTAMP)", pais, tipo, "D" + sufijo + i,
                    "Nombre " + sufijo + " " + i, email);
            long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
            jdbc.update("INSERT INTO pasajeros (persona_id, empresa_id) VALUES (?, ?)", persona, empresa);
        }
        String primera = busqueda("NOMBRE", sufijo, 0, 50);
        mvc.perform(post("/api/pasajeros/busquedas").contentType("application/json").content(primera))
                .andExpect(status().isUnauthorized());
        mvc.perform(buscar(conductor, primera)).andExpect(status().isForbidden());
        mvc.perform(buscar(solicitante, primera)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.pasajeros.length()").value(50))
                .andExpect(jsonPath("$.hayMas").value(true))
                .andExpect(jsonPath("$.pasajeros[0].telefono").value("123"))
                .andExpect(jsonPath("$.pasajeros[0].empresaId").value(empresa));
        mvc.perform(buscar(solicitante, busqueda("NOMBRE", sufijo, 1, 50))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pasajeros.length()").value(1)).andExpect(jsonPath("$.hayMas").value(false));
        for (String campo : List.of("DOCUMENTO", "CORREO", "EMPRESA")) {
            mvc.perform(buscar(solicitante, busqueda(campo, sufijo, 0, 1)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.pasajeros.length()").value(1));
        }
        mvc.perform(buscar(solicitante, busqueda("NOMBRE", "%", 0, 50))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pasajeros.length()").value(0));
        mvc.perform(buscar(solicitante, busqueda("NOMBRE", "_", 0, 50))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pasajeros.length()").value(0));
        mvc.perform(buscar(solicitante, busqueda("NOMBRE", sufijo, 0, 51))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Datos de pasajero invalidos"));
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_busqueda_pasajeros WHERE actor_id = ?",
                Integer.class, solicitante.usuarioId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_busqueda_pasajeros "
                + "WHERE actor_id = ? AND resultado = 'SIN_RESULTADOS'", Integer.class, solicitante.usuarioId()));
        assertEquals(Set.of("ID_AUDITORIA", "ACTOR_ID", "FECHA_EVENTO", "RESULTADO", "CANTIDAD"),
                Set.copyOf(jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'AUDITORIA_BUSQUEDA_PASAJEROS'", String.class)));
    }

    @Test
    void limitaTreintaPorMinutoInclusoConSolicitudesConcurrentes() throws Exception {
        Sesion actor = iniciarSesion("SOLICITANTE");
        Sesion otro = iniciarSesion("SOLICITANTE");
        String body = busqueda("NOMBRE", sufijo, 0, 1);
        for (int i = 0; i < 29; i++) {
            mvc.perform(buscar(actor, body)).andExpect(status().isOk());
        }
        CountDownLatch inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tarea = (java.util.concurrent.Callable<Integer>) () -> {
                inicio.await();
                return mvc.perform(buscar(actor, body)).andReturn().getResponse().getStatus();
            };
            var a = executor.submit(tarea);
            var b = executor.submit(tarea);
            inicio.countDown();
            assertEquals(List.of(200, 429), java.util.stream.Stream.of(a.get(20, TimeUnit.SECONDS),
                    b.get(20, TimeUnit.SECONDS)).sorted().toList());
        }
        mvc.perform(buscar(otro, body)).andExpect(status().isOk());
        assertEquals(30, jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_busqueda_pasajeros "
                + "WHERE actor_id = ? AND resultado <> 'LIMITADA'", Integer.class, actor.usuarioId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_busqueda_pasajeros "
                + "WHERE actor_id = ? AND resultado = 'LIMITADA'", Integer.class, actor.usuarioId()));
        jdbc.update("UPDATE auditoria_busqueda_pasajeros SET fecha_evento = DATEADD('SECOND', -61, CURRENT_TIMESTAMP) WHERE actor_id = ?",
                actor.usuarioId());
        mvc.perform(buscar(actor, body)).andExpect(status().isOk());
    }

    @Test
    void noDevuelveResultadosSiFallaLaAuditoria() throws Exception {
        Sesion actor = iniciarSesion("SOLICITANTE");
        jdbc.execute("ALTER TABLE auditoria_busqueda_pasajeros ADD CONSTRAINT chk_auditoria_test CHECK (actor_id <> "
                + actor.usuarioId() + ")");
        try {
            mvc.perform(buscar(actor, busqueda("NOMBRE", sufijo, 0, 10)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("Operacion de pasajeros no disponible"))
                    .andExpect(jsonPath("$.pasajeros").doesNotExist());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_busqueda_pasajeros WHERE actor_id = ?",
                    Integer.class, actor.usuarioId()));
        } finally {
            jdbc.execute("ALTER TABLE auditoria_busqueda_pasajeros DROP CONSTRAINT chk_auditoria_test");
        }
    }

    @Test
    void vuelveAValidarLaSesionDespuesDeBloquearAlActor() throws Exception {
        Sesion actor = iniciarSesion("SOLICITANTE");
        var claims = jwt.parseToken(actor.token().substring(7));
        var identidad = new IdentidadSesion(actor.usuarioId(), claims.get("sessionId", String.class),
                claims.getExpiration().toInstant().atOffset(java.time.ZoneOffset.UTC));
        jdbc.update("UPDATE sesiones_usuario SET activa = 0 WHERE sesion_id = ?", identidad.sesionId());
        var consulta = new BusquedaPasajeroRequest(BusquedaPasajeroRequest.Campo.NOMBRE, sufijo, 0, 10);
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> pasajeros.buscar(identidad, consulta))
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_busqueda_pasajeros WHERE actor_id = ?",
                Integer.class, actor.usuarioId()));
    }

    private Sesion iniciarSesion(String rol) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("MERGE INTO roles (nombre_rol) KEY (nombre_rol) VALUES (?)", rol);
        long rolId = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, rol);
        String email = "usuario" + id + "@example.invalid";
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) "
                + "VALUES (?, ?, ?, 'Usuario', ?, CURRENT_TIMESTAMP)", pais, tipo, "U" + id, email);
        long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, ?, 1)",
                persona, rolId, new BCryptPasswordEncoder().encode("password"));
        long usuario = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, persona);
        String respuesta = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", "password"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Sesion("Bearer " + json.readTree(respuesta).get("accessToken").asString(), usuario);
    }

    private String pasajero(String documento, String nombre, String email, String telefono) {
        return json.writeValueAsString(Map.of("nombreCompleto", nombre, "paisDocumentoId", pais,
                "tipoDocumentoId", tipo, "numeroDocumento", documento, "email", email,
                "telefono", telefono, "empresaId", empresa));
    }

    private String busqueda(String campo, String texto, int pagina, int tamano) {
        return json.writeValueAsString(Map.of("campo", campo, "texto", texto, "pagina", pagina, "tamano", tamano));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder crear(Sesion sesion, String body) {
        return post("/api/pasajeros").header("Authorization", sesion.token()).contentType("application/json").content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder buscar(Sesion sesion, String body) {
        return post("/api/pasajeros/busquedas").header("Authorization", sesion.token()).contentType("application/json").content(body);
    }

    private record Sesion(String token, long usuarioId) {}
}
