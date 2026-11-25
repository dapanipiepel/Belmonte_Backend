package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DecisionTiempoMigrationTest {
    @Test
    void migracionConservaAutorizacionesAntiguasYRestringeLaCota() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE decisiones_autorizacion (
                    decision_id VARCHAR(121) PRIMARY KEY,
                    estado VARCHAR(20) NOT NULL,
                    jwt_expira_en TIMESTAMP WITH TIME ZONE NOT NULL,
                    sesion_expira_instante TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.update("""
                INSERT INTO decisiones_autorizacion VALUES
                ('antigua', 'AUTORIZADA', TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:05+00:00',
                 TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:04+00:00'),
                ('preparada', 'PREPARADA', TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:05+00:00',
                 TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:04+00:00')
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V9__prueba_tiempo_decision.sql"))
                .execute(dataSource);

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM decisiones_autorizacion
                WHERE decision_id = 'antigua' AND tiempo_limite_superior IS NULL
                """, Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE decisiones_autorizacion SET tiempo_limite_superior = sesion_expira_instante
                WHERE decision_id = 'antigua'
                """));
        jdbc.update("""
                UPDATE decisiones_autorizacion SET sesion_expira_instante =
                TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:06+00:00' WHERE decision_id = 'antigua'
                """);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE decisiones_autorizacion SET tiempo_limite_superior = jwt_expira_en
                WHERE decision_id = 'antigua'
                """));
        jdbc.update("UPDATE decisiones_autorizacion SET sesion_expira_instante = NULL WHERE decision_id = 'antigua'");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE decisiones_autorizacion SET tiempo_limite_superior =
                TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:03+00:00' WHERE decision_id = 'antigua'
                """));
        jdbc.update("""
                UPDATE decisiones_autorizacion SET sesion_expira_instante =
                TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:04+00:00' WHERE decision_id = 'antigua'
                """);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE decisiones_autorizacion SET tiempo_limite_superior =
                TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:03+00:00' WHERE decision_id = 'preparada'
                """));
        assertEquals(1, jdbc.update("""
                UPDATE decisiones_autorizacion SET tiempo_limite_superior =
                TIMESTAMP WITH TIME ZONE '2030-01-02 03:04:03+00:00' WHERE decision_id = 'antigua'
                """));
    }
}
