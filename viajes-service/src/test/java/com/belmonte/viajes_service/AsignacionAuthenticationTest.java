package com.belmonte.viajes_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AsignacionAuthenticationTest {
    private static final String JWT_SECRET = "test-jwt-secret-not-for-deployment";
    private static final String INTERNAL_SECRET = "test-internal-secret";
    private static final HttpServer SESSIONS = startSessionService();

    @Autowired
    private MockMvc mvc;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> JWT_SECRET);
        registry.add("internal.api.secret", () -> INTERNAL_SECRET);
        registry.add("usuarios.service.uri",
                () -> "http://127.0.0.1:" + SESSIONS.getAddress().getPort());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:asignaciontest;DB_CLOSE_DELAY=-1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @AfterAll
    static void stopSessionService() {
        SESSIONS.stop(0);
    }

    @Test
    void activeAdministratorCreatesAndAssignsPendingTrip() throws Exception {
        jdbc.update("INSERT INTO estados_traslado (codigo_estado) VALUES ('PENDIENTE')");
        jdbc.update("INSERT INTO estados_traslado (codigo_estado) VALUES ('ASIGNADO')");
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");

        mvc.perform(post("/api/traslados")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"origen":{"comunaId":1,"direccion":"Origen"},
                                 "destino":{"comunaId":2,"direccion":"Destino"},
                                 "fechaHoraViaje":"2026-12-01T10:00:00-03:00",
                                 "fechaHoraTerminoEstimado":"2026-12-01T11:00:00-03:00",
                                 "pasajeroIds":[101]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));
        Long id = jdbc.queryForObject("SELECT id_traslado FROM traslados WHERE solicitante_id = 7", Long.class);

        mvc.perform(put("/api/traslados/{id}/asignar", id)
                        .header("Authorization", authorization)
                        .header("Idempotency-Key", "operacion-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conductorId\":2,\"vehiculoId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ASIGNADO"))
                .andExpect(jsonPath("$.conductorId").value(2))
                .andExpect(jsonPath("$.vehiculoId").value(3));

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
    }

    @Test
    void rejectsMissingOrRevokedSessionAndNonAdministrator() throws Exception {
        var request = put("/api/traslados/10/asignar")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conductorId\":2,\"vehiculoId\":3}");
        mvc.perform(request).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/traslados/10/asignar")
                        .header("Authorization", "Bearer " + token("revocada", "ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conductorId\":2,\"vehiculoId\":3}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/traslados/10/asignar")
                        .header("Authorization", "Bearer " + token("activa", "CONDUCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conductorId\":2,\"vehiculoId\":3}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void preservesControllerErrorsOverRealHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/traslados/999999999/asignar");

        for (String role : List.of("ADMINISTRADOR", "CONDUCTOR")) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token("activa", role))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"conductorId\":2,\"vehiculoId\":3}"))
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            assertEquals(role.equals("ADMINISTRADOR") ? 404 : 403, status);
        }

        HttpRequest directError = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/error")).GET().build();
        assertEquals(401, client.send(directError, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private static String token(String sessionId, String role) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("test@example.invalid")
                .claim("usuarioId", 7L)
                .claim("sessionId", sessionId)
                .claim("roles", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(key))
                .compact();
    }

    private static HttpServer startSessionService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/auth/sessions/", exchange -> {
                boolean active = "/api/auth/sessions/activa/validate".equals(exchange.getRequestURI().getPath())
                        && INTERNAL_SECRET.equals(exchange.getRequestHeaders()
                                .getFirst("X-Belmonte-Internal-Secret"));
                exchange.sendResponseHeaders(active ? 200 : 401, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo iniciar el servicio de sesiones de prueba", exception);
        }
    }
}
