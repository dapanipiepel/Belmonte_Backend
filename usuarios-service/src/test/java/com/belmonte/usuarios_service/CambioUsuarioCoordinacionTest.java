package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.belmonte.usuarios_service.service.CambioUsuarioIdempotenteService;
import com.belmonte.usuarios_service.service.CambioUsuarioTransacciones;
import com.belmonte.usuarios_service.service.JwtService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CambioUsuarioCoordinacionTest {
    private static final ExecutorService SERVER_THREADS = Executors.newCachedThreadPool();
    private static final Map<String, String> BARRERAS = new ConcurrentHashMap<>();
    private static final Map<String, String> PROPOSITOS = new ConcurrentHashMap<>();
    private static volatile boolean rechazar;
    private static volatile boolean invalida;
    private static volatile boolean perderCierre;
    private static volatile boolean fallarAntesDeCerrar;
    private static volatile CountDownLatch preparacionIniciada;
    private static volatile CountDownLatch continuarPreparacion;
    private static final HttpServer VIAJES = iniciarViajes();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private CambioUsuarioIdempotenteService cambios;
    @Autowired private CambioUsuarioTransacciones transacciones;
    @Autowired private PlatformTransactionManager transactionManager;
    private Actor admin;
    private Actor otroAdmin;
    private Actor conductor;
    private Long rolConductor;
    private Long rolSolicitante;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "test-jwt-secret-not-for-deployment");
        registry.add("internal.api.secret", () -> "test-internal-secret");
        registry.add("viajes.service.uri", () -> "http://127.0.0.1:" + VIAJES.getAddress().getPort());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:usuarioscoordinacion;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void preparar() {
        BARRERAS.clear();
        PROPOSITOS.clear();
        rechazar = false;
        invalida = false;
        perderCierre = false;
        fallarAntesDeCerrar = false;
        preparacionIniciada = null;
        continuarPreparacion = null;
        Long rolAdmin = rol("ADMINISTRADOR");
        rolConductor = rol("CONDUCTOR");
        rolSolicitante = rol("SOLICITANTE");
        admin = actor(rolAdmin, "ADMINISTRADOR");
        otroAdmin = actor(rolAdmin, "ADMINISTRADOR");
        conductor = actor(rolConductor, "CONDUCTOR");
    }

    @AfterAll
    static void terminar() {
        VIAJES.stop(0);
        SERVER_THREADS.shutdownNow();
    }

    @Test
    void reintentosYRolesConservanResultadoYPropiedad() throws Exception {
        mvc.perform(estado(admin, conductor.id(), null, false)).andExpect(status().isBadRequest());
        String primero = mvc.perform(estado(admin, conductor.id(), "estado-primero", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(0))
                .andReturn().getResponse().getContentAsString();
        assertEquals("BAJA", proposito("estado-primero"));
        assertEquals(0, sesionesActivas(conductor.id()));
        mvc.perform(estado(admin, conductor.id(), "estado-activar", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(1));
        assertEquals("CAMBIO_COMPATIBLE", proposito("estado-activar"));
        assertEquals(primero, mvc.perform(estado(admin, conductor.id(), "estado-primero", false))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(estado(admin, conductor.id(), "estado-primero", true)).andExpect(status().isConflict());
        mvc.perform(estado(otroAdmin, conductor.id(), "estado-primero", false)).andExpect(status().isConflict());
        mvc.perform(rol(admin, conductor.id(), "estado-primero", rolSolicitante)).andExpect(status().isConflict());
        mvc.perform(get("/api/usuarios/operaciones/cambio").param("clave", "estado-primero")
                .header("Authorization", admin.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COMPLETADA"))
                .andExpect(jsonPath("$.resultado.activo").value(0));
        mvc.perform(get("/api/usuarios/operaciones/cambio").param("clave", "estado-primero")
                .header("Authorization", otroAdmin.token())).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
        mvc.perform(rol(admin, conductor.id(), "rol-baja", rolSolicitante)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("SOLICITANTE"));
        assertEquals("BAJA", proposito("rol-baja"));
        mvc.perform(rol(admin, conductor.id(), "rol-compatible", rolConductor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("CONDUCTOR"));
        assertEquals("CAMBIO_COMPATIBLE", proposito("rol-compatible"));
    }

    @Test
    void rechazoEsDefinitivoYRespuestaInvalidaNoMuta() throws Exception {
        rechazar = true;
        mvc.perform(rol(admin, conductor.id(), "rol-rechazado", rolSolicitante)).andExpect(status().isConflict());
        assertNull(jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_cambio_usuario WHERE clave = ?", String.class, "rol-rechazado"));
        assertNull(jdbc.queryForObject(
                "SELECT tipo_solicitud FROM operaciones_cambio_usuario WHERE clave = ?", String.class, "rol-rechazado"));
        rechazar = false;
        mvc.perform(rol(admin, conductor.id(), "rol-rechazado", rolSolicitante)).andExpect(status().isConflict());
        assertEquals(rolConductor, jdbc.queryForObject("SELECT rol_id FROM usuarios WHERE id_usuario = ?",
                Long.class, conductor.id()));
        assertEquals(1, sesionesActivas(conductor.id()));
        invalida = true;
        mvc.perform(estado(admin, conductor.id(), "respuesta-invalida", false))
                .andExpect(status().isServiceUnavailable());
        assertEquals(1, jdbc.queryForObject("SELECT activo FROM usuarios WHERE id_usuario = ?",
                Integer.class, conductor.id()));
        invalida = false;
        mvc.perform(estado(admin, conductor.id(), "respuesta-invalida", false)).andExpect(status().isOk());
    }

    @Test
    void pendienteConservaIntencionYSesionOriginalSinSustituirlaEnReintentos() throws Exception {
        invalida = true;
        mvc.perform(rol(admin, conductor.id(), "intencion-rol", rolSolicitante))
                .andExpect(status().isServiceUnavailable());
        var vencimientoOriginal = jwt.parseToken(admin.token().substring(7)).getExpiration().toInstant();
        assertEquals(vencimientoOriginal, jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_cambio_usuario WHERE clave = ?",
                OffsetDateTime.class, "intencion-rol").toInstant());
        assertEquals(admin.sesion(), jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_cambio_usuario WHERE clave = ?", String.class, "intencion-rol"));
        assertEquals("ROL", jdbc.queryForObject(
                "SELECT tipo_solicitud FROM operaciones_cambio_usuario WHERE clave = ?", String.class, "intencion-rol"));
        assertEquals(rolSolicitante, jdbc.queryForObject(
                "SELECT solicitud_rol_id FROM operaciones_cambio_usuario WHERE clave = ?", Long.class, "intencion-rol"));
        mvc.perform(get("/api/usuarios/operaciones/cambio").param("clave", "intencion-rol")
                .header("Authorization", admin.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.sesionOriginal").doesNotExist())
                .andExpect(jsonPath("$.jwtOriginalExpiraEn").doesNotExist())
                .andExpect(jsonPath("$.solicitudRolId").doesNotExist());
        String otraSesion = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sesiones_usuario (sesion_id, usuario_id, fecha_inicio, fecha_expiracion, fecha_expiracion_instante, activa) VALUES (?, ?, CURRENT_TIMESTAMP, DATEADD('HOUR', 1, CURRENT_TIMESTAMP), DATEADD('HOUR', 1, CURRENT_TIMESTAMP), 1)",
                otraSesion, admin.id());
        Actor mismoActor = new Actor(admin.id(), otraSesion,
                "Bearer " + new JwtService("test-jwt-secret-not-for-deployment", 60000)
                        .createToken("admin@example.invalid", admin.id(), otraSesion, List.of("ADMINISTRADOR")));
        mvc.perform(rol(mismoActor, conductor.id(), "intencion-rol", rolSolicitante))
                .andExpect(status().isServiceUnavailable());
        assertEquals(admin.sesion(), jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_cambio_usuario WHERE clave = ?", String.class, "intencion-rol"));
        assertEquals(vencimientoOriginal, jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_cambio_usuario WHERE clave = ?",
                OffsetDateTime.class, "intencion-rol").toInstant());
        mvc.perform(estado(admin, conductor.id(), "intencion-estado", false)).andExpect(status().isServiceUnavailable());
        assertEquals("ESTADO", jdbc.queryForObject(
                "SELECT tipo_solicitud FROM operaciones_cambio_usuario WHERE clave = ?", String.class, "intencion-estado"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT solicitud_activo FROM operaciones_cambio_usuario WHERE clave = ?", Integer.class, "intencion-estado"));
        invalida = false;
        mvc.perform(rol(mismoActor, conductor.id(), "intencion-rol", rolSolicitante)).andExpect(status().isOk());
        assertNull(jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_cambio_usuario WHERE clave = ?", String.class, "intencion-rol"));
        assertNull(jdbc.queryForObject(
                "SELECT solicitud_rol_id FROM operaciones_cambio_usuario WHERE clave = ?", Long.class, "intencion-rol"));
        assertNull(jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_cambio_usuario WHERE clave = ?",
                OffsetDateTime.class, "intencion-rol"));
    }

    @Test
    void jwtSinVencimientoNoInventaUnaFecha() throws Exception {
        var key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256")
                .digest("test-jwt-secret-not-for-deployment".getBytes(StandardCharsets.UTF_8)));
        String token = "Bearer " + Jwts.builder().subject("admin@example.invalid")
                .claim("usuarioId", admin.id()).claim("sessionId", admin.sesion())
                .claim("roles", List.of("ADMINISTRADOR")).signWith(key).compact();
        invalida = true;
        mvc.perform(estado(new Actor(admin.id(), admin.sesion(), token), conductor.id(), "sin-exp", false))
                .andExpect(status().isServiceUnavailable());
        assertNull(jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_cambio_usuario WHERE clave = ?",
                OffsetDateTime.class, "sin-exp"));
    }

    @Test
    void mismoRolConservaSesionYSoloAdminPuedeModificar() throws Exception {
        mvc.perform(rol(admin, conductor.id(), "mismo-rol", rolConductor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rol").value("CONDUCTOR"));
        assertEquals(1, sesionesActivas(conductor.id()));
        mvc.perform(estado(conductor, conductor.id(), "sin-permiso", false)).andExpect(status().isForbidden());
    }

    @Test
    void cierreAutomaticoRecuperaAutoDesactivacionSinSesion() throws Exception {
        perderCierre = true;
        mvc.perform(estado(admin, admin.id(), "auto-desactivacion", false))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, sesionesActivas(admin.id()));
        assertEquals("APLICADA", estadoOperacion("auto-desactivacion"));
        mvc.perform(estado(admin, admin.id(), "auto-desactivacion", false)).andExpect(status().isUnauthorized());
        transacciones.limpiarRespuestasExpiradas();
        assertEquals("APLICADA", estadoOperacion("auto-desactivacion"));
        perderCierre = false;
        cambios.recuperarAplicadas();
        assertEquals("COMPLETADA", estadoOperacion("auto-desactivacion"));
        assertEquals("CERRADA", BARRERAS.get(barrera("auto-desactivacion")));
        mvc.perform(get("/api/usuarios/operaciones/cambio").param("clave", "auto-desactivacion")
                .header("Authorization", otroAdmin.token())).andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"));
    }

    @Test
    void cierreAutomaticoRecuperaAutoCambioDeRolConBarreraAbierta() throws Exception {
        fallarAntesDeCerrar = true;
        mvc.perform(rol(admin, admin.id(), "auto-rol", rolSolicitante))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, sesionesActivas(admin.id()));
        assertEquals("APLICADA", estadoOperacion("auto-rol"));
        assertEquals("ABIERTA", BARRERAS.get(barrera("auto-rol")));
        fallarAntesDeCerrar = false;
        cambios.recuperarAplicadas();
        assertEquals("CERRADA", BARRERAS.get(barrera("auto-rol")));
        assertEquals("COMPLETADA", estadoOperacion("auto-rol"));
    }

    @Test
    void faltantesExpiracionYActorNoCoincidente() throws Exception {
        mvc.perform(rol(admin, conductor.id(), "rol-inexistente", 999999999L)).andExpect(status().isNotFound());
        assertEquals("COMPLETADA", estadoOperacion("rol-inexistente"));
        assertEquals(0, BARRERAS.size());
        mvc.perform(estado(admin, 999999999L, "usuario-inexistente", false)).andExpect(status().isNotFound());
        mvc.perform(estado(admin, conductor.id(), "expiracion", false)).andExpect(status().isOk());
        jdbc.update("UPDATE operaciones_cambio_usuario SET expira_en = ? WHERE clave = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), "expiracion");
        transacciones.limpiarRespuestasExpiradas();
        assertEquals("EXPIRADA", estadoOperacion("expiracion"));
        assertNull(jdbc.queryForObject("SELECT actor_id FROM operaciones_cambio_usuario WHERE clave = ?",
                Long.class, "expiracion"));
        assertNull(jdbc.queryForObject("SELECT respuesta_json FROM operaciones_cambio_usuario WHERE clave = ?",
                String.class, "expiracion"));
        mvc.perform(estado(admin, conductor.id(), "expiracion", false)).andExpect(status().isConflict());
        String tokenFalso = "Bearer " + jwt.createToken("admin@example.invalid", otroAdmin.id(),
                admin.sesion(), List.of("ADMINISTRADOR"));
        mvc.perform(patch("/api/usuarios/{id}/estado", conductor.id()).header("Authorization", tokenFalso)
                .header("Idempotency-Key", "actor-incorrecto").contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}")).andExpect(status().isUnauthorized());
    }

    @Test
    void esperaHttpNoRetieneTransaccionYClaveConcurrenteAplicaUnaVez() throws Exception {
        assertThrows(IllegalTransactionStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> cambios.cambiarEstado(
                        "transaccion", admin.id(), admin.sesion(), conductor.id(), false, null)));
        preparacionIniciada = new CountDownLatch(1);
        continuarPreparacion = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> mvc.perform(estado(admin, conductor.id(), "concurrente", false))
                    .andReturn().getResponse());
            assertEquals(true, preparacionIniciada.await(5, TimeUnit.SECONDS));
            assertEquals("PENDIENTE", estadoOperacion("concurrente"));
            var second = executor.submit(() -> mvc.perform(estado(admin, conductor.id(), "concurrente", false))
                    .andReturn().getResponse());
            continuarPreparacion.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(200, a.getStatus());
            assertEquals(200, b.getStatus());
            assertEquals(a.getContentAsString(), b.getContentAsString());
            assertEquals(0, sesionesActivas(conductor.id()));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM operaciones_cambio_usuario WHERE clave = 'concurrente'", Integer.class));
        } finally {
            continuarPreparacion.countDown();
        }
    }

    private MockHttpServletRequestBuilder estado(Actor actor, Long id, String clave, boolean activo) {
        var request = patch("/api/usuarios/{id}/estado", id).header("Authorization", actor.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":" + activo + "}");
        if (clave != null) request.header("Idempotency-Key", clave);
        return request;
    }

    private MockHttpServletRequestBuilder rol(Actor actor, Long id, String clave, Long rolId) {
        return patch("/api/usuarios/{id}/rol", id).header("Authorization", actor.token())
                .header("Idempotency-Key", clave).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rolId\":" + rolId + "}");
    }

    private Long rol(String nombre) {
        var ids = jdbc.queryForList("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, nombre);
        if (ids.isEmpty()) jdbc.update("INSERT INTO roles (nombre_rol) VALUES (?)", nombre);
        return jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, nombre);
    }

    private Actor actor(Long rolId, String nombreRol) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES (?)", "Pais " + suffix);
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES (?)", suffix);
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = ?", Long.class, "Pais " + suffix);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = ?", Long.class, suffix);
        String email = suffix + "@example.invalid";
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) VALUES (?, ?, ?, 'Prueba', ?, CURRENT_TIMESTAMP)",
                pais, tipo, suffix, email);
        Long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, 'no-login', 1)",
                persona, rolId);
        Long usuario = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, persona);
        String sesion = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sesiones_usuario (sesion_id, usuario_id, fecha_inicio, fecha_expiracion, fecha_expiracion_instante, activa) VALUES (?, ?, CURRENT_TIMESTAMP, DATEADD('HOUR', 1, CURRENT_TIMESTAMP), DATEADD('HOUR', 1, CURRENT_TIMESTAMP), 1)",
                sesion, usuario);
        return new Actor(usuario, sesion, "Bearer " + jwt.createToken(email, usuario, sesion, List.of(nombreRol)));
    }

    private int sesionesActivas(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sesiones_usuario WHERE usuario_id = ? AND activa = 1",
                Integer.class, id);
    }

    private String estadoOperacion(String clave) {
        return jdbc.queryForObject("SELECT estado FROM operaciones_cambio_usuario WHERE clave = ?", String.class, clave);
    }

    private String barrera(String clave) {
        return jdbc.queryForObject("SELECT barrera_id FROM operaciones_cambio_usuario WHERE clave = ?", String.class, clave);
    }

    private String proposito(String clave) {
        return PROPOSITOS.get(barrera(clave));
    }

    private static HttpServer iniciarViajes() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(SERVER_THREADS);
            server.createContext("/internal/barreras/", CambioUsuarioCoordinacionTest::responder);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void responder(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            boolean cierre = path.endsWith("/cerrar");
            String id = path.substring("/internal/barreras/".length()).replace("/cerrar", "");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!"test-internal-secret".equals(exchange.getRequestHeaders().getFirst("X-Belmonte-Internal-Secret"))
                    || !body.contains("\"tipo\":\"CONDUCTOR\"")) {
                responder(exchange, 403, "");
                return;
            }
            if (cierre) {
                if (fallarAntesDeCerrar) {
                    responder(exchange, 503, "");
                    return;
                }
                BARRERAS.put(id, "CERRADA");
                responder(exchange, perderCierre ? 503 : 204, "");
                return;
            }
            var iniciado = preparacionIniciada;
            if (iniciado != null) {
                iniciado.countDown();
                try {
                    if (!continuarPreparacion.await(5, TimeUnit.SECONDS)) throw new IOException("Espera agotada");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            if (invalida) {
                responder(exchange, 200, "{}");
                return;
            }
            String proposito = body.contains("CAMBIO_COMPATIBLE") ? "CAMBIO_COMPATIBLE" : "BAJA";
            PROPOSITOS.put(id, proposito);
            String estado = BARRERAS.computeIfAbsent(id,
                    ignored -> rechazar && "BAJA".equals(proposito) ? "RECHAZADA" : "ABIERTA");
            responder(exchange, "RECHAZADA".equals(estado) ? 409 : 200, "{\"estado\":\"" + estado + "\"}");
        } finally {
            exchange.close();
        }
    }

    private static void responder(HttpExchange exchange, int codigo, String contenido) throws IOException {
        byte[] bytes = contenido.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(codigo, codigo == 204 ? -1 : bytes.length);
        if (codigo != 204) exchange.getResponseBody().write(bytes);
    }

    private record Actor(Long id, String sesion, String token) {}
}
