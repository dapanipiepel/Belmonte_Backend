package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.security.TokenUtil;
import com.belmonte.usuarios_service.service.RecuperacionPasswordService;
import com.belmonte.usuarios_service.service.RelojBaseDatos;
import com.belmonte.usuarios_service.service.UsuarioService;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "jwt.secret=test-jwt-secret-not-for-deployment",
        "internal.api.secret=test-internal-secret",
        "spring.datasource.url=jdbc:h2:mem:recuperaciontest;DB_CLOSE_DELAY=-1"
})
class RecuperacionPasswordServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecuperacionPasswordService recuperaciones;
    @Autowired private UsuarioService usuarios;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private TokenUtil tokens;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private RelojBaseDatos reloj;
    @MockitoSpyBean private SesionUsuarioRepository sesiones;

    @Test
    void soloUltimoEnlaceCambiaPasswordUnaVezYRevocaSesiones() {
        Fixture fixture = crearUsuario();
        usuarios.iniciarSesion(fixture.email(), "password");
        usuarios.iniciarSesion(fixture.email(), "password");
        var primero = recuperaciones.emitir(fixture.id());
        var ultimo = recuperaciones.emitir(fixture.id());
        assertFalse(ultimo.toString().contains(ultimo.token()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recuperaciones_password WHERE token_hash = ?",
                Integer.class, tokens.hashTokenSHA256(ultimo.token())));
        assertThrows(ResponseStatusException.class, () -> recuperaciones.cambiarPassword(primero.token(), "nuevo"));
        recuperaciones.cambiarPassword(ultimo.token(), "nuevo");
        assertThrows(ResponseStatusException.class, () -> recuperaciones.cambiarPassword(ultimo.token(), "otro"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sesiones_usuario WHERE usuario_id = ? AND activa = 1",
                Integer.class, fixture.id()));
        String hash = jdbc.queryForObject("SELECT password FROM usuarios WHERE id_usuario = ?", String.class, fixture.id());
        assertNotEquals("nuevo", hash);
        assertTrue(new BCryptPasswordEncoder().matches("nuevo", hash));
        assertFalse(new BCryptPasswordEncoder().matches("password", hash));
    }

    @Test
    void falloAlRevocarReviertePasswordYConsumo() {
        Fixture fixture = crearUsuario();
        usuarios.iniciarSesion(fixture.email(), "password");
        var enlace = recuperaciones.emitir(fixture.id());
        doThrow(new DataAccessResourceFailureException("Fallo simulado"))
                .when(sesiones).revokeAllByUsuarioId(fixture.id());
        try {
            assertThrows(DataAccessResourceFailureException.class,
                    () -> recuperaciones.cambiarPassword(enlace.token(), "nuevo"));
        } finally {
            reset(sesiones);
        }
        String hash = jdbc.queryForObject("SELECT password FROM usuarios WHERE id_usuario = ?", String.class, fixture.id());
        assertTrue(new BCryptPasswordEncoder().matches("password", hash));
        assertEquals(0, jdbc.queryForObject("SELECT usada FROM recuperaciones_password WHERE token_hash = ?",
                Integer.class, tokens.hashTokenSHA256(enlace.token())));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sesiones_usuario WHERE usuario_id = ? AND activa = 1",
                Integer.class, fixture.id()));
        recuperaciones.cambiarPassword(enlace.token(), "nuevo");
    }

    @Test
    void rechazaVencimientoLimiteYContrasenaDemasiadoLargaSinConsumir() {
        Fixture fixture = crearUsuario();
        var enlace = recuperaciones.emitir(fixture.id());
        assertThrows(ResponseStatusException.class,
                () -> recuperaciones.cambiarPassword(enlace.token(), "ñ".repeat(37)));
        doReturn(enlace.fechaExpiracion()).when(reloj).ahora();
        try {
            assertThrows(ResponseStatusException.class,
                    () -> recuperaciones.cambiarPassword(enlace.token(), "nuevo"));
        } finally {
            reset(reloj);
        }
        assertEquals(0, jdbc.queryForObject("SELECT usada FROM recuperaciones_password WHERE token_hash = ?",
                Integer.class, tokens.hashTokenSHA256(enlace.token())));
        recuperaciones.cambiarPassword(enlace.token(), "nuevo");
    }

    @Test
    void emisionConcurrenteInvalidaEnlaceAntesDePoderConsumirlo() throws Exception {
        Fixture fixture = crearUsuario();
        var anterior = recuperaciones.emitir(fixture.id());
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            var future = new TransactionTemplate(transactionManager).execute(status -> {
                usuarioRepository.findForUpdate(fixture.id()).orElseThrow();
                var consumo = executor.submit(() -> {
                    inicio.countDown();
                    recuperaciones.cambiarPassword(anterior.token(), "anterior");
                });
                try {
                    assertTrue(inicio.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> consumo.get(500, TimeUnit.MILLISECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                recuperaciones.emitir(fixture.id());
                return consumo;
            });
            ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof ResponseStatusException);
            String hash = jdbc.queryForObject("SELECT password FROM usuarios WHERE id_usuario = ?", String.class, fixture.id());
            assertTrue(new BCryptPasswordEncoder().matches("password", hash));
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture crearUsuario() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = suffix + "@example.invalid";
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES (?)", "Pais " + suffix);
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES (?)", suffix);
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES (?)", "ROL_" + suffix);
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = ?", Long.class, "Pais " + suffix);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = ?", Long.class, suffix);
        Long rol = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, "ROL_" + suffix);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) VALUES (?, ?, ?, 'Prueba', ?, CURRENT_TIMESTAMP)",
                pais, tipo, suffix, email);
        Long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, ?, 1)",
                persona, rol, new BCryptPasswordEncoder().encode("password"));
        Long id = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, persona);
        return new Fixture(id, email);
    }

    private record Fixture(Long id, String email) {}
}
