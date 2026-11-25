package com.belmonte.vehiculos_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
import com.belmonte.vehiculos_service.dto.VehiculoRequest;
import com.belmonte.vehiculos_service.repository.ModeloVehiculoRepository;
import com.belmonte.vehiculos_service.repository.TipoVehiculoRepository;
import com.belmonte.vehiculos_service.repository.VehiculoRepository;
import com.belmonte.vehiculos_service.service.VehiculoService;
import com.belmonte.vehiculos_service.service.EstadoVehiculoIdempotenteService;
import com.belmonte.vehiculos_service.service.EstadoVehiculoTransacciones;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "BELMONTE_ORACLE_TEST_URL", matches = ".+")
class VehiculosOracleMigrationTest {

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
            statement.executeUpdate("INSERT INTO operaciones_estado_vehiculo "
                    + "(clave, actor_id, vehiculo_id, solicitud_hash, barrera_id, proposito, estado) "
                    + "VALUES ('legacy-pendiente', 7, 1, 'hash', 'legacy', 'BAJA', 'PENDIENTE')");
        }
        Flyway.configure().dataSource(url, username, password).target("3").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO operaciones_estado_vehiculo "
                    + "(clave, estado, sesion_original, solicitud_activo) "
                    + "VALUES ('sin-vencimiento', 'PENDIENTE', 'sesion', 0)");
        }
        HttpServer viajes = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> barreras = new ConcurrentHashMap<>();
        AtomicBoolean cierreNoDisponible = new AtomicBoolean();
        viajes.createContext("/internal/barreras/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean cierre = path.endsWith("/cerrar");
            String id = path.substring("/internal/barreras/".length()).replace("/cerrar", "");
            if (cierre && cierreNoDisponible.get()) {
                exchange.sendResponseHeaders(503, -1);
            } else if (cierre) {
                barreras.put(id, "CERRADA");
                exchange.sendResponseHeaders(204, -1);
            } else {
                String estado = barreras.computeIfAbsent(id, ignored -> "ABIERTA");
                byte[] body = ("{\"estado\":\"" + estado + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        viajes.start();
        try {
            ConfigurableEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("oracleTest", Map.of(
                    "VEHICULOS_DB_URL", url,
                    "VEHICULOS_DB_USER", username,
                    "VEHICULOS_DB_PASSWORD", password,
                    "server.port", "0",
                    "viajes.service.uri", "http://127.0.0.1:" + viajes.getAddress().getPort())));
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(VehiculosServiceApplication.class)
                    .environment(environment)
                    .profiles("oracle")
                    .web(WebApplicationType.SERVLET)
                    .run()) {
                assertEquals(0, context.getBean(VehiculoRepository.class).count());
                Flyway flyway = Flyway.configure().dataSource(url, username, password).load();
                assertEquals(0, flyway.migrate().migrationsExecuted);
                assertEquals("4", flyway.info().current().getVersion().getVersion());
                assertTrue(flyway.validateWithResult().validationSuccessful);
                verifyConstraintsAndRepository(url, username, password, context);
                verifyCoordinatedState(context, cierreNoDisponible);
            }
        } finally {
            viajes.stop(0);
        }
    }

    private void verifyCoordinatedState(ConfigurableApplicationContext context, AtomicBoolean cierreNoDisponible) throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        Long id = jdbc.queryForObject("SELECT id_vehiculo FROM vehiculos WHERE patente = 'TEST-1'", Long.class);
        var estados = context.getBean(EstadoVehiculoIdempotenteService.class);
        var transacciones = context.getBean(EstadoVehiculoTransacciones.class);
        transacciones.reservar("oracle-intencion", 7L, "activa", id, "hash",
                java.util.UUID.randomUUID().toString(), "CAMBIO_COMPATIBLE", true,
                OffsetDateTime.parse("2030-01-02T03:04:05Z"));
        assertEquals("activa", jdbc.queryForObject(
                "SELECT sesion_original FROM operaciones_estado_vehiculo WHERE clave = 'oracle-intencion'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT solicitud_activo FROM operaciones_estado_vehiculo WHERE clave = 'oracle-intencion'", Integer.class));
        assertEquals(java.time.Instant.parse("2030-01-02T03:04:05Z"), jdbc.queryForObject(
                "SELECT jwt_original_expira_en FROM operaciones_estado_vehiculo WHERE clave = 'oracle-intencion'",
                OffsetDateTime.class).toInstant());
        transacciones.aplicar("oracle-intencion", 7L, id, "hash", true);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM operaciones_estado_vehiculo WHERE clave = 'oracle-intencion' "
                + "AND sesion_original IS NULL AND solicitud_activo IS NULL AND jwt_original_expira_en IS NULL", Integer.class));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                start.await();
                return estados.cambiar("oracle-estado", 7L, "activa", id, false, null);
            });
            var two = executor.submit(() -> {
                start.await();
                return estados.cambiar("oracle-estado", 7L, "activa", id, false, null);
            });
            start.countDown();
            var a = one.get(20, TimeUnit.SECONDS);
            var b = two.get(20, TimeUnit.SECONDS);
            assertEquals(200, a.codigo());
            assertEquals(a, b);
        }
        assertEquals(200, estados.cambiar("oracle-activar", 7L, "activa", id, true, null).codigo());
        assertTrue(context.getBean(VehiculoRepository.class).findById(id).orElseThrow().getActivo());
        assertTrue(estados.cambiar("oracle-estado", 7L, "activa", id, false, null).cuerpo().contains("\"activo\":false"));
        jdbc.update("UPDATE operaciones_estado_vehiculo SET expira_en = ? WHERE clave = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), "oracle-estado");
        context.getBean(EstadoVehiculoTransacciones.class).limpiarRespuestasExpiradas();
        assertEquals("EXPIRADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "oracle-estado"));
        assertEquals(409, estados.cambiar("oracle-estado", 7L, "activa", id, false, null).codigo());
        cierreNoDisponible.set(true);
        assertEquals(503, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> estados.cambiar("oracle-recuperar", 7L, "activa", id, false, null)).getStatusCode().value());
        assertEquals("APLICADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "oracle-recuperar"));
        cierreNoDisponible.set(false);
        estados.recuperarAplicadas();
        assertEquals("COMPLETADA", jdbc.queryForObject(
                "SELECT estado FROM operaciones_estado_vehiculo WHERE clave = ?", String.class, "oracle-recuperar"));
    }

    private void verifyConstraintsAndRepository(String url, String username, String password,
                                                ConfigurableApplicationContext context) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM operaciones_estado_vehiculo WHERE clave = 'legacy-pendiente' "
                    + "AND estado = 'PENDIENTE' AND sesion_original IS NULL AND solicitud_activo IS NULL "
                    + "AND jwt_original_expira_en IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM operaciones_estado_vehiculo WHERE clave = 'sin-vencimiento' "
                    + "AND sesion_original = 'sesion' AND jwt_original_expira_en IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE operaciones_estado_vehiculo SET jwt_original_expira_en = SYSTIMESTAMP "
                    + "WHERE clave = 'legacy-pendiente'")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO operaciones_estado_vehiculo (clave, estado, sesion_original) "
                    + "VALUES ('incompleta', 'PENDIENTE', 'sesion')")).getErrorCode());
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN "
                    + "('TIPOS_VEHICULO', 'MARCAS_VEHICULO', 'MODELOS_VEHICULO', 'VEHICULOS')")) {
                assertTrue(result.next());
                assertEquals(4, result.getInt(1));
            }

            statement.executeUpdate("INSERT INTO tipos_vehiculo (codigo_tipo, descripcion) VALUES ('TEST', 'Tipo de prueba')");
            statement.executeUpdate("INSERT INTO marcas_vehiculo (nombre_marca) VALUES ('Marca de prueba')");
            statement.executeUpdate("INSERT INTO modelos_vehiculo (marca_id, nombre_modelo) "
                    + "SELECT id_marca, 'Modelo de prueba' FROM marcas_vehiculo");
            statement.executeUpdate("INSERT INTO vehiculos (tipo_vehiculo_id, modelo_id, patente, anio, capacidad_pasajeros) "
                    + "SELECT t.id_tipo_vehiculo, m.id_modelo, 'TEST-1', 2025, 4 "
                    + "FROM tipos_vehiculo t CROSS JOIN modelos_vehiculo m");

            assertEquals(1, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO vehiculos (tipo_vehiculo_id, modelo_id, patente, anio, capacidad_pasajeros) "
                    + "SELECT tipo_vehiculo_id, modelo_id, patente, anio, capacidad_pasajeros FROM vehiculos")).getErrorCode());
            assertEquals(2291, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO modelos_vehiculo (marca_id, nombre_modelo) VALUES (999999, 'Modelo invalido')")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE vehiculos SET capacidad_pasajeros = 0")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE vehiculos SET activo = 2")).getErrorCode());
            connection.commit();
            assertEquals(1, context.getBean(VehiculoRepository.class).findByActivoTrue().size());
            Long tipoId = context.getBean(TipoVehiculoRepository.class).findAll().get(0).getId();
            Long modeloId = context.getBean(ModeloVehiculoRepository.class).findAll().get(0).getId();
            var creado = context.getBean(VehiculoService.class).guardar(
                    new VehiculoRequest(tipoId, modeloId, "TEST-2", 2025, "Azul", 5, true));
            assertTrue(creado.getId() > 0);
            assertEquals(2, context.getBean(VehiculoRepository.class).findByActivoTrue().size());
        }
    }
}
