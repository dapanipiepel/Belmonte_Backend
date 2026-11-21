package com.belmonte.usuarios_service;

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

import com.belmonte.usuarios_service.repository.PersonaRepository;
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
class UsuariosOracleMigrationTest {

    @Test
    void migratesAnEmptyTestSchemaAndEnforcesItsConstraints() throws SQLException {
        String url = System.getenv("BELMONTE_ORACLE_TEST_URL");
        String username = Objects.requireNonNull(System.getenv("BELMONTE_ORACLE_TEST_USER"));
        String password = Objects.requireNonNull(System.getenv("BELMONTE_ORACLE_TEST_PASSWORD"));

        assertTrue(username.matches("BMTEST_[A-F0-9]{12}"), "Use a dedicated temporary test account");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "SELECT SYS_CONTEXT('USERENV', 'CURRENT_USER'), (SELECT COUNT(*) FROM USER_OBJECTS) FROM DUAL")) {
                assertTrue(result.next());
                assertEquals(username, result.getString(1));
                assertEquals(0, result.getInt(2), "The test schema must be empty");
            }
        }

        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("oracleTest", Map.of(
                "USUARIOS_DB_URL", url,
                "USUARIOS_DB_USER", username,
                "USUARIOS_DB_PASSWORD", password)));

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(UsuariosServiceApplication.class)
                .environment(environment)
                .profiles("oracle")
                .web(WebApplicationType.NONE)
                .run()) {
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
                    + "('PAISES', 'REGIONES', 'COMUNAS', 'EMPRESAS', 'ROLES', "
                    + "'TIPOS_DOCUMENTO', 'PERSONAS', 'USUARIOS', 'PASAJEROS')")) {
                assertTrue(result.next());
                assertEquals(9, result.getInt(1));
            }

            statement.executeUpdate("INSERT INTO paises (nombre_pais) VALUES ('Pais de prueba')");
            statement.executeUpdate("INSERT INTO tipos_documento (codigo_tipo) VALUES ('PRUEBA')");
            statement.executeUpdate("INSERT INTO roles (nombre_rol) VALUES ('SOLICITANTE')");
            statement.executeUpdate("INSERT INTO empresas (rut_empresa, razon_social) VALUES ('TEST-1', 'Empresa de prueba')");
            statement.executeUpdate("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, telefono, email) "
                    + "SELECT p.id_pais, t.id_tipo_documento, 'TEST-1', 'Persona de prueba', '123', 'prueba@example.invalid' "
                    + "FROM paises p CROSS JOIN tipos_documento t");
            statement.executeUpdate("INSERT INTO usuarios (persona_id, rol_id, password) "
                    + "SELECT p.id_persona, r.id_rol, 'test-only' FROM personas p CROSS JOIN roles r");
            statement.executeUpdate("INSERT INTO pasajeros (persona_id, empresa_id) "
                    + "SELECT p.id_persona, e.id_empresa FROM personas p CROSS JOIN empresas e");

            assertEquals(1, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO paises (nombre_pais) VALUES ('Pais de prueba')")).getErrorCode());
            assertEquals(2291, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO regiones (pais_id, nombre_region) VALUES (999999, 'Region invalida')")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE usuarios SET activo = 2")).getErrorCode());
            assertEquals(1400, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO pasajeros (persona_id, empresa_id) SELECT id_persona, NULL FROM personas")).getErrorCode());
            connection.commit();
            assertTrue(context.getBean(PersonaRepository.class).existsByEmail("prueba@example.invalid"));
        }
    }
}
