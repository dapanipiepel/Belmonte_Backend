package com.belmonte.viajes_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.sun.net.httpserver.HttpServer;

import com.belmonte.viajes_service.model.HistorialEstado;
import com.belmonte.viajes_service.model.Traslado;
import com.belmonte.viajes_service.model.Ubicacion;
import com.belmonte.viajes_service.repository.EstadoTrasladoRepository;
import com.belmonte.viajes_service.repository.HistorialEstadoRepository;
import com.belmonte.viajes_service.repository.TrasladoRepository;
import com.belmonte.viajes_service.repository.UbicacionRepository;
import com.belmonte.viajes_service.service.AsignacionOperacionTransacciones;
import com.belmonte.viajes_service.service.AsignacionIdempotenteService;
import com.belmonte.viajes_service.service.BarreraRecursoService;
import com.belmonte.viajes_service.service.TrasladoService;
import com.belmonte.viajes_service.dto.AsignacionRequest;
import com.belmonte.viajes_service.dto.CancelacionRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "BELMONTE_ORACLE_TEST_URL", matches = ".+")
class ViajesOracleMigrationTest {

    @Test
    void migratesAnEmptyTestSchemaAndEnforcesItsConstraints() throws Exception {
        String url = System.getenv("BELMONTE_ORACLE_TEST_URL");
        String username = Objects.requireNonNull(System.getenv("BELMONTE_ORACLE_TEST_USER"));
        String password = Objects.requireNonNull(System.getenv("BELMONTE_ORACLE_TEST_PASSWORD"));

        assertTrue(username.matches("BMTEST_[A-F0-9]{12}"), "Use a dedicated temporary test account");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                    "SELECT SYS_CONTEXT('USERENV', 'CURRENT_USER'), (SELECT COUNT(*) FROM USER_OBJECTS) FROM DUAL")) {
            assertTrue(result.next());
            assertEquals(username, result.getString(1));
            assertEquals(0, result.getInt(2), "The test schema must be empty");
        }
        Flyway.configure().dataSource(url, username, password).target("2").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO operaciones_traslados "
                    + "(clave, actor_id, operacion, traslado_id, respuesta_codigo) "
                    + "VALUES ('oracle-anterior', 7, 'asignar', 999999999, 200)");
        }
        Flyway.configure().dataSource(url, username, password).target("4").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO operaciones_traslados "
                    + "(clave, actor_id, operacion, traslado_id, respuesta_codigo, estado_operacion, solicitud_hash) "
                    + "VALUES ('legacy-pendiente', 7, 'asignar', 1, 102, 'PENDIENTE', 'hash')");
        }
        Flyway.configure().dataSource(url, username, password).target("5").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO operaciones_traslados "
                    + "(clave, operacion, respuesta_codigo, estado_operacion, sesion_original, "
                    + "solicitud_conductor_id, solicitud_vehiculo_id) "
                    + "VALUES ('sin-vencimiento', 'asignar', 102, 'PENDIENTE', 'sesion', 2, 3)");
        }

        HttpServer resources = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resources.createContext("/internal/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            String body = path.startsWith("/internal/conductores/")
                    ? "{\"usuarioId\":" + id + ",\"rol\":\"CONDUCTOR\",\"activo\":true}"
                    : "{\"vehiculoId\":" + id + ",\"activo\":true,\"capacidadPasajeros\":10}";
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        resources.start();
        try {
            String uri = "http://127.0.0.1:" + resources.getAddress().getPort();
            ConfigurableEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("oracleTest", Map.of(
                    "VIAJES_DB_URL", url, "VIAJES_DB_USER", username, "VIAJES_DB_PASSWORD", password,
                    "server.port", "0", "usuarios.service.uri", uri, "vehiculos.service.uri", uri)));
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ViajesServiceApplication.class)
                    .environment(environment).profiles("oracle").web(WebApplicationType.SERVLET).run()) {
                Flyway flyway = Flyway.configure().dataSource(url, username, password).load();
                assertEquals(0, flyway.migrate().migrationsExecuted);
                assertTrue(flyway.validateWithResult().validationSuccessful);
                assertEquals("6", flyway.info().current().getVersion().getVersion());
                verifyConstraints(url, username, password);
                verifyRepositories(context, url, username, password);
                verifyAssignmentOperation(context, url, username, password);
                verifyConcurrentAssignments(context);
                verifyBarrier(context);
                verifyConcurrentBarrierAndAssignment(context);
            }
        } finally {
            resources.stop(0);
        }
    }

    private void verifyConcurrentBarrierAndAssignment(ConfigurableApplicationContext context) throws Exception {
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        Long id = transaction.execute(status -> {
            var ubicaciones = context.getBean(UbicacionRepository.class).findAll();
            var traslado = context.getBean(TrasladoRepository.class).saveAndFlush(new Traslado(
                    7L, ubicaciones.get(0), ubicaciones.get(1),
                    OffsetDateTime.parse("2026-12-01T10:00:00-03:00"),
                    OffsetDateTime.parse("2026-12-01T11:00:00-03:00"), null, Set.of(11L)));
            var estado = context.getBean(EstadoTrasladoRepository.class).findByCodigo("PENDIENTE").orElseThrow();
            context.getBean(HistorialEstadoRepository.class).saveAndFlush(new HistorialEstado(traslado, estado, 7L, null));
            return traslado.getId();
        });
        var asignaciones = context.getBean(AsignacionIdempotenteService.class);
        var barreras = context.getBean(BarreraRecursoService.class);
        var start = new CountDownLatch(1);
        String operation = UUID.randomUUID().toString();
        var request = new AsignacionRequest(912001L, 912002L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var assignment = executor.submit(() -> {
                start.await();
                try {
                    return asignaciones.asignar("oracle-carrera-barrera", 7L, "activa", id, request, null).codigo();
                } catch (ResponseStatusException exception) {
                    return exception.getStatusCode().value();
                }
            });
            var barrier = executor.submit(() -> {
                start.await();
                try {
                    return "RECHAZADA".equals(barreras.prepararBaja(operation, "VEHICULO", 912002L)) ? 409 : 200;
                } catch (ResponseStatusException exception) {
                    return exception.getStatusCode().value();
                }
            });
            start.countDown();
            int a = assignment.get(20, TimeUnit.SECONDS);
            int b = barrier.get(20, TimeUnit.SECONDS);
            assertTrue((a == 200 && (b == 409 || b == 503)) || (a == 503 && b == 200),
                    "assignment=" + a + ", barrier=" + b);
            if (b == 200) {
                barreras.cerrar(operation, "VEHICULO", 912002L);
                assertEquals(200, asignaciones.asignar("oracle-carrera-barrera", 7L, "activa", id, request, null).codigo());
            }
            if (b == 503) {
                assertEquals("RECHAZADA", barreras.prepararBaja(operation, "VEHICULO", 912002L));
            }
        }
        String compatible = UUID.randomUUID().toString();
        assertEquals("ABIERTA", barreras.preparar(compatible, "VEHICULO", 912002L, "CAMBIO_COMPATIBLE"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> barreras.cerrar(compatible, "VEHICULO", 912002L, "BAJA")).getStatusCode().value());
        barreras.cerrar(compatible, "VEHICULO", 912002L, "CAMBIO_COMPATIBLE");
        assertEquals("CERRADA", barreras.preparar(compatible, "VEHICULO", 912002L, "CAMBIO_COMPATIBLE"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> barreras.preparar(compatible, "VEHICULO", 912002L, "BAJA")).getStatusCode().value());
        String rejected = UUID.randomUUID().toString();
        assertEquals("RECHAZADA", barreras.prepararBaja(rejected, "VEHICULO", 912002L));
        context.getBean(TrasladoService.class).cancelar(id, new CancelacionRequest("Prueba"),
                7L, true, false, "oracle-cancelar-barrera");
        assertEquals("RECHAZADA", barreras.prepararBaja(rejected, "VEHICULO", 912002L));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> barreras.cerrar(rejected, "VEHICULO", 912002L)).getStatusCode().value());
        assertEquals("ABIERTA", barreras.prepararBaja(UUID.randomUUID().toString(), "VEHICULO", 912002L));
    }

    private void verifyBarrier(ConfigurableApplicationContext context) {
        var barreras = context.getBean(BarreraRecursoService.class);
        String id = UUID.randomUUID().toString();
        assertEquals("ABIERTA", barreras.prepararBaja(id, "VEHICULO", 999999999L));
        assertEquals("ABIERTA", barreras.prepararBaja(id, "VEHICULO", 999999999L));
        assertEquals(503, assertThrows(ResponseStatusException.class, () ->
                barreras.prepararBaja(UUID.randomUUID().toString(), "VEHICULO", 999999999L))
                .getStatusCode().value());
        barreras.cerrar(id, "VEHICULO", 999999999L);
        assertEquals("CERRADA", barreras.prepararBaja(id, "VEHICULO", 999999999L));
    }

    private void verifyConcurrentAssignments(ConfigurableApplicationContext context) throws Exception {
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        List<Long> ids = transaction.execute(status -> {
            var ubicaciones = context.getBean(UbicacionRepository.class).findAll();
            var estado = context.getBean(EstadoTrasladoRepository.class).findByCodigo("PENDIENTE").orElseThrow();
            var traslados = context.getBean(TrasladoRepository.class);
            var historial = context.getBean(HistorialEstadoRepository.class);
            List<Long> result = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                var traslado = traslados.saveAndFlush(new Traslado(7L, ubicaciones.get(0), ubicaciones.get(1),
                        OffsetDateTime.parse("2026-12-01T10:00:00-03:00"),
                        OffsetDateTime.parse("2026-12-01T11:00:00-03:00"), null, Set.of(11L)));
                historial.saveAndFlush(new HistorialEstado(traslado, estado, 7L, null));
                result.add(traslado.getId());
            }
            return result;
        });
        var asignaciones = context.getBean(AsignacionIdempotenteService.class);
        var request = new AsignacionRequest(301L, 401L);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return asignaciones.asignar("oracle-concurrente-a", 7L, "activa", ids.get(0), request, null);
            });
            var second = executor.submit(() -> {
                start.await();
                return asignaciones.asignar("oracle-concurrente-b", 7L, "activa", ids.get(1), request, null);
            });
            start.countDown();
            AsignacionOperacionTransacciones.Resultado a = null;
            AsignacionOperacionTransacciones.Resultado b = null;
            try {
                a = first.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException exception) {
                if (!(exception.getCause() instanceof ResponseStatusException status)
                        || status.getStatusCode().value() != 503) throw exception;
            }
            try {
                b = second.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException exception) {
                if (!(exception.getCause() instanceof ResponseStatusException status)
                        || status.getStatusCode().value() != 503) throw exception;
            }
            if (a == null) a = asignaciones.asignar("oracle-concurrente-a", 7L, "activa", ids.get(0), request, null);
            if (b == null) b = asignaciones.asignar("oracle-concurrente-b", 7L, "activa", ids.get(1), request, null);
            assertEquals(List.of(200, 409), List.of(a.codigo(), b.codigo()).stream().sorted().toList());
            var winner = a.codigo() == 200 ? a : b;
            String key = a.codigo() == 200 ? "oracle-concurrente-a" : "oracle-concurrente-b";
            Long id = a.codigo() == 200 ? ids.get(0) : ids.get(1);
            assertEquals(winner, asignaciones.asignar(key, 7L, "activa", id, request, null));
            assertEquals(3, context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM historial_estados WHERE traslado_id IN (?, ?)",
                    Integer.class, ids.get(0), ids.get(1)));

            var sameKeyStart = new CountDownLatch(1);
            var sameRequest = new AsignacionRequest(302L, 402L);
            var sameFirst = executor.submit(() -> {
                sameKeyStart.await();
                return asignaciones.asignar("oracle-misma-clave", 7L, "activa", ids.get(2), sameRequest, null);
            });
            var sameSecond = executor.submit(() -> {
                sameKeyStart.await();
                return asignaciones.asignar("oracle-misma-clave", 7L, "activa", ids.get(2), sameRequest, null);
            });
            sameKeyStart.countDown();
            var sameResult = sameFirst.get(20, TimeUnit.SECONDS);
            assertEquals(200, sameResult.codigo());
            assertEquals(sameResult, sameSecond.get(20, TimeUnit.SECONDS));
            assertEquals(2, context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM historial_estados WHERE traslado_id = ?", Integer.class, ids.get(2)));
        }
    }

    private void verifyAssignmentOperation(ConfigurableApplicationContext context, String url,
                                           String username, String password) throws SQLException {
        var operaciones = context.getBean(AsignacionOperacionTransacciones.class);
        var errorAnterior = assertThrows(ResponseStatusException.class, () ->
                context.getBean(AsignacionIdempotenteService.class).asignar(
                        "oracle-anterior", 7L, "activa", 999999999L, new AsignacionRequest(2L, 3L), null));
        assertEquals(409, errorAnterior.getStatusCode().value());
        String hash = "a".repeat(64);
        operaciones.reservar("oracle-prueba", 7L, "activa", 999999999L, hash, new AsignacionRequest(2L, 3L),
                OffsetDateTime.parse("2030-01-02T03:04:05Z"));
        assertEquals("PENDIENTE", operaciones.consultar("oracle-prueba", 7L).estado());
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        assertEquals("activa", jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_traslados WHERE clave = 'oracle-prueba'", String.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT solicitud_conductor_id FROM operaciones_traslados WHERE clave = 'oracle-prueba'", Long.class));
        assertEquals(java.time.Instant.parse("2030-01-02T03:04:05Z"), jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_traslados WHERE clave = 'oracle-prueba'",
                OffsetDateTime.class).toInstant());
        operaciones.preparar("oracle-prueba", 7L, 999999999L, new AsignacionRequest(2L, 3L), hash);
        var resultado = operaciones.procesar("oracle-prueba", 7L, 999999999L,
                new AsignacionRequest(2L, 3L), hash);
        assertEquals(404, resultado.codigo());
        assertNull(jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_traslados WHERE clave = 'oracle-prueba'", String.class));
        assertNull(jdbc.queryForObject(
                "SELECT solicitud_conductor_id FROM operaciones_traslados WHERE clave = 'oracle-prueba'", Long.class));
        assertNull(jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_traslados WHERE clave = 'oracle-prueba'", OffsetDateTime.class));
        assertEquals(resultado.cuerpo(), operaciones.procesar("oracle-prueba", 7L, 999999999L,
                new AsignacionRequest(2L, 3L), hash).cuerpo());
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE operaciones_traslados SET expira_en = SYSTIMESTAMP - INTERVAL '1' DAY "
                    + "WHERE clave = 'oracle-prueba'");
        }
        operaciones.limpiarRespuestasExpiradas();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT estado_operacion, actor_id, respuesta_json FROM operaciones_traslados "
                             + "WHERE clave = 'oracle-prueba'")) {
            assertTrue(result.next());
            assertEquals("EXPIRADA", result.getString(1));
            assertNull(result.getObject(2));
            assertNull(result.getString(3));
        }
    }

    private void verifyConstraints(String url, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM operaciones_traslados WHERE clave = 'legacy-pendiente' "
                    + "AND estado_operacion = 'PENDIENTE' AND sesion_original IS NULL "
                    + "AND solicitud_conductor_id IS NULL AND solicitud_vehiculo_id IS NULL "
                    + "AND jwt_original_expira_en IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM operaciones_traslados WHERE clave = 'sin-vencimiento' "
                    + "AND sesion_original = 'sesion' AND jwt_original_expira_en IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE operaciones_traslados SET jwt_original_expira_en = SYSTIMESTAMP "
                    + "WHERE clave = 'legacy-pendiente'")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO operaciones_traslados (clave, operacion, respuesta_codigo, sesion_original) "
                    + "VALUES ('incompleta', 'asignar', 102, 'sesion')")).getErrorCode());
            try (ResultSet result = statement.executeQuery("SELECT codigo_estado FROM estados_traslado ORDER BY codigo_estado")) {
                List<String> estados = new ArrayList<>();
                while (result.next()) {
                    estados.add(result.getString(1));
                }
                assertEquals(List.of("ASIGNADO", "CANCELADO", "EN_CURSO", "FINALIZADO", "PENDIENTE"), estados);
            }
            statement.executeUpdate("INSERT INTO ubicaciones (comuna_id, direccion_texto) VALUES (1, 'Origen de prueba')");
            statement.executeUpdate("INSERT INTO ubicaciones (comuna_id, direccion_texto, latitud, longitud) "
                    + "VALUES (2, 'Destino de prueba', -33.45, -70.66)");
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO ubicaciones (comuna_id, direccion_texto, latitud) VALUES (1, 'Incompleta', 1)")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO ubicaciones (comuna_id, direccion_texto, latitud, longitud) VALUES (1, 'Invalida', 91, 0)")).getErrorCode());

            statement.executeUpdate("INSERT INTO traslados (solicitante_id, origen_ubicacion_id, destino_ubicacion_id, "
                    + "fecha_hora_viaje, fecha_hora_termino_estimado) "
                    + "SELECT 1, o.id_ubicacion, d.id_ubicacion, "
                    + "TO_TIMESTAMP_TZ('2026-11-20 10:00 -03:00', 'YYYY-MM-DD HH24:MI TZH:TZM'), "
                    + "TO_TIMESTAMP_TZ('2026-11-20 11:00 -03:00', 'YYYY-MM-DD HH24:MI TZH:TZM') "
                    + "FROM ubicaciones o CROSS JOIN ubicaciones d "
                    + "WHERE o.direccion_texto = 'Origen de prueba' AND d.direccion_texto = 'Destino de prueba'");
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM traslados "
                    + "WHERE SYS_EXTRACT_UTC(fecha_hora_viaje) = TIMESTAMP '2026-11-20 13:00:00'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE traslados SET conductor_id = 1")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE traslados SET fecha_hora_termino_estimado = fecha_hora_viaje")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE traslados SET destino_ubicacion_id = origen_ubicacion_id")).getErrorCode());
            try (ResultSet result = statement.executeQuery("SELECT estado_actual FROM v_traslados_estado_actual")) {
                assertTrue(result.next());
                assertNull(result.getString(1));
            }

            statement.executeUpdate("INSERT INTO historial_estados (traslado_id, estado_id, usuario_id, fecha_cambio) "
                    + "SELECT t.id_traslado, e.id_estado, 1, TO_TIMESTAMP_TZ('2026-11-20 10:00 -03:00', "
                    + "'YYYY-MM-DD HH24:MI TZH:TZM') FROM traslados t CROSS JOIN estados_traslado e "
                    + "WHERE e.codigo_estado = 'PENDIENTE'");
            statement.executeUpdate("INSERT INTO historial_estados (traslado_id, estado_id, usuario_id, fecha_cambio) "
                    + "SELECT t.id_traslado, e.id_estado, 1, TO_TIMESTAMP_TZ('2026-11-20 10:00 -03:00', "
                    + "'YYYY-MM-DD HH24:MI TZH:TZM') FROM traslados t CROSS JOIN estados_traslado e "
                    + "WHERE e.codigo_estado = 'ASIGNADO'");
            try (ResultSet result = statement.executeQuery("SELECT estado_actual FROM v_traslados_estado_actual")) {
                assertTrue(result.next());
                assertEquals("ASIGNADO", result.getString(1));
            }
            statement.executeUpdate("UPDATE traslados SET fecha_actualizacion = "
                    + "TO_TIMESTAMP_TZ('2000-01-01 00:00 +00:00', 'YYYY-MM-DD HH24:MI TZH:TZM')");
            try (ResultSet result = statement.executeQuery("SELECT EXTRACT(YEAR FROM fecha_actualizacion) FROM traslados")) {
                assertTrue(result.next());
                assertTrue(result.getInt(1) > 2000);
            }
            connection.rollback();
        }
    }

    private void verifyRepositories(ConfigurableApplicationContext context, String url, String username,
                                    String password) throws SQLException {
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            UbicacionRepository ubicaciones = context.getBean(UbicacionRepository.class);
            Ubicacion origen = ubicaciones.save(new Ubicacion(1L, "Origen JPA", null, null));
            Ubicacion destino = ubicaciones.save(new Ubicacion(2L, "Destino JPA", null, null));
            Traslado traslado = context.getBean(TrasladoRepository.class).saveAndFlush(new Traslado(
                    1L, origen, destino, OffsetDateTime.parse("2026-11-20T10:00:00-03:00"),
                    OffsetDateTime.parse("2026-11-20T11:00:00-03:00"), null, Set.of(11L, 12L)));
            assertTrue(traslado.getId() > 0);
            assertTrue(traslado.getFechaCreacion() != null);
            var pendiente = context.getBean(EstadoTrasladoRepository.class).findByCodigo("PENDIENTE").orElseThrow();
            context.getBean(HistorialEstadoRepository.class).saveAndFlush(new HistorialEstado(traslado, pendiente, 1L, null));
        });
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                    "SELECT estado_actual, (SELECT COUNT(*) FROM traslados_pasajeros) FROM v_traslados_estado_actual")) {
            assertTrue(result.next());
            assertEquals("PENDIENTE", result.getString(1));
            assertEquals(2, result.getInt(2));
        }
    }
}
