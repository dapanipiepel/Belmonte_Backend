package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.service.DecisionAutorizacionService;
import com.belmonte.usuarios_service.service.UsuarioService;
import com.belmonte.usuarios_service.service.RelojBaseDatos;
import com.belmonte.usuarios_service.service.ControlDecisionSegura;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "jwt.secret=test-jwt-secret-not-for-deployment",
        "internal.api.secret=test-internal-secret",
        "spring.datasource.url=jdbc:h2:mem:decisionautorizacion;DB_CLOSE_DELAY=-1"
})
class DecisionAutorizacionTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DecisionAutorizacionService decisiones;
    @Autowired private UsuarioService usuarios;
    @Autowired private UsuarioRepository usuariosRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private RelojBaseDatos reloj;
    @MockitoBean private ControlDecisionSegura control;
    private Long actorId;
    private String sesionId;

    @BeforeEach
    void crearActor() {
        doAnswer(invocation -> Instant.now()).when(control).obtenerLimiteSuperior();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES (?)", "Pais " + suffix);
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES (?)", suffix);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE nombre_rol = 'ADMINISTRADOR'", Integer.class) == 0) {
            jdbc.update("INSERT INTO roles (nombre_rol) VALUES ('ADMINISTRADOR')");
        }
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = ?", Long.class, "Pais " + suffix);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = ?", Long.class, suffix);
        Long rol = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = 'ADMINISTRADOR'", Long.class);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) "
                + "VALUES (?, ?, ?, 'Prueba', ?, CURRENT_TIMESTAMP)", pais, tipo, suffix, suffix + "@example.invalid");
        Long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?",
                Long.class, suffix + "@example.invalid");
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, 'no-login', 1)", persona, rol);
        actorId = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, persona);
        sesionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sesiones_usuario (sesion_id, usuario_id, fecha_inicio, fecha_expiracion, fecha_expiracion_instante, activa) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, DATEADD('HOUR', 1, CURRENT_TIMESTAMP), DATEADD('HOUR', 1, CURRENT_TIMESTAMP), 1)", sesionId, actorId);
    }

    @Test
    void decisionEsRepetibleTrasLogoutYNoAdmiteOtraIntencion() {
        var solicitud = solicitud("confirmada", ahora().plusMinutes(5));
        assertEquals("AUTORIZADA", decisiones.decidir(solicitud));
        usuarios.cerrarSesion(sesionId);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(control).obtenerLimiteSuperior();
        assertEquals("AUTORIZADA", decisiones.decidir(solicitud));
        var distinta = new DecisionAutorizacionService.Solicitud(
                solicitud.servicio(), solicitud.clave(), actorId, sesionId, "b".repeat(64), solicitud.jwtExpiraEn());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(distinta))
                .getStatusCode().value());
        var otroVencimiento = new DecisionAutorizacionService.Solicitud(
                solicitud.servicio(), solicitud.clave(), actorId, sesionId, solicitud.hash(), solicitud.jwtExpiraEn().plusSeconds(1));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(otroVencimiento))
                .getStatusCode().value());
        var otraSesion = new DecisionAutorizacionService.Solicitud(
                solicitud.servicio(), solicitud.clave(), actorId, UUID.randomUUID().toString(), solicitud.hash(), solicitud.jwtExpiraEn());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(otraSesion))
                .getStatusCode().value());
    }

    @Test
    void sinDecisionPreviaLogoutImpideAutorizar() {
        usuarios.cerrarSesion(sesionId);
        var solicitud = solicitud("revocada", ahora().plusMinutes(5));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(solicitud))
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM decisiones_autorizacion WHERE decision_id = ?", Integer.class, solicitud.id()));
    }

    @Test
    void preparadaTrasCaidaSoloSeCertificaSiSeDemuestraPlazo() throws Exception {
        var recuperable = solicitud("recuperable", ahora().plusMinutes(5));
        simularPreparacionConfirmada(recuperable);
        usuarios.cerrarSesion(sesionId);
        assertEquals("AUTORIZADA", decisiones.decidir(recuperable));

        sesionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sesiones_usuario (sesion_id, usuario_id, fecha_inicio, fecha_expiracion, fecha_expiracion_instante, activa) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, DATEADD('HOUR', 1, CURRENT_TIMESTAMP), DATEADD('HOUR', 1, CURRENT_TIMESTAMP), 1)", sesionId, actorId);
        var incierta = solicitud("incierta", ahora().plusSeconds(3));
        simularPreparacionConfirmada(incierta);
        Thread.sleep(3200);
        assertEquals("INCIERTA", decisiones.decidir(incierta));
        assertEquals("PREPARADA", jdbc.queryForObject(
                "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, incierta.id()));
    }

    @Test
    void logoutPrevioAlBloqueoImpidePrepararLaDecision() throws Exception {
        var solicitud = solicitud("carrera", ahora().plusMinutes(5));
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Future<String>> futuro = new AtomicReference<>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                usuariosRepository.findForUpdate(actorId).orElseThrow();
                futuro.set(executor.submit(() -> {
                    started.countDown();
                    return decisiones.decidir(solicitud);
                }));
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                assertThrows(TimeoutException.class, () -> futuro.get().get(500, TimeUnit.MILLISECONDS));
                usuarios.cerrarSesion(sesionId);
            });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> futuro.get().get(5, TimeUnit.SECONDS));
            assertEquals(401, ((ResponseStatusException) failure.getCause()).getStatusCode().value());
        }
    }

    @Test
    void exigeVencimientoYNoPermiteUnaTransaccionExterna() {
        var solicitud = solicitud("transaccion", ahora().plusMinutes(5));
        assertThrows(IllegalTransactionStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> decisiones.decidir(solicitud)));
        var sinVencimiento = new DecisionAutorizacionService.Solicitud(
                solicitud.servicio(), solicitud.clave(), actorId, sesionId, solicitud.hash(), null);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(sinVencimiento))
                .getStatusCode().value());
        var precisionNoAdmitida = new DecisionAutorizacionService.Solicitud(
                solicitud.servicio(), solicitud.clave(), actorId, sesionId, solicitud.hash(), solicitud.jwtExpiraEn().plusNanos(1));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(precisionNoAdmitida))
                .getStatusCode().value());
    }

    @Test
    void preparadaConPlazoDeSesionVencidoQuedaIncierta() {
        var solicitud = solicitud("sesion-incierta", ahora().plusMinutes(5));
        simularPreparacionConfirmada(solicitud);
        jdbc.update("UPDATE decisiones_autorizacion SET sesion_expira_instante = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE decision_id = ?",
                solicitud.id());
        assertEquals("INCIERTA", decisiones.decidir(solicitud));
        assertEquals("PREPARADA", jdbc.queryForObject(
                "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));
    }

    @Test
    void vencimientoEntrePreparacionYCertificacionNoAutoriza() {
        var inicio = reloj.ahora();
        var solicitud = solicitud("vence-entre-fases", inicio.plusSeconds(30).withNano(0));
        doReturn(inicio.plusMinutes(1).toInstant()).when(control).obtenerLimiteSuperior();
        assertEquals("INCIERTA", decisiones.decidir(solicitud));
        assertEquals("PREPARADA", jdbc.queryForObject(
                "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));
    }

    @Test
    void obtienePruebaTrasCommitYFueraDeTransaccion() {
        var solicitud = solicitud("prueba-posterior", ahora().plusMinutes(5));
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PREPARADA", jdbc.queryForObject(
                    "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));
            return Instant.now();
        }).when(control).obtenerLimiteSuperior();
        assertEquals("AUTORIZADA", decisiones.decidir(solicitud));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM decisiones_autorizacion WHERE decision_id = ? AND tiempo_limite_superior IS NOT NULL",
                Integer.class, solicitud.id()));
    }

    @Test
    void decisionConFuenteFirmadaSimuladaRechazaIdentidadAjenaYPermiteReintento() throws Exception {
        var generador = KeyPairGenerator.getInstance("Ed25519");
        var confiable = generador.generateKeyPair();
        var ajena = generador.generateKeyPair();
        var solicitud = solicitud("fuente-firmada", ahora().plusMinutes(5));
        var limite = ahora().plusSeconds(1).toInstant();
        var maxError = Duration.ofSeconds(2);
        var maxEdad = Duration.ofSeconds(60);
        doAnswer(invocation -> SimuladorTiempo.consultar(confiable.getPublic(), maxError, maxEdad, desafio -> {
            try {
                return SimuladorTiempo.firmar(ajena, desafio, limite, Duration.ZERO, Duration.ZERO, true);
            } catch (java.security.GeneralSecurityException exception) {
                throw new AssertionError(exception);
            }
        })).when(control).obtenerLimiteSuperior();
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(solicitud))
                .getStatusCode().value());
        assertEquals("PREPARADA", jdbc.queryForObject(
                "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));

        doAnswer(invocation -> SimuladorTiempo.consultar(confiable.getPublic(), maxError, maxEdad, desafio -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            try {
                return SimuladorTiempo.firmar(confiable, desafio, limite, Duration.ZERO, Duration.ZERO, true);
            } catch (java.security.GeneralSecurityException exception) {
                throw new AssertionError(exception);
            }
        })).when(control).obtenerLimiteSuperior();
        assertEquals("AUTORIZADA", decisiones.decidir(solicitud));
    }

    @Test
    void logoutPuedeConfirmarseMientrasEsperaLaFuente() throws Exception {
        var solicitud = solicitud("logout-durante-fuente", ahora().plusMinutes(5));
        var consultaIniciada = new CountDownLatch(1);
        var liberarConsulta = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            consultaIniciada.countDown();
            if (!liberarConsulta.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Consulta simulada no liberada");
            }
            return Instant.now();
        }).when(control).obtenerLimiteSuperior();
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var decision = executor.submit(() -> decisiones.decidir(solicitud));
                assertTrue(consultaIniciada.await(5, TimeUnit.SECONDS));
                assertEquals("PREPARADA", jdbc.queryForObject(
                        "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));
                var logout = executor.submit(() -> usuarios.cerrarSesion(sesionId));
                logout.get(3, TimeUnit.SECONDS);
                liberarConsulta.countDown();
                assertEquals("AUTORIZADA", decision.get(5, TimeUnit.SECONDS));
            } finally {
                liberarConsulta.countDown();
            }
        }
    }

    @Test
    void fuenteCaidaConservaPreparacionParaReintentoSeguro() {
        var solicitud = solicitud("fuente-caida", ahora().plusMinutes(5));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(control).obtenerLimiteSuperior();
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(solicitud))
                .getStatusCode().value());
        assertEquals("PREPARADA", jdbc.queryForObject(
                "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));
        doReturn(Instant.now()).when(control).obtenerLimiteSuperior();
        assertEquals("AUTORIZADA", decisiones.decidir(solicitud));
    }

    @Test
    void pruebaAusenteNoAutoriza() {
        var solicitud = solicitud("prueba-ausente", ahora().plusMinutes(5));
        doReturn(null).when(control).obtenerLimiteSuperior();
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(solicitud))
                .getStatusCode().value());
        assertEquals("PREPARADA", jdbc.queryForObject(
                "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));
    }

    @Test
    void limiteIgualAlVencimientoNoAutoriza() {
        var solicitud = solicitud("limite-igual", ahora().plusMinutes(5));
        doReturn(solicitud.jwtExpiraEn().toInstant()).when(control).obtenerLimiteSuperior();
        assertEquals("INCIERTA", decisiones.decidir(solicitud));
        assertEquals("PREPARADA", jdbc.queryForObject(
                "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));
    }

    @Test
    void limiteIgualAlVencimientoDeSesionNoAutoriza() {
        var solicitud = solicitud("limite-sesion-igual", ahora().plusHours(2));
        simularPreparacionConfirmada(solicitud);
        var expiraSesion = jdbc.queryForObject(
                "SELECT sesion_expira_instante FROM decisiones_autorizacion WHERE decision_id = ?",
                OffsetDateTime.class, solicitud.id());
        doReturn(expiraSesion.toInstant()).when(control).obtenerLimiteSuperior();
        assertEquals("INCIERTA", decisiones.decidir(solicitud));
        assertEquals("PREPARADA", jdbc.queryForObject(
                "SELECT estado FROM decisiones_autorizacion WHERE decision_id = ?", String.class, solicitud.id()));
    }

    @Test
    void autorizacionAntiguaSinPruebaIndependienteEsIncierta() {
        var solicitud = solicitud("autorizada-antigua", ahora().plusMinutes(5));
        simularPreparacionConfirmada(solicitud);
        jdbc.update("UPDATE decisiones_autorizacion SET estado = 'AUTORIZADA' WHERE decision_id = ?", solicitud.id());
        assertEquals("INCIERTA", decisiones.decidir(solicitud));
    }

    @Test
    void noAutorizaSesionAjenaNiActorSinRol() {
        var solicitud = solicitud("sesion-ajena", ahora().plusMinutes(5));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) "
                + "SELECT pais_documento_id, tipo_documento_id, ?, 'Otro', ?, CURRENT_TIMESTAMP "
                + "FROM personas WHERE id_persona = (SELECT persona_id FROM usuarios WHERE id_usuario = ?)",
                suffix, suffix + "@example.invalid", actorId);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) "
                + "SELECT p.id_persona, u.rol_id, 'no-login', 1 FROM personas p, usuarios u "
                + "WHERE p.email = ? AND u.id_usuario = ?", suffix + "@example.invalid", actorId);
        Long otroActor = jdbc.queryForObject("SELECT u.id_usuario FROM usuarios u JOIN personas p ON p.id_persona = u.persona_id "
                + "WHERE p.email = ?", Long.class, suffix + "@example.invalid");
        var ajena = new DecisionAutorizacionService.Solicitud(
                solicitud.servicio(), solicitud.clave(), otroActor, sesionId, solicitud.hash(), solicitud.jwtExpiraEn());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(ajena))
                .getStatusCode().value());
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES (?)", "OTRO-" + suffix);
        jdbc.update("UPDATE usuarios SET rol_id = (SELECT id_rol FROM roles WHERE nombre_rol = ?) WHERE id_usuario = ?",
                "OTRO-" + suffix, actorId);
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(solicitud))
                .getStatusCode().value());
        jdbc.update("UPDATE usuarios SET rol_id = (SELECT id_rol FROM roles WHERE nombre_rol = 'ADMINISTRADOR') WHERE id_usuario = ?", actorId);
        jdbc.update("UPDATE usuarios SET activo = 0 WHERE id_usuario = ?", actorId);
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(solicitud))
                .getStatusCode().value());
    }

    @Test
    void noAutorizaConVencimientoPrevioDeJwtOSesion() {
        var jwtVencido = solicitud("jwt-vencido", ahora().minusSeconds(1));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(jwtVencido))
                .getStatusCode().value());
        jdbc.update("UPDATE sesiones_usuario SET fecha_expiracion_instante = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE sesion_id = ?", sesionId);
        var sesionVencida = solicitud("sesion-vencida", ahora().plusMinutes(5));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(sesionVencida))
                .getStatusCode().value());
    }

    @Test
    void soloElInstanteInequivocoPermiteCertificar() {
        jdbc.update("UPDATE sesiones_usuario SET fecha_expiracion = DATEADD('DAY', -1, CURRENT_TIMESTAMP) WHERE sesion_id = ?", sesionId);
        assertEquals("AUTORIZADA", decisiones.decidir(solicitud("sin-zona-local", ahora().plusMinutes(5))));
        jdbc.update("UPDATE sesiones_usuario SET fecha_expiracion_instante = NULL WHERE sesion_id = ?", sesionId);
        var antigua = solicitud("sesion-antigua", ahora().plusMinutes(5));
        assertEquals("INCIERTA", decisiones.decidir(antigua));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM decisiones_autorizacion WHERE decision_id = ?", Integer.class, antigua.id()));
    }

    @Test
    void decisionAntiguaSinInstanteNoSeCertifica() {
        var solicitud = solicitud("decision-antigua", ahora().plusMinutes(5));
        simularPreparacionConfirmada(solicitud);
        jdbc.update("UPDATE decisiones_autorizacion SET sesion_expira_instante = NULL WHERE decision_id = ?", solicitud.id());
        assertEquals("INCIERTA", decisiones.decidir(solicitud));
        jdbc.update("UPDATE decisiones_autorizacion SET estado = 'AUTORIZADA' WHERE decision_id = ?", solicitud.id());
        assertEquals("INCIERTA", decisiones.decidir(solicitud));
    }

    @Test
    void solicitudesConcurrentesConLaMismaClaveConvergen() throws Exception {
        var solicitud = solicitud("concurrente", ahora().plusMinutes(5));
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futuros = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) {
                futuros.add(executor.submit(() -> {
                    if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Inicio agotado");
                    return decisiones.decidir(solicitud);
                }));
            }
            inicio.countDown();
            for (var futuro : futuros) assertEquals("AUTORIZADA", futuro.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM decisiones_autorizacion WHERE decision_id = ?", Integer.class, solicitud.id()));
    }

    private DecisionAutorizacionService.Solicitud solicitud(String clave, OffsetDateTime expira) {
        return new DecisionAutorizacionService.Solicitud(
                "VEHICULOS_ESTADO", clave, actorId, sesionId, "a".repeat(64), expira);
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private void simularPreparacionConfirmada(DecisionAutorizacionService.Solicitud solicitud) {
        jdbc.update("""
                INSERT INTO decisiones_autorizacion
                (decision_id, actor_id, sesion_id, solicitud_hash, jwt_expira_en, sesion_expira_en, sesion_expira_instante, estado)
                SELECT ?, ?, ?, ?, ?, fecha_expiracion, fecha_expiracion_instante, 'PREPARADA' FROM sesiones_usuario WHERE sesion_id = ?
                """, solicitud.id(), actorId, sesionId, solicitud.hash(), solicitud.jwtExpiraEn(), sesionId);
    }
}
