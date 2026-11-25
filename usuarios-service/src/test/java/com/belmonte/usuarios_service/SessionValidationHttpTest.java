package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.belmonte.usuarios_service.model.Rol;
import com.belmonte.usuarios_service.model.Persona;
import com.belmonte.usuarios_service.model.Usuario;
import com.belmonte.usuarios_service.service.RolService;
import com.belmonte.usuarios_service.service.UsuarioService;
import com.belmonte.usuarios_service.service.JwtService;
import com.belmonte.usuarios_service.service.RelojBaseDatos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=test-jwt-secret-not-for-deployment",
        "internal.api.secret=test-internal-secret",
        "spring.datasource.url=jdbc:h2:mem:usuariosauthtest;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class SessionValidationHttpTest {
    @LocalServerPort
    private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private RolService roles;
    @Autowired private UsuarioService usuarios;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private RelojBaseDatos reloj;

    @Test
    void loginPreservesResponseAndPersistsActiveSession() throws Exception {
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES ('Pais login')");
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES ('LOGIN')");
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES ('ROL_LOGIN')");
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = 'Pais login'", Long.class);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = 'LOGIN'", Long.class);
        Long rol = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = 'ROL_LOGIN'", Long.class);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) VALUES (?, ?, 'LOGIN1', 'Login', 'login@example.invalid', CURRENT_TIMESTAMP)", pais, tipo);
        Long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = 'login@example.invalid'", Long.class);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, ?, 1)",
                persona, rol, new BCryptPasswordEncoder().encode("password"));
        Long usuario = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, persona);
        var respuesta = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"login@example.invalid\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(43200))
                .andExpect(jsonPath("$.usuario.idUsuario").value(usuario))
                .andExpect(jsonPath("$.usuario.rol").value("ROL_LOGIN"))
                .andExpect(jsonPath("$.usuario.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sesiones_usuario WHERE usuario_id = ? AND activa = 1", Integer.class, usuario));
        String token = json.readTree(respuesta).get("accessToken").asString();
        String sesion = jwt.parseToken(token).get("sessionId", String.class);
        var vencimientoToken = jwt.parseToken(token).getExpiration().toInstant();
        var vencimientoSesion = jdbc.queryForObject("SELECT fecha_expiracion_instante FROM sesiones_usuario WHERE sesion_id = ?",
                java.time.OffsetDateTime.class, sesion);
        assertEquals(vencimientoToken, vencimientoSesion.toInstant());
        mvc.perform(get("/api/auth/sessions/{id}/validate", sesion)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret")
                .param("jwtExpiraEn", Long.toString(vencimientoToken.toEpochMilli()))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/sessions/{id}/validate", sesion)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret")
                .param("jwtExpiraEn", "0")).andExpect(status().isUnauthorized());
        doReturn(vencimientoSesion.plusSeconds(1)).when(reloj).ahora();
        try {
            mvc.perform(get("/api/auth/sessions/{id}/validate", sesion)
                    .header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        } finally {
            reset(reloj);
        }
        doThrow(new DataAccessResourceFailureException("Reloj no disponible")).when(reloj).ahora();
        try {
            mvc.perform(get("/api/auth/sessions/{id}/validate", sesion)
                    .header("X-Belmonte-Internal-Secret", "test-internal-secret")).andExpect(status().isServiceUnavailable());
            mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            reset(reloj);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sesiones_usuario "
                + "WHERE sesion_id = ? AND fecha_expiracion_instante IS NOT NULL", Integer.class, sesion));
        jdbc.update("UPDATE sesiones_usuario SET fecha_expiracion = DATEADD('DAY', -1, CURRENT_TIMESTAMP) WHERE sesion_id = ?", sesion);
        mvc.perform(get("/api/auth/sessions/{id}/validate", sesion)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret")).andExpect(status().isOk());
        jdbc.update("UPDATE sesiones_usuario SET fecha_expiracion_instante = DATEADD('SECOND', -1, CURRENT_TIMESTAMP), "
                + "fecha_expiracion = DATEADD('DAY', 1, CURRENT_TIMESTAMP) WHERE sesion_id = ?", sesion);
        mvc.perform(get("/api/auth/sessions/{id}/validate", sesion)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE sesiones_usuario SET fecha_expiracion_instante = NULL WHERE sesion_id = ?", sesion);
        mvc.perform(get("/api/auth/sessions/{id}/validate", sesion)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE sesiones_usuario SET fecha_expiracion_instante = DATEADD('HOUR', 1, CURRENT_TIMESTAMP) WHERE sesion_id = ?", sesion);
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Sesion cerrada"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sesiones_usuario WHERE usuario_id = ? AND activa = 1", Integer.class, usuario));
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creatingRoleCannotOverwriteExistingRole() {
        Rol original = new Rol();
        original.setNombreRol("ROL_INMUTABLE_TEST");
        Rol saved = roles.crearRol(original);
        Rol replacement = new Rol();
        replacement.setIdRol(saved.getIdRol());
        replacement.setNombreRol("ROL_CAMBIADO_TEST");
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> roles.crearRol(replacement)).getStatusCode().value());
        assertEquals("ROL_INMUTABLE_TEST", jdbc.queryForObject(
                "SELECT nombre_rol FROM roles WHERE id_rol = ?", String.class, saved.getIdRol()));
    }

    @Test
    void registeringUserCannotOverwriteExistingUserOrChangeTheirRoleAndState() {
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES ('Pais registro protegido')");
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES ('REGPROT')");
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES ('ROL_REG_PROTEGIDO')");
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES ('ROL_REG_NUEVO')");
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = 'Pais registro protegido'", Long.class);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = 'REGPROT'", Long.class);
        Long rolOriginal = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = 'ROL_REG_PROTEGIDO'", Long.class);
        Long rolNuevo = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = 'ROL_REG_NUEVO'", Long.class);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) VALUES (?, ?, 'RP1', 'Uno', 'registro1@example.invalid', CURRENT_TIMESTAMP)", pais, tipo);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) VALUES (?, ?, 'RP2', 'Dos', 'registro2@example.invalid', CURRENT_TIMESTAMP)", pais, tipo);
        Long personaOriginal = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = 'registro1@example.invalid'", Long.class);
        Long personaNueva = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = 'registro2@example.invalid'", Long.class);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, 'original', 0)",
                personaOriginal, rolOriginal);
        Long usuarioId = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, personaOriginal);
        Usuario request = new Usuario();
        request.setIdUsuario(usuarioId);
        Persona persona = new Persona();
        persona.setIdPersona(personaNueva);
        request.setPersona(persona);
        Rol rol = new Rol();
        rol.setIdRol(rolNuevo);
        request.setRol(rol);
        request.setPassword("nueva");
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> usuarios.registrarUsuario(request)).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT activo FROM usuarios WHERE id_usuario = ?", Integer.class, usuarioId));
        assertEquals(rolOriginal, jdbc.queryForObject("SELECT rol_id FROM usuarios WHERE id_usuario = ?", Long.class, usuarioId));
        assertEquals(personaOriginal, jdbc.queryForObject("SELECT persona_id FROM usuarios WHERE id_usuario = ?", Long.class, usuarioId));
        assertEquals("original", jdbc.queryForObject("SELECT password FROM usuarios WHERE id_usuario = ?", String.class, usuarioId));
        request.setIdUsuario(null);
        assertEquals(rolNuevo, usuarios.registrarUsuario(request).getRol().getIdRol());
    }

    @Test
    void distinguishesInvalidInternalCredentialFromInvalidSession() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/auth/sessions/desconocida/validate");
        HttpRequest noSecret = HttpRequest.newBuilder(uri).GET().build();
        HttpRequest wrongSecret = HttpRequest.newBuilder(uri)
                .header("X-Belmonte-Internal-Secret", "incorrecta").GET().build();
        HttpRequest correctSecret = HttpRequest.newBuilder(uri)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret").GET().build();
        assertEquals(403, client.send(noSecret, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(403, client.send(wrongSecret, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(401, client.send(correctSecret, HttpResponse.BodyHandlers.discarding()).statusCode());
        HttpRequest directError = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/error")).GET().build();
        assertEquals(401, client.send(directError, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void internalDriverLookupAuthenticatesAndReturnsUserRoleAndStatus() throws Exception {
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES ('Pais de prueba')");
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES ('TEST')");
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES ('CONDUCTOR')");
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES ('CLIENTE')");
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = 'Pais de prueba'", Long.class);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = 'TEST'", Long.class);
        Long conductor = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = 'CONDUCTOR'", Long.class);
        Long cliente = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = 'CLIENTE'", Long.class);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) VALUES (?, ?, 'P1', 'Uno', 'uno@example.invalid', CURRENT_TIMESTAMP)", pais, tipo);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) VALUES (?, ?, 'P2', 'Dos', 'dos@example.invalid', CURRENT_TIMESTAMP)", pais, tipo);
        Long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = 'dos@example.invalid'", Long.class);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, 'test', 1)", persona, conductor);
        Long usuario = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, persona);
        assertNotEquals(persona, usuario);

        mvc.perform(get("/internal/conductores/{id}", usuario)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/conductores/{id}", usuario)
                .header("X-Belmonte-Internal-Secret", "incorrecta")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/conductores/{id}", usuario)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usuarioId").value(usuario))
                .andExpect(jsonPath("$.rol").value("CONDUCTOR")).andExpect(jsonPath("$.activo").value(true));
        jdbc.update("UPDATE usuarios SET activo = 0, rol_id = ? WHERE id_usuario = ?", cliente, usuario);
        mvc.perform(get("/internal/conductores/{id}", usuario)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rol").value("CLIENTE"))
                .andExpect(jsonPath("$.activo").value(false));
        mvc.perform(get("/internal/conductores/999999999")
                .header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isNotFound());
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/internal/conductores/999999999");
        assertEquals(403, client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(404, client.send(HttpRequest.newBuilder(uri)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret").GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
    }
}
