package com.belmonte.vehiculos_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SessionValidationHttpTest {
    private static final String JWT_SECRET = "test-jwt-secret-not-for-deployment";
    private static volatile String ultimaConsultaSesion;
    private static final HttpServer SESSIONS = startSessionService();

    @LocalServerPort
    private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> JWT_SECRET);
        registry.add("internal.api.secret", () -> "test-internal-secret");
        registry.add("usuarios.service.uri", () -> "http://127.0.0.1:" + SESSIONS.getAddress().getPort());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:vehiculosauthtest;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void stopSessionService() {
        SESSIONS.stop(0);
    }

    @Test
    void distinguishesInvalidSessionFromUnavailableSessionService() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/vehiculos");
        for (String session : List.of("activa", "revocada", "error", "credencial", "conexion-cerrada")) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token(session))
                    .GET().build();
            int expected = switch (session) {
                case "activa" -> 200;
                case "revocada" -> 401;
                default -> 503;
            };
            assertEquals(expected, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        }

        HttpRequest directError = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/error")).GET().build();
        assertEquals(401, client.send(directError, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void enviaVencimientoVerificadoAServicioDeSesiones() throws Exception {
        String token = token("activa");
        var key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        long expira = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getExpiration().getTime();
        mvc.perform(get("/api/vehiculos").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertEquals("jwtExpiraEn=" + expira, ultimaConsultaSesion);
    }

    @Test
    void internalVehicleLookupAuthenticatesAndReturnsCapacityAndStatus() throws Exception {
        Long id = jdbc.queryForObject("SELECT id_vehiculo FROM vehiculos WHERE patente = 'ABCD-12'", Long.class);
        mvc.perform(get("/internal/vehiculos/{id}", id)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/vehiculos/{id}", id)
                .header("X-Belmonte-Internal-Secret", "incorrecta")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/vehiculos/{id}", id)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vehiculoId").value(id))
                .andExpect(jsonPath("$.capacidadPasajeros").value(4))
                .andExpect(jsonPath("$.activo").value(true));
        jdbc.update("UPDATE vehiculos SET activo = false WHERE id_vehiculo = ?", id);
        mvc.perform(get("/internal/vehiculos/{id}", id)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(false));
        mvc.perform(get("/internal/vehiculos/999999999")
                .header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isNotFound());
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/internal/vehiculos/999999999");
        assertEquals(403, client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(404, client.send(HttpRequest.newBuilder(uri)
                .header("X-Belmonte-Internal-Secret", "test-internal-secret").GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private static String token(String sessionId) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder().subject("test@example.invalid")
                .claim("sessionId", sessionId)
                .claim("roles", List.of("ADMINISTRADOR"))
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(key)).compact();
    }

    private static HttpServer startSessionService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/auth/sessions/", exchange -> {
                ultimaConsultaSesion = exchange.getRequestURI().getQuery();
                String path = exchange.getRequestURI().getPath();
                if (path.contains("/conexion-cerrada/")) {
                    exchange.close();
                    return;
                }
                int status = path.contains("/activa/") ? 200
                        : path.contains("/credencial/") ? 403
                        : path.contains("/error/") ? 503 : 401;
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo iniciar el servicio de sesiones de prueba", exception);
        }
    }
}
