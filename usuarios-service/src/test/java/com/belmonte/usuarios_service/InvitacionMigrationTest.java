package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class InvitacionMigrationTest {
    @Test
    void migracionConservaInvitacionesAnterioresYRestringeLaPersona() {
        var db = new SingleConnectionDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "", true);
        var jdbc = new JdbcTemplate(db);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__usuarios_y_catalogos.sql"),
                new ClassPathResource("db/migration/V11__invitaciones_y_recuperacion.sql")).execute(db);
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES ('Pais')");
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES ('DOC')");
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES ('ADMINISTRADOR')");
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email) "
                + "SELECT id_pais, id_tipo_documento, '1', 'Persona', 'p@example.invalid' FROM paises, tipos_documento");
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password) SELECT id_persona, id_rol, 'hash' FROM personas, roles");
        jdbc.update("INSERT INTO invitaciones (email_destinatario, rol_id, creado_por_usuario_id, token_hash, fecha_creacion, fecha_expiracion) "
                + "SELECT 'p@example.invalid', id_rol, id_usuario, 'hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '2' DAY FROM roles, usuarios");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V12__invitaciones_persona.sql")).execute(db);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM invitaciones WHERE persona_id IS NULL", Integer.class));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE invitaciones SET persona_id = 99999"));
        assertEquals(1, jdbc.update("UPDATE invitaciones SET persona_id = (SELECT id_persona FROM personas)"));
        db.destroy();
    }
}
