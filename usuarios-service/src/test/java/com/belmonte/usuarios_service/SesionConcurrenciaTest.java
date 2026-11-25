package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.service.JwtService;
import com.belmonte.usuarios_service.service.CambioUsuarioTransacciones;
import com.belmonte.usuarios_service.service.UsuarioService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "jwt.secret=test-jwt-secret-not-for-deployment",
        "internal.api.secret=test-internal-secret",
        "spring.datasource.url=jdbc:h2:mem:sesionconcurrencia;DB_CLOSE_DELAY=-1"
})
class SesionConcurrenciaTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UsuarioService usuarios;
    @Autowired private JwtService jwtService;
    @Autowired private CambioUsuarioTransacciones cambios;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void loginEsperaDesactivacionYNoEmiteSesion() throws Exception {
        Fixture fixture = crearUsuario();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> login = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                usuarioRepository.findForUpdate(fixture.usuarioId()).orElseThrow();
                login.set(executor.submit(() -> {
                    started.countDown();
                    return usuarios.iniciarSesion(fixture.email(), "password");
                }));
                esperarInicio(started);
                assertThrows(TimeoutException.class, () -> login.get().get(500, TimeUnit.MILLISECONDS));
                aplicarCambio(fixture.usuarioId(), false, null);
            });
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> login.get().get(5, TimeUnit.SECONDS));
            assertEquals(401, assertInstanceOf(ResponseStatusException.class, failure.getCause())
                    .getStatusCode().value());
            assertEquals(0, sesionesActivas(fixture.usuarioId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cambioDeRolEsperaLoginYRevocaLaSesionEmitida() throws Exception {
        Fixture fixture = crearUsuario();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> cambio = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                usuarios.iniciarSesion(fixture.email(), "password");
                cambio.set(executor.submit(() -> {
                    started.countDown();
                    aplicarCambio(fixture.usuarioId(), null, fixture.otroRolId());
                }));
                esperarInicio(started);
                assertThrows(TimeoutException.class, () -> cambio.get().get(500, TimeUnit.MILLISECONDS));
            });
            cambio.get().get(5, TimeUnit.SECONDS);
            assertEquals(0, sesionesActivas(fixture.usuarioId()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sesiones_usuario WHERE usuario_id = ?",
                    Integer.class, fixture.usuarioId()));
            assertEquals(fixture.otroRolId(), jdbc.queryForObject(
                    "SELECT rol_id FROM usuarios WHERE id_usuario = ?", Long.class, fixture.usuarioId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void loginEsperaCambioDeRolYEmiteTokenConRolActualizado() throws Exception {
        Fixture fixture = crearUsuario();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Map<String, Object>>> login = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                usuarioRepository.findForUpdate(fixture.usuarioId()).orElseThrow();
                login.set(executor.submit(() -> {
                    started.countDown();
                    return usuarios.iniciarSesion(fixture.email(), "password");
                }));
                esperarInicio(started);
                assertThrows(TimeoutException.class, () -> login.get().get(500, TimeUnit.MILLISECONDS));
                aplicarCambio(fixture.usuarioId(), null, fixture.otroRolId());
            });
            Map<String, Object> respuesta = login.get().get(5, TimeUnit.SECONDS);
            Map<?, ?> usuario = assertInstanceOf(Map.class, respuesta.get("usuario"));
            String rolActual = jdbc.queryForObject("SELECT nombre_rol FROM roles WHERE id_rol = ?",
                    String.class, fixture.otroRolId());
            assertEquals(rolActual, usuario.get("rol"));
            assertEquals(List.of(rolActual), jwtService.parseToken((String) respuesta.get("accessToken")).get("roles"));
            assertEquals(1, sesionesActivas(fixture.usuarioId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logoutEsperaBloqueoDeUsuarioYRevocaSoloSuSesion() throws Exception {
        Fixture fixture = crearUsuario();
        Map<String, Object> primera = usuarios.iniciarSesion(fixture.email(), "password");
        usuarios.iniciarSesion(fixture.email(), "password");
        String sesion = jwtService.parseToken((String) primera.get("accessToken")).get("sessionId", String.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> logout = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                usuarioRepository.findForUpdate(fixture.usuarioId()).orElseThrow();
                logout.set(executor.submit(() -> {
                    started.countDown();
                    usuarios.cerrarSesion(sesion);
                }));
                esperarInicio(started);
                assertThrows(TimeoutException.class, () -> logout.get().get(500, TimeUnit.MILLISECONDS));
                assertEquals(2, sesionesActivas(fixture.usuarioId()));
            });
            logout.get().get(5, TimeUnit.SECONDS);
            assertEquals(1, sesionesActivas(fixture.usuarioId()));
            assertEquals(0, jdbc.queryForObject("SELECT activa FROM sesiones_usuario WHERE sesion_id = ?",
                    Integer.class, sesion));
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture crearUsuario() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = suffix + "@example.invalid";
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES (?)", "Pais " + suffix);
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES (?)", suffix);
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES (?)", "ROL_A_" + suffix);
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES (?)", "ROL_B_" + suffix);
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = ?", Long.class, "Pais " + suffix);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = ?", Long.class, suffix);
        Long rol = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, "ROL_A_" + suffix);
        Long otroRol = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, "ROL_B_" + suffix);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) VALUES (?, ?, ?, 'Prueba', ?, CURRENT_TIMESTAMP)",
                pais, tipo, suffix, email);
        Long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, ?, 1)",
                persona, rol, new BCryptPasswordEncoder().encode("password"));
        Long usuario = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, persona);
        return new Fixture(usuario, email, otroRol);
    }

    private int sesionesActivas(Long usuarioId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sesiones_usuario WHERE usuario_id = ? AND activa = 1",
                Integer.class, usuarioId);
    }

    private void aplicarCambio(Long usuarioId, Boolean activo, Long rolId) {
        String clave = UUID.randomUUID().toString();
        String hash = UUID.randomUUID().toString();
        cambios.reservar(clave, 1L, UUID.randomUUID().toString(), usuarioId, hash,
                UUID.randomUUID().toString(), activo, rolId, null);
        cambios.aplicar(clave, 1L, usuarioId, hash, activo, rolId);
    }

    private void esperarInicio(CountDownLatch started) {
        try {
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("La tarea concurrente no inicio");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Fixture(Long usuarioId, String email, Long otroRolId) {}
}
