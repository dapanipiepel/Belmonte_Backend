package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.belmonte.usuarios_service.security.TokenUtil;
import com.belmonte.usuarios_service.service.RelojBaseDatos;
import com.belmonte.usuarios_service.service.UsuarioService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "jwt.secret=test-jwt-secret-not-for-deployment",
        "internal.api.secret=test-internal-secret",
        "spring.datasource.url=jdbc:h2:mem:identidadtest;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class IdentidadHttpTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private UsuarioService usuarios;
    @Autowired private TokenUtil tokens;
    @Autowired private ObjectMapper json;
    @MockitoBean private JavaMailSender mail;
    @MockitoSpyBean private RelojBaseDatos reloj;
    private String origen;
    private long persona;
    private long rol;
    private String email;
    private String admin;
    private String solicitante;

    @BeforeEach
    void preparar() {
        origen = UUID.randomUUID().toString();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES (?)", "Pais " + suffix);
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES (?)", suffix);
        long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = ?", Long.class, "Pais " + suffix);
        long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = ?", Long.class, suffix);
        for (String nombre : List.of("ADMINISTRADOR", "SOLICITANTE")) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE nombre_rol = ?", Integer.class, nombre) == 0) {
                jdbc.update("INSERT INTO roles (nombre_rol) VALUES (?)", nombre);
            }
        }
        rol = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = 'SOLICITANTE'", Long.class);
        email = suffix + "@example.invalid";
        persona = crearPersona(pais, tipo, suffix, email);
        admin = crearSesion(pais, tipo, "a" + suffix, "ADMINISTRADOR");
        solicitante = crearSesion(pais, tipo, "s" + suffix, "SOLICITANTE");
    }

    @Test
    void invitacionUltimaVigenteUnUsoYPermisos() throws Exception {
        String cuerpo = "{\"personaId\":" + persona + ",\"rolId\":" + rol + "}";
        mvc.perform(post("/api/invitaciones").contentType("application/json").content(cuerpo))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/invitaciones").header("Authorization", solicitante)
                .contentType("application/json").content(cuerpo)).andExpect(status().isForbidden());
        String primero = invitar(cuerpo);
        String ultimo = invitar(cuerpo);
        assertNotEquals(primero, ultimo);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM invitaciones WHERE token_hash = ? AND persona_id = ?",
                Integer.class, tokens.hashTokenSHA256(ultimo), persona));
        mvc.perform(publico("/api/auth/activar", tokenBody(primero, "nueva"))).andExpect(status().isBadRequest());
        mvc.perform(publico("/api/auth/activar", tokenBody(ultimo, "nueva")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(publico("/api/auth/activar", tokenBody(ultimo, "otra"))).andExpect(status().isBadRequest());
        assertNotNull(usuarios.iniciarSesion(email, "nueva").get("accessToken"));
        mvc.perform(post("/api/invitaciones").header("Authorization", admin)
                .contentType("application/json").content(cuerpo)).andExpect(status().isConflict());
    }

    @Test
    void venceA48HorasYNoConsumeAlFallar() throws Exception {
        String token = invitar("{\"personaId\":" + persona + ",\"rolId\":" + rol + "}");
        var creacion = jdbc.queryForObject("SELECT fecha_creacion FROM invitaciones WHERE token_hash = ?",
                java.time.OffsetDateTime.class, tokens.hashTokenSHA256(token));
        var expiracion = jdbc.queryForObject("SELECT fecha_expiracion FROM invitaciones WHERE token_hash = ?",
                java.time.OffsetDateTime.class, tokens.hashTokenSHA256(token));
        assertEquals(creacion.plusHours(48), expiracion);
        mvc.perform(publico("/api/auth/activar", tokenBody(token, "ñ".repeat(37)))).andExpect(status().isBadRequest());
        doReturn(expiracion).when(reloj).ahora();
        try {
            mvc.perform(publico("/api/auth/activar", tokenBody(token, "nueva"))).andExpect(status().isBadRequest());
        } finally {
            reset(reloj);
        }
        mvc.perform(publico("/api/auth/activar", tokenBody(token, "nueva"))).andExpect(status().isOk());
    }

    @Test
    void recuperacionNoRevelaCuentaYRevocaSesiones() throws Exception {
        String usuarioEmail = "s" + email;
        String solicitud = "{\"email\":\"" + usuarioEmail + "\"}";
        String existente = mvc.perform(publico("/api/auth/recuperacion", solicitud)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String token = ultimoToken();
        String inexistente = mvc.perform(publico("/api/auth/recuperacion", "{\"email\":\"nadie@example.invalid\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(existente), json.readTree(inexistente));
        mvc.perform(publico("/api/auth/recuperacion/cambiar", tokenBody(token, "nueva")))
                .andExpect(status().isOk());
        mvc.perform(publico("/api/auth/recuperacion/cambiar", tokenBody(token, "nueva")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").header("Authorization", solicitante)).andExpect(status().isUnauthorized());
        assertNotNull(usuarios.iniciarSesion(usuarioEmail, "nueva").get("accessToken"));
        doThrow(new MailSendException("fallo")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(publico("/api/auth/recuperacion", solicitud)).andExpect(status().isAccepted())
                .andExpect(content().json(existente));
    }

    @Test
    void soloUnaActivacionConcurrenteGana() throws Exception {
        String token = invitar("{\"personaId\":" + persona + ",\"rolId\":" + rol + "}");
        CountDownLatch inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tarea = (java.util.concurrent.Callable<Integer>) () -> {
                inicio.await();
                return mvc.perform(publico("/api/auth/activar", tokenBody(token, "nueva")))
                        .andReturn().getResponse().getStatus();
            };
            var primero = executor.submit(tarea);
            var segundo = executor.submit(tarea);
            inicio.countDown();
            assertEquals(List.of(200, 400), java.util.stream.Stream.of(
                    primero.get(20, TimeUnit.SECONDS), segundo.get(20, TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM usuarios WHERE persona_id = ?", Integer.class, persona));
    }

    @Test
    void limitaPeticionesPublicasPorOrigen() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(publico("/api/auth/recuperacion", "{\"email\":\"nadie@example.invalid\"}"))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(publico("/api/auth/recuperacion", "{\"email\":\"nadie@example.invalid\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void cambioDeCorreoInvalidaInvitacionYErrorSmtpEsVisibleAlAdmin() throws Exception {
        String cuerpo = "{\"personaId\":" + persona + ",\"rolId\":" + rol + "}";
        String token = invitar(cuerpo);
        jdbc.update("UPDATE personas SET email = ? WHERE id_persona = ?", "otro-" + email, persona);
        mvc.perform(publico("/api/auth/activar", tokenBody(token, "nueva"))).andExpect(status().isBadRequest());
        doThrow(new MailSendException("fallo")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(post("/api/invitaciones").header("Authorization", admin)
                .contentType("application/json").content(cuerpo)).andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM usuarios WHERE persona_id = ?", Integer.class, persona));
    }

    @Test
    void reemitirParaElMismoCorreoInvalidaInvitacionDePersonaAnterior() throws Exception {
        String anterior = invitar("{\"personaId\":" + persona + ",\"rolId\":" + rol + "}");
        jdbc.update("UPDATE personas SET email = ? WHERE id_persona = ?", "otro-" + email, persona);
        jdbc.update("""
                INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion)
                SELECT pais_documento_id, tipo_documento_id, CONCAT(rut_dni, 'b'), nombre_completo, ?, CURRENT_TIMESTAMP
                FROM personas WHERE id_persona = ?
                """, email, persona);
        long otra = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
        invitar("{\"personaId\":" + otra + ",\"rolId\":" + rol + "}");
        jdbc.update("UPDATE personas SET email = ? WHERE id_persona = ?", "tercero-" + email, otra);
        jdbc.update("UPDATE personas SET email = ? WHERE id_persona = ?", email, persona);
        mvc.perform(publico("/api/auth/activar", tokenBody(anterior, "nueva"))).andExpect(status().isBadRequest());
    }

    private String invitar(String cuerpo) throws Exception {
        mvc.perform(post("/api/invitaciones").header("Authorization", admin)
                .contentType("application/json").content(cuerpo)).andExpect(status().isCreated());
        return ultimoToken();
    }

    private String ultimoToken() {
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(captor.capture());
        String texto = captor.getAllValues().getLast().getText();
        var matcher = Pattern.compile("[?&]token=([A-Za-z0-9_-]{43})").matcher(texto);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private MockHttpServletRequestBuilder publico(String ruta, String cuerpo) {
        return post(ruta).contentType("application/json").content(cuerpo).with(request -> {
            request.setRemoteAddr(origen);
            return request;
        });
    }

    private String tokenBody(String token, String password) {
        return "{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}";
    }

    private long crearPersona(long pais, long tipo, String documento, String correo) {
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) "
                + "VALUES (?, ?, ?, 'Prueba', ?, CURRENT_TIMESTAMP)", pais, tipo, documento, correo);
        return jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, correo);
    }

    private String crearSesion(long pais, long tipo, String sufijo, String nombreRol) {
        String correo = sufijo + "@example.invalid";
        long id = crearPersona(pais, tipo, sufijo, correo);
        long rolId = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, nombreRol);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, ?, 1)",
                id, rolId, new BCryptPasswordEncoder().encode("password"));
        return "Bearer " + usuarios.iniciarSesion(correo, "password").get("accessToken");
    }
}
