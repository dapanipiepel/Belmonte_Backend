package com.belmonte.viajes_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.orm.jpa.EntityManagerHolder;
import com.belmonte.viajes_service.service.AsignacionOperacionTransacciones;
import com.belmonte.viajes_service.service.AsignacionIdempotenteService;
import com.belmonte.viajes_service.service.BarreraRecursoService;
import com.belmonte.viajes_service.service.CreacionIdempotenteService;
import com.belmonte.viajes_service.service.CambioTrasladoIdempotenteService;
import com.belmonte.viajes_service.service.ValidacionRecursosClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.belmonte.viajes_service.dto.AsignacionRequest;
import com.belmonte.viajes_service.dto.CancelacionRequest;
import com.belmonte.viajes_service.dto.EstadoRequest;
import com.belmonte.viajes_service.dto.TrasladoResponse;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AsignacionAuthenticationTest {
    private static final String JWT_SECRET = "test-jwt-secret-not-for-deployment";
    private static final String INTERNAL_SECRET = "test-internal-secret";
    private static volatile String ultimaConsultaSesion;
    private static final HttpServer SESSIONS = startSessionService();
    private static volatile CountDownLatch simultaneousSessions;
    private static volatile boolean recursosCaidos;
    private static volatile CountDownLatch conductorConsultado;
    private static volatile CountDownLatch continuarConsulta;
    private static final AtomicInteger consultasComuna = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean private ObjectMapper json;
    @Autowired private AsignacionOperacionTransacciones operaciones;
    @Autowired private AsignacionIdempotenteService asignaciones;
    @Autowired private BarreraRecursoService barreras;
    @Autowired private CreacionIdempotenteService creaciones;
    @Autowired private CambioTrasladoIdempotenteService cambios;
    @MockitoSpyBean private ValidacionRecursosClient validacionRecursos;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> JWT_SECRET);
        registry.add("internal.api.secret", () -> INTERNAL_SECRET);
        registry.add("usuarios.service.uri",
                () -> "http://127.0.0.1:" + SESSIONS.getAddress().getPort());
        registry.add("vehiculos.service.uri",
                () -> "http://127.0.0.1:" + SESSIONS.getAddress().getPort());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:asignaciontest;DB_CLOSE_DELAY=-1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @AfterAll
    static void stopSessionService() {
        SESSIONS.stop(0);
    }

    @BeforeEach
    void seedStates() {
        jdbc.update("MERGE INTO estados_traslado (codigo_estado) KEY (codigo_estado) VALUES ('PENDIENTE')");
        jdbc.update("MERGE INTO estados_traslado (codigo_estado) KEY (codigo_estado) VALUES ('ASIGNADO')");
        jdbc.update("MERGE INTO estados_traslado (codigo_estado) KEY (codigo_estado) VALUES ('CANCELADO')");
        jdbc.update("MERGE INTO estados_traslado (codigo_estado) KEY (codigo_estado) VALUES ('EN_CURSO')");
        jdbc.update("MERGE INTO estados_traslado (codigo_estado) KEY (codigo_estado) VALUES ('FINALIZADO')");
    }

    @Test
    void activeAdministratorCreatesAndAssignsPendingTrip() throws Exception {
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");

        var creation = mvc.perform(post("/api/traslados")
                        .header("Authorization", authorization)
                        .header("Idempotency-Key", "crear-inicial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"origen":{"comunaId":1,"direccion":"Origen"},
                                 "destino":{"comunaId":2,"direccion":"Destino"},
                                 "fechaHoraViaje":"2026-12-01T10:00:00-03:00",
                                 "fechaHoraTerminoEstimado":"2026-12-01T11:00:00-03:00",
                                 "pasajeroIds":[101]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(creation).get("id").asLong();

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
    void creacionConservaRespuestaYDetectaCambioDeSolicitud() throws Exception {
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        String clave = UUID.randomUUID().toString();
        String body = creacionBody(1, 2);
        mvc.perform(post("/api/traslados").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        String original = mvc.perform(creacion(clave, authorization, body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(original).get("id").asLong();
        mvc.perform(post("/api/traslados/{id}/cancelar", id).header("Authorization", authorization)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Prueba\"}"))
                .andExpect(status().isOk());
        try {
            recursosCaidos = true;
            String repetida = mvc.perform(creacion(clave, authorization, body))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertEquals(original, repetida);
            mvc.perform(creacion(clave, authorization, creacionBody(1, 3)))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/traslados/operaciones/creacion").param("clave", clave)
                    .header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("COMPLETADA"))
                    .andExpect(jsonPath("$.resultado.estado").value("PENDIENTE"));
            mvc.perform(get("/api/traslados/operaciones/creacion").param("clave", clave)
                    .header("Authorization", "Bearer " + token("activa", "SOLICITANTE", 8)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
        } finally {
            recursosCaidos = false;
        }
    }

    @Test
    void creacionValidaAmbasComunasYNoRecuperaPendientesInciertas() throws Exception {
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(validacionRecursos).validarComunas(anyLong(), anyLong());
        for (String body : List.of(creacionBody(990001, 2), creacionBody(1, 990001))) {
            String clave = UUID.randomUUID().toString();
            String error = mvc.perform(creacion(clave, authorization, body)).andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertEquals(error, mvc.perform(creacion(clave, authorization, body)).andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString());
        }
        for (long comuna : List.of(990002L, 990003L, 990004L, 990005L)) {
            String clave = UUID.randomUUID().toString();
            String body = creacionBody(1, comuna);
            mvc.perform(creacion(clave, authorization, body)).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/traslados/operaciones/creacion").param("clave", clave)
                    .header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("PENDIENTE"));
            mvc.perform(creacion(clave, authorization, body)).andExpect(status().isServiceUnavailable());
        }
        String incierta = UUID.randomUUID().toString();
        try {
            recursosCaidos = true;
            mvc.perform(creacion(incierta, authorization, creacionBody(1, 2)))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            recursosCaidos = false;
        }
        int consultasAlReintentar = consultasComuna.get();
        mvc.perform(creacion(incierta, authorization, creacionBody(1, 2)))
                .andExpect(status().isServiceUnavailable());
        assertEquals(consultasAlReintentar, consultasComuna.get());
        String invalida = creacionBody(1, 2).replace("2026-12-01T11:00:00-03:00", "2026-12-01T09:00:00-03:00");
        String claveInvalida = UUID.randomUUID().toString();
        String rechazo = mvc.perform(creacion(claveInvalida, authorization, invalida)).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals(rechazo, mvc.perform(creacion(claveInvalida, authorization, invalida))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        int consultasAntes = consultasComuna.get();
        mvc.perform(creacion(UUID.randomUUID().toString(), authorization, creacionBody(1, 1)))
                .andExpect(status().isCreated());
        assertEquals(1, consultasComuna.get() - consultasAntes);
        assertThrows(IllegalTransactionStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> creaciones.crear("clave", 7L, "activa", null, null)));
    }

    @Test
    void creacionExpiradaConservaMarcaYOperacionesAntiguasNoSonVerificables() throws Exception {
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        String clave = UUID.randomUUID().toString();
        String body = creacionBody(1, 2);
        mvc.perform(creacion(clave, authorization, body)).andExpect(status().isCreated());
        OffsetDateTime vencimiento = jdbc.queryForObject(
                "SELECT expira_en FROM operaciones_traslados WHERE clave = ?", OffsetDateTime.class, clave);
        assertTrue(vencimiento.isAfter(OffsetDateTime.now().plusDays(29).plusHours(23)));
        assertTrue(vencimiento.isBefore(OffsetDateTime.now().plusDays(30).plusMinutes(1)));
        jdbc.update("UPDATE operaciones_traslados SET expira_en = ? WHERE clave = ?",
                OffsetDateTime.now().minusSeconds(1), clave);
        mvc.perform(get("/api/traslados/operaciones/creacion").param("clave", clave)
                .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
        mvc.perform(creacion(clave, authorization, body)).andExpect(status().isConflict());
        assertEquals("EXPIRADA", jdbc.queryForObject(
                "SELECT estado_operacion FROM operaciones_traslados WHERE clave = ?", String.class, clave));

        String antigua = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO operaciones_traslados (clave, actor_id, operacion, respuesta_codigo, creado_en) "
                + "VALUES (?, 7, 'crear', 201, ?)", antigua, OffsetDateTime.now());
        mvc.perform(creacion(antigua, authorization, body)).andExpect(status().isConflict());
    }

    @Test
    void creacionConcurrenteNoDuplicaTraslados() throws Exception {
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        String clave = UUID.randomUUID().toString();
        String body = creacionBody(1, 2);
        int antes = jdbc.queryForObject("SELECT COUNT(*) FROM traslados", Integer.class);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            var tareas = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 2; i++) {
                tareas.add(executor.submit(() -> {
                    inicio.await();
                    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/traslados"))
                            .header("Authorization", authorization).header("Idempotency-Key", clave)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            inicio.countDown();
            var primera = tareas.get(0).get(20, TimeUnit.SECONDS);
            var segunda = tareas.get(1).get(20, TimeUnit.SECONDS);
            assertTrue(List.of(201, 503).contains(primera.statusCode()));
            assertTrue(List.of(201, 503).contains(segunda.statusCode()));
            assertTrue(primera.statusCode() == 201 || segunda.statusCode() == 201);
            String resultado = primera.statusCode() == 201 ? primera.body() : segunda.body();
            assertEquals(resultado, mvc.perform(creacion(clave, authorization, body)).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString());
            assertEquals(antes + 1, jdbc.queryForObject("SELECT COUNT(*) FROM traslados", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private String creacionBody(long origen, long destino) {
        return """
                {"origen":{"comunaId":%d,"direccion":"Origen"},
                 "destino":{"comunaId":%d,"direccion":"Destino"},
                 "fechaHoraViaje":"2026-12-01T10:00:00-03:00",
                 "fechaHoraTerminoEstimado":"2026-12-01T11:00:00-03:00","pasajeroIds":[101]}
                """.formatted(origen, destino);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder creacion(
            String clave, String authorization, String body) {
        return post("/api/traslados").header("Authorization", authorization)
                .header("Idempotency-Key", clave).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void soloAdministradorYSolicitantePuedenCrearTraslados() throws Exception {
        String body = creacionBody(1, 2);
        mvc.perform(post("/api/traslados").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(creacion(UUID.randomUUID().toString(), "Bearer " + token("revocada", "SOLICITANTE"), body))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("CONDUCTOR", "OTRO")) {
            String clave = UUID.randomUUID().toString();
            mvc.perform(creacion(clave, "Bearer " + token("activa", role), body))
                    .andExpect(status().isForbidden());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM operaciones_traslados WHERE clave = ?", Integer.class, clave));
        }
        mvc.perform(creacion(UUID.randomUUID().toString(), "Bearer " + token("activa", "SOLICITANTE"), body))
                .andExpect(status().isCreated());
    }

    @Test
    void validaPasajerosEnLoteYConservaElResultado() throws Exception {
        String auth = "Bearer " + token("activa", "SOLICITANTE");
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(validacionRecursos).validarPasajeros(org.mockito.ArgumentMatchers.anySet());
        String clave = UUID.randomUUID().toString();
        String inexistente = creacionBody(1, 2).replace("[101]", "[101,990021]");
        String rechazo = mvc.perform(creacion(clave, auth, inexistente)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertEquals(rechazo, mvc.perform(creacion(clave, auth, inexistente)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());
        for (long id : List.of(990022L, 990023L, 990024L, 990025L)) {
            String pendiente = UUID.randomUUID().toString();
            String body = creacionBody(1, 2).replace("[101]", "[101," + id + "]");
            mvc.perform(creacion(pendiente, auth, body)).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/traslados/operaciones/creacion").param("clave", pendiente)
                    .header("Authorization", auth)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.estado").value("PENDIENTE"));
        }
        String cincuenta = java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
        mvc.perform(creacion(UUID.randomUUID().toString(), auth,
                creacionBody(1, 2).replace("101", cincuenta))).andExpect(status().isCreated());
        String claveExcesiva = UUID.randomUUID().toString();
        String demasiados = creacionBody(1, 2).replace("101", cincuenta + ",51");
        String error = mvc.perform(creacion(claveExcesiva, auth, demasiados))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertEquals(error, mvc.perform(creacion(claveExcesiva, auth, demasiados))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        jdbc.update("UPDATE operaciones_traslados SET respuesta_codigo = 201, respuesta_json = ? WHERE clave = ?",
                "{\"id\":999}", claveExcesiva);
        mvc.perform(creacion(claveExcesiva, auth, demasiados))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(999));
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
                    .header("Idempotency-Key", "http-error-" + role)
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

    @Test
    void distinguishesInvalidSessionFromUnavailableSessionServiceOverRealHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/traslados/999999999/asignar");
        for (String session : List.of("revocada", "error", "credencial", "conexion-cerrada")) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token(session, "ADMINISTRADOR"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"conductorId\":2,\"vehiculoId\":3}"))
                    .build();
            assertEquals(session.equals("revocada") ? 401 : 503,
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        }

    }

    @Test
    void enviaVencimientoVerificadoAServicioDeSesiones() throws Exception {
        String token = token("activa", "ADMINISTRADOR");
        var key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        long expira = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getExpiration().getTime();
        mvc.perform(put("/api/traslados/999999999/asignar")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "prueba-vencimiento")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conductorId\":2,\"vehiculoId\":3}")).andExpect(status().isNotFound());
        assertEquals("jwtExpiraEn=" + expira, ultimaConsultaSesion);
    }

    @Test
    void repeatsOriginalResponseAndRejectsChangedRequest() throws Exception {
        long id = createTrip();
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        var first = mvc.perform(assignment(id, "repeticion", authorization, 101, 201))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/traslados/{id}/cancelar", id)
                .header("Authorization", authorization).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Prueba\"}")).andExpect(status().isOk());
        var repeated = mvc.perform(assignment(id, "repeticion", authorization, 101, 201))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, repeated);
        mvc.perform(assignment(id, "repeticion", authorization, 102, 201))
                .andExpect(status().isConflict());
        mvc.perform(assignment(id, "repeticion",
                "Bearer " + token("activa", "ADMINISTRADOR", 8L), 101, 201))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/traslados/operaciones/asignacion").param("clave", "repeticion")
                .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("COMPLETADA"))
                .andExpect(jsonPath("$.resultado.estado").value("ASIGNADO"));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
    }

    @Test
    void pendingOperationCanBeRecoveredAndOnlyItsActorCanSeeIt() throws Exception {
        long id = createTrip();
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((id + ":103:203").getBytes(StandardCharsets.UTF_8)));
        operaciones.reservar("recuperar", 7L, "activa", id, hash, new AsignacionRequest(103L, 203L), null);
        mvc.perform(get("/api/traslados/operaciones/asignacion").param("clave", "recuperar")
                .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("PENDIENTE"));
        mvc.perform(post("/api/traslados/{id}/cancelar", id)
                .header("Authorization", authorization).header("Idempotency-Key", "recuperar")
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Prueba\"}"))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
        mvc.perform(get("/api/traslados/operaciones/asignacion").param("clave", "recuperar")
                .header("Authorization", "Bearer " + token("activa", "ADMINISTRADOR", 8L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
        mvc.perform(get("/api/traslados/operaciones/asignacion").param("clave", "desconocida")
                .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
        mvc.perform(assignment(id, "recuperar", authorization, 103, 203))
                .andExpect(status().isOk());
        mvc.perform(get("/api/traslados/operaciones/asignacion").param("clave", "recuperar")
                .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("COMPLETADA"));
    }

    @Test
    void pendingAssignmentKeepsOriginalSessionAndIntentUntilCompleted() throws Exception {
        long id = createTrip();
        String original = "Bearer " + token("activa", "ADMINISTRADOR");
        String otra = "Bearer " + token("simultanea", "ADMINISTRADOR", 7L, 120);
        try {
            recursosCaidos = true;
            mvc.perform(assignment(id, "asignacion-intencion", original, 880001, 880002))
                    .andExpect(status().isServiceUnavailable());
            var key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256")
                    .digest(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
            var vencimientoOriginal = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(original.substring(7)).getPayload().getExpiration().toInstant();
            assertEquals(vencimientoOriginal, jdbc.queryForObject(
                    "SELECT jwt_original_expira_en FROM operaciones_traslados WHERE clave = ?",
                    OffsetDateTime.class, "asignacion-intencion").toInstant());
            assertEquals("activa", jdbc.queryForObject(
                    "SELECT sesion_original FROM operaciones_traslados WHERE clave = ?", String.class, "asignacion-intencion"));
            assertEquals(880001L, jdbc.queryForObject(
                    "SELECT solicitud_conductor_id FROM operaciones_traslados WHERE clave = ?", Long.class, "asignacion-intencion"));
            assertEquals(880002L, jdbc.queryForObject(
                    "SELECT solicitud_vehiculo_id FROM operaciones_traslados WHERE clave = ?", Long.class, "asignacion-intencion"));
            mvc.perform(assignment(id, "asignacion-intencion", otra, 880001, 880002))
                    .andExpect(status().isServiceUnavailable());
            assertEquals("activa", jdbc.queryForObject(
                    "SELECT sesion_original FROM operaciones_traslados WHERE clave = ?", String.class, "asignacion-intencion"));
            assertEquals(vencimientoOriginal, jdbc.queryForObject(
                    "SELECT jwt_original_expira_en FROM operaciones_traslados WHERE clave = ?",
                    OffsetDateTime.class, "asignacion-intencion").toInstant());
            mvc.perform(get("/api/traslados/operaciones/asignacion").param("clave", "asignacion-intencion")
                    .header("Authorization", original)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                    .andExpect(jsonPath("$.sesionOriginal").doesNotExist())
                    .andExpect(jsonPath("$.jwtOriginalExpiraEn").doesNotExist())
                    .andExpect(jsonPath("$.solicitudConductorId").doesNotExist());
        } finally {
            recursosCaidos = false;
        }
        mvc.perform(assignment(id, "asignacion-intencion", otra, 880001, 880002))
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_traslados WHERE clave = ?", String.class, "asignacion-intencion"));
        assertNull(jdbc.queryForObject(
                "SELECT solicitud_conductor_id FROM operaciones_traslados WHERE clave = ?", Long.class, "asignacion-intencion"));
        assertNull(jdbc.queryForObject(
                "SELECT solicitud_vehiculo_id FROM operaciones_traslados WHERE clave = ?", Long.class, "asignacion-intencion"));
        assertNull(jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_traslados WHERE clave = ?",
                OffsetDateTime.class, "asignacion-intencion"));
    }

    @Test
    void tokenWithoutExpirationDoesNotInventADeadline() throws Exception {
        long id = createTrip();
        var key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256")
                .digest(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        String token = "Bearer " + Jwts.builder().subject("test@example.invalid").claim("usuarioId", 7L)
                .claim("sessionId", "activa").claim("roles", List.of("ADMINISTRADOR")).signWith(key).compact();
        try {
            recursosCaidos = true;
            mvc.perform(assignment(id, "asignacion-sin-exp", token, 880011, 880012))
                    .andExpect(status().isServiceUnavailable());
            assertNull(ultimaConsultaSesion);
            assertNull(jdbc.queryForObject(
                    "SELECT jwt_original_expira_en FROM operaciones_traslados WHERE clave = ?",
                    OffsetDateTime.class, "asignacion-sin-exp"));
        } finally {
            recursosCaidos = false;
        }
    }

    @Test
    void rolledBackProcessingLeavesPendingReservationForRetry() throws Exception {
        long id = createTrip();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((id + ":107:207").getBytes(StandardCharsets.UTF_8)));
        operaciones.reservar("rollback", 7L, "activa", id, hash, new AsignacionRequest(107L, 207L), null);
        operaciones.preparar("rollback", 7L, id, new AsignacionRequest(107L, 207L), hash);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    operaciones.procesar("rollback", 7L, id, new AsignacionRequest(107L, 207L), hash);
                    throw new IllegalStateException("Simulacion de fallo antes del commit");
                }));
        assertEquals("PENDIENTE", jdbc.queryForObject(
                "SELECT estado_operacion FROM operaciones_traslados WHERE clave = 'rollback'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
        mvc.perform(assignment(id, "rollback", "Bearer " + token("activa", "ADMINISTRADOR"), 107, 207))
                .andExpect(status().isOk());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
    }

    @Test
    void expiredResponseIsErasedButKeyCannotBeReused() throws Exception {
        long id = createTrip();
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        mvc.perform(assignment(id, "expirada", authorization, 104, 204)).andExpect(status().isOk());
        OffsetDateTime expiracion = jdbc.queryForObject(
                "SELECT expira_en FROM operaciones_traslados WHERE clave = 'expirada'", OffsetDateTime.class);
        assertTrue(expiracion.isAfter(OffsetDateTime.now().plusDays(30).minusSeconds(10)));
        assertTrue(expiracion.isBefore(OffsetDateTime.now().plusDays(30).plusSeconds(10)));
        jdbc.update("UPDATE operaciones_traslados SET expira_en = DATEADD('DAY', -1, CURRENT_TIMESTAMP) WHERE clave = 'expirada'");
        operaciones.limpiarRespuestasExpiradas();
        var row = jdbc.queryForMap("SELECT actor_id, traslado_id, solicitud_hash, respuesta_json, estado_operacion FROM operaciones_traslados WHERE clave = 'expirada'");
        assertNull(row.get("ACTOR_ID"));
        assertNull(row.get("TRASLADO_ID"));
        assertNull(row.get("SOLICITUD_HASH"));
        assertNull(row.get("RESPUESTA_JSON"));
        assertEquals("EXPIRADA", row.get("ESTADO_OPERACION"));
        mvc.perform(assignment(id, "expirada", authorization, 104, 204))
                .andExpect(status().isConflict());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
    }

    @Test
    void requiresKeyAndPreservesDefinitiveError() throws Exception {
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        mvc.perform(put("/api/traslados/999999999/asignar").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content("{\"conductorId\":2,\"vehiculoId\":3}"))
                .andExpect(status().isBadRequest());
        mvc.perform(assignment(999999999L, "x".repeat(101), authorization, 2, 3))
                .andExpect(status().isBadRequest());
        var first = mvc.perform(assignment(999999999L, "no-existe", authorization, 2, 3))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var repeated = mvc.perform(assignment(999999999L, "no-existe", authorization, 2, 3))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(first, repeated);
    }

    @Test
    void rejectedAssignmentLeavesNoPartialResourceAndReplaysConflict() throws Exception {
        long anterior = createTrip();
        long id = createTrip();
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        mvc.perform(assignment(anterior, "anterior", authorization, 106, 206)).andExpect(status().isOk());
        var first = mvc.perform(assignment(id, "rechazada", authorization, 106, 206))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
        mvc.perform(post("/api/traslados/{id}/cancelar", anterior).header("Authorization", authorization)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Prueba\"}"))
                .andExpect(status().isOk());
        var repeat = mvc.perform(assignment(id, "rechazada", authorization, 106, 206))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertEquals(first, repeat);
        mvc.perform(assignment(id, "nueva", authorization, 106, 206)).andExpect(status().isOk());
    }

    @Test
    void enforcesOneMinuteSeparationAndAllowsNonOverlappingBookings() throws Exception {
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        long first = createTrip();
        long tooClose = createTrip("2026-12-01T11:00:59-03:00", "2026-12-01T12:00:00-03:00");
        long separated = createTrip("2026-12-01T11:01:00-03:00", "2026-12-01T12:00:00-03:00");
        long driverConflict = createTrip();
        long vehicleConflict = createTrip();
        mvc.perform(assignment(first, "separacion-a", authorization, 109, 209)).andExpect(status().isOk());
        mvc.perform(assignment(driverConflict, "conductor-ocupado", authorization, 109, 999))
                .andExpect(status().isConflict());
        mvc.perform(assignment(vehicleConflict, "vehiculo-ocupado", authorization, 999, 209))
                .andExpect(status().isConflict());
        mvc.perform(assignment(tooClose, "separacion-b", authorization, 109, 209)).andExpect(status().isConflict());
        mvc.perform(assignment(separated, "separacion-c", authorization, 109, 209)).andExpect(status().isOk());
    }

    @Test
    void runningTripBlocksStartAndReassignmentKeepsRunningState() throws Exception {
        String admin = "Bearer " + token("activa", "ADMINISTRADOR");
        String conductor = "Bearer " + token("activa", "CONDUCTOR", 110L);
        long first = createTrip();
        long second = createTrip("2026-12-01T12:00:00-03:00", "2026-12-01T13:00:00-03:00");
        mvc.perform(assignment(first, "inicio-a", admin, 110, 210)).andExpect(status().isOk());
        mvc.perform(assignment(second, "inicio-b", admin, 110, 210)).andExpect(status().isOk());
        mvc.perform(patch("/api/traslados/{id}/estado", first).header("Authorization", conductor)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"EN_CURSO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_CURSO"));
        mvc.perform(patch("/api/traslados/{id}/estado", second).header("Authorization", conductor)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"EN_CURSO\"}"))
                .andExpect(status().isConflict());
        mvc.perform(assignment(first, "reasignar-en-curso", admin, 111, 211))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_CURSO"));
        mvc.perform(post("/api/traslados/{id}/cancelar", first).header("Authorization", conductor)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Prueba\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/traslados/{id}/estado", second).header("Authorization", conductor)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"EN_CURSO\"}"))
                .andExpect(status().isOk());
        mvc.perform(assignment(first, "reasignar-a-ocupado", admin, 110, 211))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentDifferentKeysCannotBookSameResourcesAtSameTime() throws Exception {
        long first = createTrip();
        long second = createTrip();
        String authorization = "Bearer " + token("simultanea", "ADMINISTRADOR");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest requestOne = httpAssignment(first, "carrera-a", authorization, 108, 208);
        HttpRequest requestTwo = httpAssignment(second, "carrera-b", authorization, 108, 208);
        simultaneousSessions = new CountDownLatch(2);
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT id_estado FROM estados_traslado WHERE codigo_estado = 'PENDIENTE' FOR UPDATE").close();
            var one = client.sendAsync(requestOne, HttpResponse.BodyHandlers.ofString());
            var two = client.sendAsync(requestTwo, HttpResponse.BodyHandlers.ofString());
            assertTrue(simultaneousSessions.await(10, TimeUnit.SECONDS));
            connection.rollback();
            int oneStatus = one.get(10, TimeUnit.SECONDS).statusCode();
            int twoStatus = two.get(10, TimeUnit.SECONDS).statusCode();
            List<Integer> statuses = List.of(oneStatus, twoStatus).stream().sorted().toList();
            assertTrue(statuses.equals(List.of(200, 409)) || statuses.equals(List.of(200, 503)));
            if (oneStatus == 503) {
                assertEquals(409, client.send(requestOne, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            if (twoStatus == 503) {
                assertEquals(409, client.send(requestTwo, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        } finally {
            simultaneousSessions = null;
        }
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id IN (?, ?)", Integer.class, first, second));
    }

    @Test
    void simultaneousRequestsWithSameKeyHaveOneEffect() throws Exception {
        long id = createTrip();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((id + ":105:205").getBytes(StandardCharsets.UTF_8)));
        operaciones.reservar("simultanea", 7L, "simultanea", id, hash, new AsignacionRequest(105L, 205L), null);
        String authorization = "Bearer " + token("simultanea", "ADMINISTRADOR");
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/traslados/" + id + "/asignar"))
                .header("Authorization", authorization).header("Idempotency-Key", "simultanea")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"conductorId\":105,\"vehiculoId\":205}"))
                .build();
        HttpClient client = HttpClient.newHttpClient();
        simultaneousSessions = new CountDownLatch(2);
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT clave FROM operaciones_traslados WHERE clave = 'simultanea' FOR UPDATE").close();
            var one = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            var two = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(simultaneousSessions.await(10, TimeUnit.SECONDS));
            mvc.perform(get("/api/traslados/operaciones/asignacion").param("clave", "simultanea")
                    .header("Authorization", "Bearer " + token("activa", "ADMINISTRADOR")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("PENDIENTE"));
            connection.rollback();
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            assertEquals(200, first.statusCode());
            assertEquals(200, second.statusCode());
            assertEquals(first.body(), second.body());
        } finally {
            simultaneousSessions = null;
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
    }

    @Test
    void resourceBarrierSurvivesRetryAndDoesNotSaveTemporaryAssignmentFailure() throws Exception {
        long id = createTrip();
        String operation = UUID.randomUUID().toString();
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        assertEquals("ABIERTA", barreras.prepararBaja(operation, "CONDUCTOR", 850001L));
        assertEquals("ABIERTA", barreras.prepararBaja(operation, "CONDUCTOR", 850001L));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> barreras.prepararBaja(operation, "CONDUCTOR", 850002L)).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> barreras.prepararBaja(UUID.randomUUID().toString(), "CONDUCTOR", 850001L))
                .getStatusCode().value());

        mvc.perform(assignment(id, "barrera-pendiente", authorization, 850001, 850003))
                .andExpect(status().isServiceUnavailable());
        assertEquals("PENDIENTE", operaciones.consultar("barrera-pendiente", 7L).estado());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    barreras.cerrar(operation, "CONDUCTOR", 850001L);
                    throw new IllegalStateException("Simulacion de fallo antes del commit");
                }));
        assertEquals("ABIERTA", barreras.prepararBaja(operation, "CONDUCTOR", 850001L));
        barreras.cerrar(operation, "CONDUCTOR", 850001L);
        barreras.cerrar(operation, "CONDUCTOR", 850001L);
        assertEquals("CERRADA", barreras.prepararBaja(operation, "CONDUCTOR", 850001L));
        mvc.perform(assignment(id, "barrera-pendiente", authorization, 850001, 850003))
                .andExpect(status().isOk());
        assertEquals("RECHAZADA", barreras.prepararBaja(UUID.randomUUID().toString(), "CONDUCTOR", 850001L));
        assertEquals("RECHAZADA", barreras.prepararBaja(UUID.randomUUID().toString(), "VEHICULO", 850003L));
    }

    @Test
    void resourceBarrierRejectsInvalidOrUnknownOperations() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> barreras.prepararBaja("invalido", "CONDUCTOR", 1L)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> barreras.prepararBaja(UUID.randomUUID().toString(), null, 1L))
                .getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> barreras.cerrar(UUID.randomUUID().toString(), "VEHICULO", 1L))
                .getStatusCode().value());
    }

    @Test
    void internalBarrierRequiresServiceCredentialAndPreservesOperationState() throws Exception {
        String operation = UUID.randomUUID().toString();
        String path = "/internal/barreras/" + operation;
        String body = "{\"tipo\":\"CONDUCTOR\",\"recursoId\":860001}";
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).header("X-Belmonte-Internal-Secret", "incorrecta")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).header("Authorization", "Bearer " + token("activa", "ADMINISTRADOR"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM barreras_recurso WHERE operacion_id = ?",
                Integer.class, operation));

        for (int i = 0; i < 2; i++) {
            mvc.perform(put(path).header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ABIERTA"));
        }
        mvc.perform(put(path).header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"CONDUCTOR\",\"recursoId\":860002}"))
                .andExpect(status().isConflict());
        mvc.perform(post(path + "/cerrar").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path + "/cerrar").header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"VEHICULO\",\"recursoId\":860001}"))
                .andExpect(status().isConflict());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(path + "/cerrar").header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(put(path).header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CERRADA"));
        mvc.perform(put("/internal/barreras/invalida").header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void internalBarrierRejectsResourceWithActiveTrip() throws Exception {
        long id = createTrip();
        mvc.perform(assignment(id, "barrera-http-asignada",
                "Bearer " + token("activa", "ADMINISTRADOR"), 860010, 860011))
                .andExpect(status().isOk());
        String operation = UUID.randomUUID().toString();
        mvc.perform(put("/internal/barreras/" + operation)
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"VEHICULO\",\"recursoId\":860011}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.estado").value("RECHAZADA"));
        mvc.perform(put("/internal/barreras/" + operation)
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"VEHICULO\",\"recursoId\":860012}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.estado").doesNotExist());
        mvc.perform(put("/internal/barreras/" + operation)
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"VEHICULO\",\"recursoId\":860011,\"proposito\":\"CAMBIO_COMPATIBLE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.estado").doesNotExist());
        mvc.perform(post("/api/traslados/{id}/cancelar", id)
                .header("Authorization", "Bearer " + token("activa", "ADMINISTRADOR"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Prueba\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/internal/barreras/" + operation)
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"VEHICULO\",\"recursoId\":860011}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.estado").value("RECHAZADA"));
        mvc.perform(post("/internal/barreras/" + operation + "/cerrar")
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"VEHICULO\",\"recursoId\":860011}"))
                .andExpect(status().isConflict());
        assertEquals("ABIERTA", barreras.prepararBaja(UUID.randomUUID().toString(), "VEHICULO", 860011L));
    }

    @Test
    void compatibleChangeBarrierAllowsExistingTripsButBlocksNewAssignments() throws Exception {
        String authorization = "Bearer " + token("activa", "ADMINISTRADOR");
        long assigned = createTrip();
        mvc.perform(assignment(assigned, "compatible-viaje", authorization, 860030, 860031))
                .andExpect(status().isOk());
        long pending = createTrip();
        String path = "/internal/barreras/" + UUID.randomUUID();
        String compatible = "{\"tipo\":\"VEHICULO\",\"recursoId\":860031,\"proposito\":\"CAMBIO_COMPATIBLE\"}";
        String baja = "{\"tipo\":\"VEHICULO\",\"recursoId\":860031,\"proposito\":\"BAJA\"}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(put(path).header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                    .contentType(MediaType.APPLICATION_JSON).content(compatible))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ABIERTA"));
        }
        mvc.perform(put("/internal/barreras/" + UUID.randomUUID())
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(baja))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(put(path).header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(baja))
                .andExpect(status().isConflict());
        mvc.perform(post(path + "/cerrar").header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(baja))
                .andExpect(status().isConflict());
        mvc.perform(assignment(pending, "compatible-bloqueado", authorization, 860032, 860031))
                .andExpect(status().isServiceUnavailable());
        assertEquals("PENDIENTE", operaciones.consultar("compatible-bloqueado", 7L).estado());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(path + "/cerrar").header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                    .contentType(MediaType.APPLICATION_JSON).content(compatible))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(put(path).header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(compatible))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CERRADA"));
        mvc.perform(put("/internal/barreras/" + UUID.randomUUID())
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"VEHICULO\",\"recursoId\":860031,\"proposito\":\"ASIGNACION\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(assignment(pending, "compatible-bloqueado", authorization, 860032, 860031))
                .andExpect(status().isConflict());
    }

    @Test
    void internalBarrierErrorsAndRetryWorkOverRealHttp() throws Exception {
        String operation = UUID.randomUUID().toString();
        URI uri = URI.create("http://127.0.0.1:" + port + "/internal/barreras/" + operation);
        String body = "{\"tipo\":\"VEHICULO\",\"recursoId\":860020}";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest unauthorized = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403, client.send(unauthorized, HttpResponse.BodyHandlers.discarding()).statusCode());
        HttpRequest prepare = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        for (int i = 0; i < 2; i++) {
            var response = client.send(prepare, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ABIERTA", json.readTree(response.body()).get("estado").asString());
        }
        HttpRequest close = HttpRequest.newBuilder(URI.create(uri + "/cerrar"))
                .header("Content-Type", "application/json")
                .header("X-Belmonte-Internal-Secret", INTERNAL_SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(204, client.send(close, HttpResponse.BodyHandlers.discarding()).statusCode());
        var after = client.send(prepare, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, after.statusCode());
        assertEquals("CERRADA", json.readTree(after.body()).get("estado").asString());
    }

    @Test
    void assignmentAndBarrierCannotBothWinTheRace() throws Exception {
        long id = createTrip();
        String operation = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var assignment = executor.submit(() -> {
                start.await();
                try {
                    return asignaciones.asignar("carrera-barrera", 7L, "activa", id,
                            new AsignacionRequest(850010L, 850011L), null).codigo();
                } catch (ResponseStatusException exception) {
                    return exception.getStatusCode().value();
                }
            });
            var barrier = executor.submit(() -> {
                start.await();
                try {
                    return "RECHAZADA".equals(barreras.prepararBaja(operation, "VEHICULO", 850011L)) ? 409 : 200;
                } catch (ResponseStatusException exception) {
                    return exception.getStatusCode().value();
                }
            });
            start.countDown();
            int assignmentStatus = assignment.get(20, TimeUnit.SECONDS);
            int barrierStatus = barrier.get(20, TimeUnit.SECONDS);
            assertTrue((assignmentStatus == 200 && (barrierStatus == 409 || barrierStatus == 503))
                    || (assignmentStatus == 503 && barrierStatus == 200),
                    "assignment=" + assignmentStatus + ", barrier=" + barrierStatus);
            if (barrierStatus == 200) {
                barreras.cerrar(operation, "VEHICULO", 850011L);
                assertEquals(200, asignaciones.asignar("carrera-barrera", 7L, "activa", id,
                        new AsignacionRequest(850010L, 850011L), null).codigo());
            }
            if (barrierStatus == 503) {
                assertEquals("RECHAZADA", barreras.prepararBaja(operation, "VEHICULO", 850011L));
            }
        }
    }

    @Test
    void preparedAssignmentKeepsBothResourcesUntilRecoveryAndRollsBackPartialReservation() throws Exception {
        long id = createTrip();
        var request = new AsignacionRequest(850020L, 850021L);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((id + ":850020:850021").getBytes(StandardCharsets.UTF_8)));
        String other = UUID.randomUUID().toString();
        barreras.prepararBaja(other, "VEHICULO", 850021L);
        operaciones.reservar("reserva-recuperable", 7L, "activa", id, hash, request, null);
        assertEquals(503, assertThrows(ResponseStatusException.class, () ->
                operaciones.preparar("reserva-recuperable", 7L, id, request, hash))
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM barreras_recurso WHERE tipo_recurso = 'CONDUCTOR' AND recurso_id = ?",
                Integer.class, 850020L));
        barreras.cerrar(other, "VEHICULO", 850021L);
        assertNull(operaciones.preparar("reserva-recuperable", 7L, id, request, hash).resultado());
        assertNull(operaciones.preparar("reserva-recuperable", 7L, id, request, hash).resultado());
        for (String tipo : List.of("CONDUCTOR", "VEHICULO")) {
            long recurso = tipo.equals("CONDUCTOR") ? 850020L : 850021L;
            assertEquals(503, assertThrows(ResponseStatusException.class, () ->
                    barreras.prepararBaja(UUID.randomUUID().toString(), tipo, recurso))
                    .getStatusCode().value());
        }
        assertEquals(200, asignaciones.asignar("reserva-recuperable", 7L, "activa", id, request, null).codigo());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM barreras_recurso WHERE proposito = 'ASIGNACION' AND recurso_id IN (?, ?)",
                Integer.class, 850020L, 850021L));
    }

    @Test
    void refreshesOperationWhenAnotherRequestCompletesBetweenTransactions() throws Exception {
        long id = createTrip();
        var request = new AsignacionRequest(850030L, 850031L);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((id + ":850030:850031").getBytes(StandardCharsets.UTF_8)));
        operaciones.reservar("estado-obsoleto", 7L, "activa", id, hash, request, null);
        var entityManager = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(entityManager));
        try {
            assertNull(operaciones.preparar("estado-obsoleto", 7L, id, request, hash).resultado());
            try (var executor = Executors.newSingleThreadExecutor()) {
                var first = executor.submit(() -> asignaciones.asignar("estado-obsoleto", 7L, "activa", id, request, null))
                        .get(20, TimeUnit.SECONDS);
                assertEquals(first, operaciones.procesar("estado-obsoleto", 7L, id, request, hash));
            }
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            entityManager.close();
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
    }

    @Test
    void validatesRemoteResourcesAndPreservesDefinitiveRejections() throws Exception {
        String auth = "Bearer " + token("activa", "ADMINISTRADOR");
        int[][] cases = {
                {990001, 870001, 404}, {990002, 870002, 409}, {990003, 870003, 409},
                {870004, 990011, 404}, {870005, 990012, 409}, {870006, 990013, 409}
        };
        for (int[] test : cases) {
            long id = createTrip("2026-12-01T10:00:00-03:00", "2026-12-01T11:00:00-03:00", "101,102");
            String key = "rechazo-remoto-" + test[0] + "-" + test[1];
            var first = mvc.perform(assignment(id, key, auth, test[0], test[1]))
                    .andExpect(status().is(test[2])).andReturn().getResponse().getContentAsString();
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
            try {
                recursosCaidos = true;
                var repeat = mvc.perform(assignment(id, key, auth, test[0], test[1]))
                        .andExpect(status().is(test[2])).andReturn().getResponse().getContentAsString();
                assertEquals(first, repeat);
            } finally {
                recursosCaidos = false;
            }
        }
    }

    @Test
    void dependencyFailureLeavesReservationsPendingForSameKeyRecovery() throws Exception {
        String auth = "Bearer " + token("activa", "ADMINISTRADOR");
        long id = createTrip();
        try {
            recursosCaidos = true;
            mvc.perform(assignment(id, "dependencia-caida", auth, 870020, 870021))
                    .andExpect(status().isServiceUnavailable());
            assertEquals("PENDIENTE", operaciones.consultar("dependencia-caida", 7L).estado());
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM barreras_recurso WHERE estado = 'ABIERTA' AND recurso_id IN (?, ?)",
                    Integer.class, 870020L, 870021L));
        } finally {
            recursosCaidos = false;
        }
        var first = mvc.perform(assignment(id, "dependencia-caida", auth, 870020, 870021))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        try {
            recursosCaidos = true;
            var repeat = mvc.perform(assignment(id, "dependencia-caida", auth, 870020, 870021))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(first, repeat);
        } finally {
            recursosCaidos = false;
        }
    }

    @Test
    void treatsBadInternalResponsesAsDependencyFailures() throws Exception {
        String auth = "Bearer " + token("activa", "ADMINISTRADOR");
        int[][] cases = {{990004, 870030}, {990005, 870031}, {870032, 990014}, {870033, 990015}};
        for (int[] test : cases) {
            long id = createTrip();
            String key = "interna-invalida-" + test[0] + "-" + test[1];
            mvc.perform(assignment(id, key, auth, test[0], test[1]))
                    .andExpect(status().isServiceUnavailable());
            assertEquals("PENDIENTE", operaciones.consultar(key, 7L).estado());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
        }
    }

    @Test
    void remoteValidationKeepsReservationsWithoutHoldingSqlLock() throws Exception {
        long id = createTrip();
        conductorConsultado = new CountDownLatch(1);
        continuarConsulta = new CountDownLatch(1);
        try {
            var request = httpAssignment(id, "consulta-bloqueada",
                    "Bearer " + token("activa", "ADMINISTRADOR"), 990006, 870040);
            var future = HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(conductorConsultado.await(5, TimeUnit.SECONDS));
            assertEquals(503, assertThrows(ResponseStatusException.class, () ->
                    barreras.prepararBaja(UUID.randomUUID().toString(), "VEHICULO", 870040L))
                    .getStatusCode().value());
            continuarConsulta.countDown();
            assertEquals(200, future.get(10, TimeUnit.SECONDS).statusCode());
        } finally {
            continuarConsulta.countDown();
            conductorConsultado = null;
            continuarConsulta = null;
        }
    }

    @Test
    void remoteTimeoutKeepsPendingOperationRecoverable() throws Exception {
        long id = createTrip();
        conductorConsultado = new CountDownLatch(1);
        continuarConsulta = new CountDownLatch(1);
        try {
            var request = httpAssignment(id, "timeout-recurso",
                    "Bearer " + token("activa", "ADMINISTRADOR"), 990007, 870041);
            var future = HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(conductorConsultado.await(5, TimeUnit.SECONDS));
            assertEquals(503, future.get(8, TimeUnit.SECONDS).statusCode());
            assertEquals("PENDIENTE", operaciones.consultar("timeout-recurso", 7L).estado());
        } finally {
            continuarConsulta.countDown();
            conductorConsultado = null;
            continuarConsulta = null;
        }
        mvc.perform(assignment(id, "timeout-recurso",
                "Bearer " + token("activa", "ADMINISTRADOR"), 990007, 870041)).andExpect(status().isOk());
    }

    @Test
    void assignmentCannotStartInsideAnExistingSqlTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> asignaciones.asignar(
                        "dentro-de-transaccion", 7L, "activa", 1L, new AsignacionRequest(1L, 2L), null)));
    }

    @Test
    void cancelacionConservaResultadoIntencionPermisosEHistorial() throws Exception {
        long id = createTrip();
        long otro = createTrip();
        String admin = "Bearer " + token("activa", "ADMINISTRADOR");
        String ajeno = "Bearer " + token("activa", "SOLICITANTE", 8L);
        String clave = UUID.randomUUID().toString();
        mvc.perform(post("/api/traslados/{id}/cancelar", id).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Prueba\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(cancelacion(id, UUID.randomUUID().toString(), admin, "ñ".repeat(251)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/traslados/operaciones/cancelacion").param("clave", clave)
                .header("Authorization", "Bearer " + token("revocada", "ADMINISTRADOR")))
                .andExpect(status().isUnauthorized());
        mvc.perform(cancelacion(id, UUID.randomUUID().toString(), ajeno, "Prueba"))
                .andExpect(status().isForbidden());
        String original = mvc.perform(cancelacion(id, clave, admin, "Prueba"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADO"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(original, mvc.perform(cancelacion(id, clave, admin, "Prueba"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(cancelacion(id, clave, admin, "Otro motivo")).andExpect(status().isConflict());
        mvc.perform(cancelacion(otro, clave, admin, "Prueba")).andExpect(status().isConflict());
        mvc.perform(cancelacion(id, clave, ajeno, "Prueba")).andExpect(status().isConflict());
        mvc.perform(get("/api/traslados/operaciones/cancelacion").param("clave", clave)
                .header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COMPLETADA"))
                .andExpect(jsonPath("$.resultado.estado").value("CANCELADO"));
        mvc.perform(get("/api/traslados/operaciones/cancelacion").param("clave", clave)
                .header("Authorization", ajeno)).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
        mvc.perform(get("/api/traslados/operaciones/estado").param("clave", clave)
                .header("Authorization", admin)).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?",
                Integer.class, id));
        assertEquals("Prueba", jdbc.queryForObject(
                "SELECT motivo FROM historial_estados WHERE traslado_id = ? AND motivo IS NOT NULL", String.class, id));
        assertEquals(7L, jdbc.queryForObject(
                "SELECT usuario_id FROM historial_estados WHERE traslado_id = ? AND motivo IS NOT NULL", Long.class, id));
        jdbc.update("UPDATE operaciones_traslados SET expira_en = ? WHERE clave = ?",
                OffsetDateTime.now().minusDays(1), clave);
        mvc.perform(cancelacion(id, clave, admin, "Prueba")).andExpect(status().isConflict());
        mvc.perform(get("/api/traslados/operaciones/cancelacion").param("clave", clave)
                .header("Authorization", admin)).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
    }

    @Test
    void estadoSoloAdmiteTransicionesDeConductorYConservaExitosYRechazos() throws Exception {
        long id = createTrip();
        String admin = "Bearer " + token("activa", "ADMINISTRADOR");
        String conductor = "Bearer " + token("activa", "CONDUCTOR", 390001L);
        String otro = "Bearer " + token("activa", "CONDUCTOR", 390002L);
        mvc.perform(assignment(id, UUID.randomUUID().toString(), admin, 390001, 390003))
                .andExpect(status().isOk());
        mvc.perform(estado(id, UUID.randomUUID().toString(), admin, "EN_CURSO", null))
                .andExpect(status().isForbidden());
        mvc.perform(estado(id, UUID.randomUUID().toString(), otro, "EN_CURSO", null))
                .andExpect(status().isForbidden());
        mvc.perform(estado(id, UUID.randomUUID().toString(), conductor, "CANCELADO", null))
                .andExpect(status().isConflict());
        String rechazo = UUID.randomUUID().toString();
        String error = mvc.perform(estado(id, rechazo, conductor, "FINALIZADO", null))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        String inicio = UUID.randomUUID().toString();
        String original = mvc.perform(estado(id, inicio, conductor, "EN_CURSO", "Inicio"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(error, mvc.perform(estado(id, rechazo, conductor, "FINALIZADO", null))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
        mvc.perform(estado(id, inicio, conductor, "EN_CURSO", "distinto")).andExpect(status().isConflict());
        mvc.perform(estado(id, UUID.randomUUID().toString(), conductor, "FINALIZADO", null))
                .andExpect(status().isOk());
        assertEquals(original, mvc.perform(estado(id, inicio, conductor, "EN_CURSO", "Inicio"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/traslados/operaciones/estado").param("clave", inicio)
                .header("Authorization", conductor)).andExpect(jsonPath("$.resultado.estado").value("EN_CURSO"));
        mvc.perform(cancelacion(id, UUID.randomUUID().toString(), admin, "Tarde"))
                .andExpect(status().isConflict());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?",
                Integer.class, id));
    }

    @Test
    void permisosDeCancelacionYClavesAntiguas() throws Exception {
        String admin = "Bearer " + token("activa", "ADMINISTRADOR");
        String creador = "Bearer " + token("activa", "SOLICITANTE", 7L);
        String conductor = "Bearer " + token("activa", "CONDUCTOR", 390021L);
        long pendiente = createTrip();
        mvc.perform(cancelacion(pendiente, UUID.randomUUID().toString(), creador, ""))
                .andExpect(status().isBadRequest());
        mvc.perform(cancelacion(pendiente, UUID.randomUUID().toString(), conductor, "Prueba"))
                .andExpect(status().isForbidden());
        mvc.perform(cancelacion(pendiente, "x".repeat(101), creador, "Prueba"))
                .andExpect(status().isBadRequest());
        mvc.perform(cancelacion(pendiente, UUID.randomUUID().toString(), creador, "Prueba"))
                .andExpect(status().isOk());

        long asignado = createTrip();
        mvc.perform(assignment(asignado, UUID.randomUUID().toString(), admin, 390021, 390022))
                .andExpect(status().isOk());
        mvc.perform(cancelacion(asignado, UUID.randomUUID().toString(), conductor, "Prueba"))
                .andExpect(status().isOk());
        String anterior = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO operaciones_traslados
                (clave, actor_id, operacion, traslado_id, respuesta_codigo, creado_en)
                VALUES (?, 7, 'cancelar', ?, 200, ?)
                """, anterior, asignado, OffsetDateTime.now());
        mvc.perform(cancelacion(asignado, anterior, admin, "Prueba")).andExpect(status().isConflict());
    }

    @Test
    void cancelacionConcurrenteConMismaClaveSoloRegistraUnaVezYFalloRevierte() throws Exception {
        long id = createTrip();
        String clave = UUID.randomUUID().toString();
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                inicio.await();
                return cambios.cancelar(clave, 7L, id, new CancelacionRequest("Prueba"), true, false, false);
            });
            var b = executor.submit(() -> {
                inicio.await();
                return cambios.cancelar(clave, 7L, id, new CancelacionRequest("Prueba"), true, false, false);
            });
            inicio.countDown();
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?",
                Integer.class, id));
        long otro = createTrip();
        String fallida = UUID.randomUUID().toString();
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof TrasladoResponse) {
                throw new IllegalStateException("Fallo simulado de serializacion");
            }
            return invocation.callRealMethod();
        }).when(json).writeValueAsString(any());
        assertThrows(IllegalStateException.class, () -> cambios.cancelar(fallida, 7L, otro,
                new CancelacionRequest("Prueba"), true, false, false));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?",
                Integer.class, otro));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operaciones_traslados WHERE clave = ?",
                Integer.class, fallida));
    }

    @Test
    void consultaNoDisponibleNoDescartaSolicitudEnTransito() throws Exception {
        long id = createTrip();
        String clave = UUID.randomUUID().toString();
        String authorization = "Bearer " + token("simultanea", "ADMINISTRADOR");
        var bloqueado = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        simultaneousSessions = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var bloqueo = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id_estado FROM estados_traslado WHERE codigo_estado = 'PENDIENTE' FOR UPDATE",
                        Long.class);
                bloqueado.countDown();
                try {
                    if (!liberar.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Espera agotada");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertTrue(bloqueado.await(10, TimeUnit.SECONDS));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/traslados/" + id + "/cancelar"))
                    .header("Authorization", authorization).header("Idempotency-Key", clave)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"motivo\":\"Prueba\"}")).build();
            var respuesta = HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(simultaneousSessions.await(10, TimeUnit.SECONDS));
            mvc.perform(get("/api/traslados/operaciones/cancelacion").param("clave", clave)
                    .header("Authorization", authorization)).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
            liberar.countDown();
            bloqueo.get(10, TimeUnit.SECONDS);
            assertEquals(200, respuesta.get(10, TimeUnit.SECONDS).statusCode());
            mvc.perform(get("/api/traslados/operaciones/cancelacion").param("clave", clave)
                    .header("Authorization", authorization)).andExpect(jsonPath("$.estado").value("COMPLETADA"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?",
                    Integer.class, id));
        } finally {
            liberar.countDown();
            simultaneousSessions = null;
        }
    }

    @Test
    void inicioYCancelacionConcurrentesConservanUnHistorialValido() throws Exception {
        long id = createTrip();
        String admin = "Bearer " + token("activa", "ADMINISTRADOR");
        mvc.perform(assignment(id, UUID.randomUUID().toString(), admin, 390011, 390012))
                .andExpect(status().isOk());
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                inicio.await();
                return cambios.cambiarEstado(UUID.randomUUID().toString(), 390011L, id,
                        new EstadoRequest("EN_CURSO", null)).codigo();
            });
            var b = executor.submit(() -> {
                inicio.await();
                return cambios.cancelar(UUID.randomUUID().toString(), 7L, id,
                        new CancelacionRequest("Prueba"), true, false, false).codigo();
            });
            inicio.countDown();
            int estado = a.get(20, TimeUnit.SECONDS);
            assertTrue(estado == 200 || estado == 409);
            assertEquals(200, b.get(20, TimeUnit.SECONDS));
            assertEquals(estado == 200 ? 4 : 3, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, id));
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cancelacion(
            long id, String clave, String authorization, String motivo) {
        return post("/api/traslados/{id}/cancelar", id).header("Authorization", authorization)
                .header("Idempotency-Key", clave).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new CancelacionRequest(motivo)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder estado(
            long id, String clave, String authorization, String estado, String motivo) {
        return patch("/api/traslados/{id}/estado", id).header("Authorization", authorization)
                .header("Idempotency-Key", clave).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new EstadoRequest(estado, motivo)));
    }

    private long createTrip() throws Exception {
        return createTrip("2026-12-01T10:00:00-03:00", "2026-12-01T11:00:00-03:00");
    }

    private long createTrip(String inicio, String fin) throws Exception {
        return createTrip(inicio, fin, "101");
    }

    private long createTrip(String inicio, String fin, String pasajeros) throws Exception {
        String response = mvc.perform(post("/api/traslados")
                        .header("Authorization", "Bearer " + token("activa", "ADMINISTRADOR"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"origen":{"comunaId":1,"direccion":"Origen"},
                                 "destino":{"comunaId":2,"direccion":"Destino"},
                                 "fechaHoraViaje":"%s",
                                 "fechaHoraTerminoEstimado":"%s",
                                 "pasajeroIds":[%s]}
                                """.formatted(inicio, fin, pasajeros)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private HttpRequest httpAssignment(long id, String key, String authorization, int conductor, int vehiculo) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/traslados/" + id + "/asignar"))
                .header("Authorization", authorization).header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"conductorId\":" + conductor + ",\"vehiculoId\":" + vehiculo + "}"))
                .build();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder assignment(
            long id, String key, String authorization, int conductor, int vehiculo) {
        return put("/api/traslados/{id}/asignar", id)
                .header("Authorization", authorization).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conductorId\":" + conductor + ",\"vehiculoId\":" + vehiculo + "}");
    }

    private static String token(String sessionId, String role) throws Exception {
        return token(sessionId, role, 7L);
    }

    private static String token(String sessionId, String role, long usuarioId) throws Exception {
        return token(sessionId, role, usuarioId, 60);
    }

    private static String token(String sessionId, String role, long usuarioId, long segundos) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("test@example.invalid")
                .claim("usuarioId", usuarioId)
                .claim("sessionId", sessionId)
                .claim("roles", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(segundos)))
                .signWith(Keys.hmacShaKeyFor(key))
                .compact();
    }

    private static HttpServer startSessionService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/auth/sessions/", exchange -> {
                ultimaConsultaSesion = exchange.getRequestURI().getQuery();
                String path = exchange.getRequestURI().getPath();
                if (path.contains("/simultanea/") && simultaneousSessions != null) {
                    simultaneousSessions.countDown();
                }
                if (path.contains("/conexion-cerrada/")) {
                    exchange.close();
                    return;
                }
                int status = 401;
                if (!INTERNAL_SECRET.equals(exchange.getRequestHeaders().getFirst("X-Belmonte-Internal-Secret"))
                        || path.contains("/credencial/")) {
                    status = 403;
                } else if (path.contains("/error/")) {
                    status = 503;
                } else if (path.contains("/activa/") || path.contains("/simultanea/")) {
                    status = 200;
                }
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.createContext("/internal/conductores/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/internal/conductores/".length());
                if (!INTERNAL_SECRET.equals(exchange.getRequestHeaders().getFirst("X-Belmonte-Internal-Secret"))) {
                    exchange.sendResponseHeaders(403, -1);
                } else if (recursosCaidos || id.equals("990005")) {
                    exchange.sendResponseHeaders(503, -1);
                } else if (id.equals("990001")) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    CountDownLatch consultado = conductorConsultado;
                    CountDownLatch continuar = continuarConsulta;
                    if ((id.equals("990006") || id.equals("990007")) && consultado != null && continuar != null) {
                        consultado.countDown();
                        try {
                            if (!continuar.await(10, TimeUnit.SECONDS)) {
                                exchange.sendResponseHeaders(503, -1);
                                exchange.close();
                                return;
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            exchange.close();
                            return;
                        }
                    }
                    String body = id.equals("990004") ? "{"
                            : "{\"usuarioId\":" + id + ",\"rol\":\""
                            + (id.equals("990003") ? "CLIENTE" : "CONDUCTOR")
                            + "\",\"activo\":" + !id.equals("990002") + "}";
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
                exchange.close();
            });
            server.createContext("/internal/comunas/", exchange -> {
                consultasComuna.incrementAndGet();
                String id = exchange.getRequestURI().getPath().substring("/internal/comunas/".length());
                if (!INTERNAL_SECRET.equals(exchange.getRequestHeaders().getFirst("X-Belmonte-Internal-Secret"))) {
                    exchange.sendResponseHeaders(403, -1);
                } else if (recursosCaidos || id.equals("990004")) {
                    exchange.sendResponseHeaders(503, -1);
                } else if (id.equals("990001")) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    if (id.equals("990005")) {
                        try {
                            Thread.sleep(4000);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            exchange.close();
                            return;
                        }
                    }
                    String body = id.equals("990002") ? "{"
                            : "{\"idComuna\":" + (id.equals("990003") ? "1" : id) + "}";
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
                exchange.close();
            });
            server.createContext("/internal/pasajeros/validar", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!INTERNAL_SECRET.equals(exchange.getRequestHeaders().getFirst("X-Belmonte-Internal-Secret"))) {
                    exchange.sendResponseHeaders(403, -1);
                } else if (body.contains("990021")) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (recursosCaidos || body.contains("990025")) {
                    exchange.sendResponseHeaders(503, -1);
                } else {
                    String response = body.contains("990022") ? "{"
                            : body.contains("990023") ? "{\"ids\":[101,1]}"
                            : body.contains("990024") ? "{\"ids\":[101,101]}" : body;
                    byte[] data = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
                exchange.close();
            });
            server.createContext("/internal/vehiculos/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/internal/vehiculos/".length());
                if (!INTERNAL_SECRET.equals(exchange.getRequestHeaders().getFirst("X-Belmonte-Internal-Secret"))
                        || id.equals("990014")) {
                    exchange.sendResponseHeaders(403, -1);
                } else if (recursosCaidos) {
                    exchange.sendResponseHeaders(503, -1);
                } else if (id.equals("990011")) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    String returnedId = id.equals("990015") ? "1" : id;
                    String body = "{\"vehiculoId\":" + returnedId + ",\"activo\":"
                            + !id.equals("990012") + ",\"capacidadPasajeros\":"
                            + (id.equals("990013") ? 1 : 10) + "}";
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo iniciar el servicio de sesiones de prueba", exception);
        }
    }
}
