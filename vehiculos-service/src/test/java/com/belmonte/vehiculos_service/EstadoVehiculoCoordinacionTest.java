package com.belmonte.vehiculos_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.belmonte.vehiculos_service.service.EstadoVehiculoIdempotenteService;
import com.belmonte.vehiculos_service.service.EstadoVehiculoTransacciones;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class EstadoVehiculoCoordinacionTest {
    private static final String SECRET = "test-jwt-secret-not-for-deployment";
    private static final ExecutorService SERVER_THREADS = Executors.newCachedThreadPool();
    private static final Map<String, String> BARRERAS = new ConcurrentHashMap<>();
    private static final Map<String, String> PROPOSITOS = new ConcurrentHashMap<>();
    private static volatile boolean rechazar;
    private static volatile boolean respuestaInvalida;
    private static volatile boolean perderCierre;
    private static volatile boolean fallarAntesDeCerrar;
    private static volatile CountDownLatch preparacionIniciada;
    private static volatile CountDownLatch continuarPreparacion;
    private static final HttpServer DEPENDENCIAS = startDependencies();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EstadoVehiculoTransacciones transacciones;
    @Autowired private EstadoVehiculoIdempotenteService estados;
    @Autowired private PlatformTransactionManager transactionManager;
    private Long vehiculo;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> SECRET);
        registry.add("internal.api.secret", () -> "test-internal-secret");
        registry.add("usuarios.service.uri", () -> "http://127.0.0.1:" + DEPENDENCIAS.getAddress().getPort());
        registry.add("viajes.service.uri", () -> "http://127.0.0.1:" + DEPENDENCIAS.getAddress().getPort());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:vehiculoscoordinacion;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void reset() {
        BARRERAS.clear();
        PROPOSITOS.clear();
        rechazar = false;
        respuestaInvalida = false;
        perderCierre = false;
        fallarAntesDeCerrar = false;
        preparacionIniciada = null;
        continuarPreparacion = null;
        vehiculo = jdbc.queryForObject("SELECT id_vehiculo FROM vehiculos WHERE patente = 'ABCD-12'", Long.class);
        jdbc.update("UPDATE vehiculos SET activo = true WHERE id_vehiculo = ?", vehiculo);
    }

    @AfterAll
    static void stopDependencies() {
        DEPENDENCIAS.stop(0);
        SERVER_THREADS.shutdownNow();
    }

    @Test
    void retriesReturnOriginalResponseAndProtectActorAndRequest() throws Exception {
        String admin = token(7L, "ADMINISTRADOR");
        mvc.perform(patch("/api/vehiculos/{id}/estado", vehiculo).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false}"))
                .andExpect(status().isBadRequest());
        var first = mvc.perform(change(admin, "estado-original", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(false))
                .andReturn().getResponse().getContentAsString();
        assertEquals("BAJA", propositoEnViajes("estado-original"));
        mvc.perform(change(admin, "estado-activar", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true));
        assertEquals("CAMBIO_COMPATIBLE", propositoEnViajes("estado-activar"));
        var replay = mvc.perform(change(admin, "estado-original", false))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT activo FROM vehiculos WHERE id_vehiculo = ?",
                Boolean.class, vehiculo));
        mvc.perform(change(admin, "estado-original", true)).andExpect(status().isConflict());
        mvc.perform(change(token(8L, "ADMINISTRADOR"), "estado-original", false)).andExpect(status().isConflict());
        mvc.perform(get("/api/vehiculos/operaciones/estado").param("clave", "estado-original")
                .header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COMPLETADA"))
                .andExpect(jsonPath("$.resultado.activo").value(false));
        mvc.perform(get("/api/vehiculos/operaciones/estado").param("clave", "estado-original")
                .header("Authorization", token(8L, "ADMINISTRADOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
        mvc.perform(get("/api/vehiculos/operaciones/estado").param("clave", "estado-original")
                .header("Authorization", token(7L, "CONDUCTOR"))).andExpect(status().isForbidden());
        mvc.perform(change(token(null, "ADMINISTRADOR"), "sin-actor", false)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectionAndUncertainCloseAreRecoverable() throws Exception {
        String admin = token(7L, "ADMINISTRADOR");
        rechazar = true;
        mvc.perform(change(admin, "estado-rechazado", false)).andExpect(status().isConflict());
        rechazar = false;
        mvc.perform(change(admin, "estado-rechazado", false)).andExpect(status().isConflict());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT activo FROM vehiculos WHERE id_vehiculo = ?",
                Boolean.class, vehiculo));
        respuestaInvalida = true;
        mvc.perform(change(admin, "estado-recuperable", false)).andExpect(status().isServiceUnavailable());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT activo FROM vehiculos WHERE id_vehiculo = ?",
                Boolean.class, vehiculo));
        respuestaInvalida = false;
        perderCierre = true;
        mvc.perform(change(admin, "estado-recuperable", false)).andExpect(status().isServiceUnavailable());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT activo FROM vehiculos WHERE id_vehiculo = ?",
                Boolean.class, vehiculo));
        mvc.perform(get("/api/vehiculos/operaciones/estado").param("clave", "estado-recuperable")
                .header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));
        jdbc.update("UPDATE operaciones_estado_vehiculo SET expira_en = ? WHERE clave = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), "estado-recuperable");
        transacciones.limpiarRespuestasExpiradas();
        assertEquals("APLICADA", jdbc.queryForObject("SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?",
                String.class, "estado-recuperable"));
        mvc.perform(change(admin, "estado-recuperable", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
    }

    @Test
    void pendingKeepsOriginalSessionAndIntentUntilApplied() throws Exception {
        String original = token(7L, "ADMINISTRADOR", "sesion-original");
        String otra = token(7L, "ADMINISTRADOR", "otra-sesion", 120);
        respuestaInvalida = true;
        mvc.perform(change(original, "estado-intencion", false)).andExpect(status().isServiceUnavailable());
        var key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(SECRET.getBytes(StandardCharsets.UTF_8)));
        var vencimientoOriginal = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(original.substring(7)).getPayload().getExpiration().toInstant();
        assertEquals(vencimientoOriginal, jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_estado_vehiculo WHERE clave = ?",
                OffsetDateTime.class, "estado-intencion").toInstant());
        assertEquals("sesion-original", jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "estado-intencion"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT solicitud_activo FROM operaciones_estado_vehiculo WHERE clave = ?", Integer.class, "estado-intencion"));
        mvc.perform(change(otra, "estado-intencion", false)).andExpect(status().isServiceUnavailable());
        assertEquals("sesion-original", jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "estado-intencion"));
        assertEquals(vencimientoOriginal, jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_estado_vehiculo WHERE clave = ?",
                OffsetDateTime.class, "estado-intencion").toInstant());
        mvc.perform(get("/api/vehiculos/operaciones/estado").param("clave", "estado-intencion")
                .header("Authorization", original)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.sesionOriginal").doesNotExist())
                .andExpect(jsonPath("$.jwtOriginalExpiraEn").doesNotExist())
                .andExpect(jsonPath("$.solicitudActivo").doesNotExist());
        respuestaInvalida = false;
        perderCierre = true;
        mvc.perform(change(otra, "estado-intencion", false)).andExpect(status().isServiceUnavailable());
        assertEquals("APLICADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "estado-intencion"));
        assertNull(jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "estado-intencion"));
        assertNull(jdbc.queryForObject(
                "SELECT solicitud_activo FROM operaciones_estado_vehiculo WHERE clave = ?", Integer.class, "estado-intencion"));
        assertNull(jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_estado_vehiculo WHERE clave = ?",
                OffsetDateTime.class, "estado-intencion"));
    }

    @Test
    void tokenWithoutExpirationDoesNotInventADeadline() throws Exception {
        var key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(SECRET.getBytes(StandardCharsets.UTF_8)));
        String token = "Bearer " + Jwts.builder().subject("test@example.invalid").claim("usuarioId", 7L)
                .claim("sessionId", "activa").claim("roles", List.of("ADMINISTRADOR")).signWith(key).compact();
        respuestaInvalida = true;
        mvc.perform(change(token, "estado-sin-exp", false)).andExpect(status().isServiceUnavailable());
        assertNull(jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_estado_vehiculo WHERE clave = ?",
                OffsetDateTime.class, "estado-sin-exp"));
    }

    @Test
    void expiresOnlyCompletedResponsesAndKeepsKeyTombstone() throws Exception {
        String admin = token(7L, "ADMINISTRADOR");
        mvc.perform(change(admin, "estado-expiracion", false)).andExpect(status().isOk());
        jdbc.update("UPDATE operaciones_estado_vehiculo SET expira_en = ? WHERE clave = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), "estado-expiracion");
        transacciones.limpiarRespuestasExpiradas();
        assertEquals("EXPIRADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "estado-expiracion"));
        assertNull(jdbc.queryForObject("SELECT actor_id FROM operaciones_estado_vehiculo WHERE clave = ?",
                Long.class, "estado-expiracion"));
        assertNull(jdbc.queryForObject("SELECT respuesta_json FROM operaciones_estado_vehiculo WHERE clave = ?",
                String.class, "estado-expiracion"));
        mvc.perform(change(admin, "estado-expiracion", false)).andExpect(status().isConflict());
        mvc.perform(get("/api/vehiculos/operaciones/estado").param("clave", "estado-expiracion")
                .header("Authorization", admin)).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
    }

    @Test
    void appliedOperationClosesAutomaticallyWithoutRepeatingMutation() throws Exception {
        String admin = token(7L, "ADMINISTRADOR");
        perderCierre = true;
        mvc.perform(change(admin, "estado-cierre-automatico", false))
                .andExpect(status().isServiceUnavailable());
        assertEquals("APLICADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class,
                "estado-cierre-automatico"));
        String barrera = jdbc.queryForObject(
                "SELECT barrera_id FROM operaciones_estado_vehiculo WHERE clave = ?", String.class,
                "estado-cierre-automatico");
        assertEquals("CERRADA", BARRERAS.get(barrera));
        estados.recuperarAplicadas();
        assertEquals("COMPLETADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class,
                "estado-cierre-automatico"));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT activo FROM vehiculos WHERE id_vehiculo = ?",
                Boolean.class, vehiculo));
        mvc.perform(get("/api/vehiculos/operaciones/estado").param("clave", "estado-cierre-automatico")
                .header("Authorization", admin)).andExpect(jsonPath("$.estado").value("COMPLETADA"))
                .andExpect(jsonPath("$.resultado.activo").value(false));
    }

    @Test
    void automaticRecoveryRetriesAnOpenBarrierAfterUnavailableClose() throws Exception {
        String admin = token(7L, "ADMINISTRADOR");
        fallarAntesDeCerrar = true;
        mvc.perform(change(admin, "estado-cierre-abierto", false)).andExpect(status().isServiceUnavailable());
        String barrera = jdbc.queryForObject(
                "SELECT barrera_id FROM operaciones_estado_vehiculo WHERE clave = ?", String.class,
                "estado-cierre-abierto");
        assertEquals("ABIERTA", BARRERAS.get(barrera));
        estados.recuperarAplicadas();
        assertEquals("APLICADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "estado-cierre-abierto"));
        fallarAntesDeCerrar = false;
        estados.recuperarAplicadas();
        assertEquals("CERRADA", BARRERAS.get(barrera));
        assertEquals("COMPLETADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "estado-cierre-abierto"));
    }

    @Test
    void missingVehicleResultIsStoredAndReleased() throws Exception {
        String admin = token(7L, "ADMINISTRADOR");
        long missing = 999999999L;
        var first = mvc.perform(patch("/api/vehiculos/{id}/estado", missing)
                .header("Authorization", admin).header("Idempotency-Key", "vehiculo-inexistente")
                .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var second = mvc.perform(patch("/api/vehiculos/{id}/estado", missing)
                .header("Authorization", admin).header("Idempotency-Key", "vehiculo-inexistente")
                .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals("CERRADA", BARRERAS.get(jdbc.queryForObject(
                "SELECT barrera_id FROM operaciones_estado_vehiculo WHERE clave = ?", String.class,
                "vehiculo-inexistente")));
    }

    @Test
    void remoteWaitDoesNotHoldSqlTransactionAndSameKeyHasOneResult() throws Exception {
        String admin = token(7L, "ADMINISTRADOR");
        assertThrows(IllegalTransactionStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> estados.cambiar("transaccion-no-permitida", 7L, "activa", vehiculo, false, null)));
        preparacionIniciada = new CountDownLatch(1);
        continuarPreparacion = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> mvc.perform(change(admin, "estado-concurrente", false))
                    .andReturn().getResponse());
            assertEquals(true, preparacionIniciada.await(5, TimeUnit.SECONDS));
            assertEquals("PENDIENTE", jdbc.queryForObject(
                    "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?",
                    String.class, "estado-concurrente"));
            var second = executor.submit(() -> mvc.perform(change(admin, "estado-concurrente", false))
                    .andReturn().getResponse());
            continuarPreparacion.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(200, a.getStatus());
            assertEquals(200, b.getStatus());
            assertEquals(a.getContentAsString(), b.getContentAsString());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM operaciones_estado_vehiculo WHERE clave = ?",
                    Integer.class, "estado-concurrente"));
        } finally {
            continuarPreparacion.countDown();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder change(
            String authorization, String key, boolean active) {
        return patch("/api/vehiculos/{id}/estado", vehiculo).header("Authorization", authorization)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":" + active + "}");
    }

    private String propositoEnViajes(String clave) {
        String id = jdbc.queryForObject("SELECT barrera_id FROM operaciones_estado_vehiculo WHERE clave = ?",
                String.class, clave);
        return PROPOSITOS.get(id);
    }

    private static String token(Long usuarioId, String role) throws Exception {
        return token(usuarioId, role, "activa");
    }

    private static String token(Long usuarioId, String role, String sessionId) throws Exception {
        return token(usuarioId, role, sessionId, 60);
    }

    private static String token(Long usuarioId, String role, String sessionId, long segundos) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        var builder = Jwts.builder().subject("test@example.invalid").claim("sessionId", sessionId)
                .claim("roles", List.of(role)).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(segundos)));
        if (usuarioId != null) builder.claim("usuarioId", usuarioId);
        return "Bearer " + builder.signWith(Keys.hmacShaKeyFor(key)).compact();
    }

    private static HttpServer startDependencies() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(SERVER_THREADS);
            server.createContext("/api/auth/sessions/", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.createContext("/internal/barreras/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                boolean cerrar = path.endsWith("/cerrar");
                String id = path.substring("/internal/barreras/".length()).replace("/cerrar", "");
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!"test-internal-secret".equals(exchange.getRequestHeaders().getFirst("X-Belmonte-Internal-Secret"))) {
                    exchange.sendResponseHeaders(403, -1);
                } else if (cerrar && fallarAntesDeCerrar) {
                    exchange.sendResponseHeaders(503, -1);
                } else if (cerrar) {
                    BARRERAS.put(id, "CERRADA");
                    if (perderCierre) {
                        perderCierre = false;
                        exchange.sendResponseHeaders(503, -1);
                    } else {
                        exchange.sendResponseHeaders(204, -1);
                    }
                } else if (respuestaInvalida) {
                    byte[] data = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(409, data.length);
                    exchange.getResponseBody().write(data);
                } else {
                    if (preparacionIniciada != null) {
                        preparacionIniciada.countDown();
                        try {
                            if (!continuarPreparacion.await(5, TimeUnit.SECONDS)) throw new IOException("Timeout de prueba");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                        }
                    }
                    PROPOSITOS.put(id, body.contains("CAMBIO_COMPATIBLE") ? "CAMBIO_COMPATIBLE" : "BAJA");
                    String result = BARRERAS.computeIfAbsent(id,
                            ignored -> rechazar && body.contains("BAJA") ? "RECHAZADA" : "ABIERTA");
                    byte[] data = ("{\"estado\":\"" + result + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders("RECHAZADA".equals(result) ? 409 : 200, data.length);
                    exchange.getResponseBody().write(data);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudieron iniciar dependencias de prueba", exception);
        }
    }
}
