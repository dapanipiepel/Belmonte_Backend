package com.belmonte.vehiculos_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;

import com.belmonte.vehiculos_service.dto.VehiculoRequest;
import com.belmonte.vehiculos_service.repository.ModeloVehiculoRepository;
import com.belmonte.vehiculos_service.repository.TipoVehiculoRepository;
import com.belmonte.vehiculos_service.repository.VehiculoRepository;
import com.belmonte.vehiculos_service.service.VehiculoService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@EnabledIfEnvironmentVariable(named = "BELMONTE_ORACLE_TEST_URL", matches = ".+")
class VehiculosOracleMigrationTest {

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
                "VEHICULOS_DB_URL", url,
                "VEHICULOS_DB_USER", username,
                "VEHICULOS_DB_PASSWORD", password)));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(VehiculosServiceApplication.class)
                .environment(environment)
                .profiles("oracle")
                .web(WebApplicationType.NONE)
                .run()) {
            assertEquals(0, context.getBean(VehiculoRepository.class).count());
            Flyway flyway = Flyway.configure().dataSource(url, username, password).load();
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful);
            verifyConstraintsAndRepository(url, username, password, context);
        }
    }

    private void verifyConstraintsAndRepository(String url, String username, String password,
                                                ConfigurableApplicationContext context) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
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
