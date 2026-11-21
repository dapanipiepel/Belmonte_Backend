package com.belmonte.viajes_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
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

import com.belmonte.viajes_service.model.HistorialEstado;
import com.belmonte.viajes_service.model.Traslado;
import com.belmonte.viajes_service.model.Ubicacion;
import com.belmonte.viajes_service.repository.EstadoTrasladoRepository;
import com.belmonte.viajes_service.repository.HistorialEstadoRepository;
import com.belmonte.viajes_service.repository.TrasladoRepository;
import com.belmonte.viajes_service.repository.UbicacionRepository;
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

@EnabledIfEnvironmentVariable(named = "BELMONTE_ORACLE_TEST_URL", matches = ".+")
class ViajesOracleMigrationTest {

    @Test
    void migratesAnEmptyTestSchemaAndEnforcesItsConstraints() throws SQLException {
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

        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("oracleTest", Map.of(
                "VIAJES_DB_URL", url, "VIAJES_DB_USER", username, "VIAJES_DB_PASSWORD", password)));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ViajesServiceApplication.class)
                .environment(environment).profiles("oracle").web(WebApplicationType.NONE).run()) {
            Flyway flyway = Flyway.configure().dataSource(url, username, password).load();
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful);
            verifyConstraints(url, username, password);
            verifyRepositories(context, url, username, password);
        }
    }

    private void verifyConstraints(String url, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
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
