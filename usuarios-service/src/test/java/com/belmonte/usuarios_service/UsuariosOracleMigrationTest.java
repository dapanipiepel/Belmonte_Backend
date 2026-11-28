package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;

import com.belmonte.usuarios_service.repository.PersonaRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.repository.InvitacionRepository;
import com.belmonte.usuarios_service.repository.RecuperacionPasswordRepository;
import com.belmonte.usuarios_service.model.Invitacion;
import com.belmonte.usuarios_service.model.RecuperacionPassword;
import com.belmonte.usuarios_service.service.CambioUsuarioTransacciones;
import com.belmonte.usuarios_service.service.ComunaService;
import com.belmonte.usuarios_service.service.EmpresaService;
import com.belmonte.usuarios_service.service.PasajeroService;
import com.belmonte.usuarios_service.dto.EmpresaRequest;
import com.belmonte.usuarios_service.dto.PasajeroRequest;
import com.belmonte.usuarios_service.dto.BusquedaPasajeroRequest;
import com.belmonte.usuarios_service.security.IdentidadSesion;
import com.belmonte.usuarios_service.service.JwtService;
import com.belmonte.usuarios_service.service.DecisionAutorizacionService;
import com.belmonte.usuarios_service.service.RelojBaseDatos;
import com.belmonte.usuarios_service.service.RecuperacionPasswordService;
import com.belmonte.usuarios_service.service.UsuarioService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "BELMONTE_ORACLE_TEST_URL", matches = ".+")
class UsuariosOracleMigrationTest {

    @Test
    void migratesAnEmptyTestSchemaAndEnforcesItsConstraints() throws Exception {
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
        Flyway.configure().dataSource(url, username, password).target("3").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO operaciones_cambio_usuario "
                    + "(clave, actor_id, usuario_id, solicitud_hash, barrera_id, proposito, estado) "
                    + "VALUES ('legacy-pendiente', 1, 1, 'hash', 'legacy', 'BAJA', 'PENDIENTE')");
        }
        Flyway.configure().dataSource(url, username, password).target("4").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO operaciones_cambio_usuario "
                    + "(clave, estado, sesion_original, tipo_solicitud, solicitud_activo) "
                    + "VALUES ('sin-vencimiento', 'PENDIENTE', 'sesion', 'ESTADO', 0)");
        }
        Flyway.configure().dataSource(url, username, password).target("5").load().migrate();
        Flyway.configure().dataSource(url, username, password).target("6").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO decisiones_autorizacion "
                    + "(decision_id, actor_id, sesion_id, solicitud_hash, jwt_expira_en, sesion_expira_en, estado) "
                    + "VALUES ('VEHICULOS_ESTADO:antigua', 1, 'sesion-antigua', '" + "a".repeat(64)
                    + "', TO_TIMESTAMP_TZ('2030-01-02 03:04:05 +00:00', 'YYYY-MM-DD HH24:MI:SS TZH:TZM'), "
                    + "TIMESTAMP '2030-01-02 03:04:05', 'PREPARADA')");
            statement.executeUpdate("INSERT INTO paises (nombre_pais) VALUES ('Pais migracion sesion')");
            statement.executeUpdate("INSERT INTO regiones (pais_id, nombre_region) "
                    + "SELECT id_pais, 'Region migracion' FROM paises WHERE nombre_pais = 'Pais migracion sesion'");
            statement.executeUpdate("INSERT INTO comunas (region_id, nombre_comuna) "
                    + "SELECT id_region, 'Comuna migracion' FROM regiones WHERE nombre_region = 'Region migracion'");
            statement.executeUpdate("INSERT INTO tipos_documento (codigo_tipo) VALUES ('MIGSES')");
            statement.executeUpdate("INSERT INTO roles (nombre_rol) VALUES ('ROL_MIG_SES')");
            statement.executeUpdate("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email) "
                    + "SELECT p.id_pais, t.id_tipo_documento, 'MIG-1', 'Migracion', 'migracion@example.invalid' "
                    + "FROM paises p CROSS JOIN tipos_documento t WHERE p.nombre_pais = 'Pais migracion sesion' AND t.codigo_tipo = 'MIGSES'");
            statement.executeUpdate("INSERT INTO usuarios (persona_id, rol_id, password) "
                    + "SELECT p.id_persona, r.id_rol, 'test' FROM personas p CROSS JOIN roles r "
                    + "WHERE p.email = 'migracion@example.invalid' AND r.nombre_rol = 'ROL_MIG_SES'");
            statement.executeUpdate("INSERT INTO sesiones_usuario (sesion_id, usuario_id, fecha_inicio, fecha_expiracion, activa) "
                    + "SELECT 'antigua-activa', id_usuario, SYSTIMESTAMP, SYSTIMESTAMP + 1, 1 FROM usuarios");
            statement.executeUpdate("INSERT INTO sesiones_usuario (sesion_id, usuario_id, fecha_inicio, fecha_expiracion, activa) "
                    + "SELECT 'antigua-inactiva', id_usuario, SYSTIMESTAMP, SYSTIMESTAMP + 1, 0 FROM usuarios");
        }
        Flyway.configure().dataSource(url, username, password).target("7").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sesiones_usuario "
                    + "(sesion_id, usuario_id, fecha_inicio, fecha_expiracion, fecha_expiracion_instante, activa) "
                    + "SELECT 'sesion-v7', id_usuario, SYSTIMESTAMP, SYSTIMESTAMP + 1, SYSTIMESTAMP + 1, 1 FROM usuarios");
        }
        Flyway.configure().dataSource(url, username, password).target("8").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO decisiones_autorizacion "
                    + "(decision_id, actor_id, sesion_id, solicitud_hash, jwt_expira_en, sesion_expira_en, sesion_expira_instante, estado) "
                    + "VALUES ('VEHICULOS_ESTADO:autorizada-v8', 1, 'sesion-v7', '" + "a".repeat(64)
                    + "', TO_TIMESTAMP_TZ('2030-01-02 03:04:05 +00:00', 'YYYY-MM-DD HH24:MI:SS TZH:TZM'), "
                    + "TIMESTAMP '2030-01-02 03:04:05', "
                    + "TO_TIMESTAMP_TZ('2030-01-02 03:04:05 +00:00', 'YYYY-MM-DD HH24:MI:SS TZH:TZM'), 'AUTORIZADA')");
        }

        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("oracleTest", Map.of(
                "USUARIOS_DB_URL", url,
                "USUARIOS_DB_USER", username,
                "USUARIOS_DB_PASSWORD", password,
                "server.port", "0")));

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(UsuariosServiceApplication.class)
                .environment(environment)
                .profiles("oracle")
                .web(WebApplicationType.SERVLET)
                .run()) {
            Flyway flyway = Flyway.configure().dataSource(url, username, password).load();
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful);
            var comunas = context.getBean(ComunaService.class).listar();
            assertEquals(1, comunas.size());
            assertEquals("Comuna migracion", comunas.get(0).nombreComuna());
            assertEquals("Region migracion", comunas.get(0).nombreRegion());
            assertTrue(comunas.get(0).idPais() > 0);
            verificarComunasHttp(context, comunas.get(0));
            verifyConstraintsAndRepository(url, username, password, context);
            verificarInvitacionesYRecuperaciones(context);
            verificarEmpresas(context);
            verificarPasajeros(context);
            verificarServicioRecuperacion(context);
        }
    }

    private void verificarServicioRecuperacion(ConfigurableApplicationContext context) throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        var recuperaciones = context.getBean(RecuperacionPasswordService.class);
        long id = jdbc.queryForObject("SELECT u.id_usuario FROM usuarios u JOIN personas p "
                + "ON p.id_persona = u.persona_id WHERE p.email = 'usuario-pasajeros@example.invalid'", Long.class);
        var anterior = recuperaciones.emitir(id);
        var actual = recuperaciones.emitir(id);
        assertThrows(ResponseStatusException.class, () -> recuperaciones.cambiarPassword(anterior.token(), "nuevo"));
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tareas = new java.util.ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                tareas.add(executor.submit(() -> {
                    inicio.await();
                    try {
                        recuperaciones.cambiarPassword(actual.token(), "nuevo");
                        return 200;
                    } catch (ResponseStatusException exception) {
                        return exception.getStatusCode().value();
                    }
                }));
            }
            inicio.countDown();
            assertEquals(List.of(200, 400), java.util.stream.Stream.of(
                    tareas.get(0).get(20, TimeUnit.SECONDS), tareas.get(1).get(20, TimeUnit.SECONDS))
                    .sorted().toList());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sesiones_usuario WHERE usuario_id = ? AND activa = 1",
                Integer.class, id));
        String hash = jdbc.queryForObject("SELECT password FROM usuarios WHERE id_usuario = ?", String.class, id);
        assertTrue(new BCryptPasswordEncoder().matches("nuevo", hash));
    }

    private void verificarInvitacionesYRecuperaciones(ConfigurableApplicationContext context) throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var invitaciones = context.getBean(InvitacionRepository.class);
        var recuperaciones = context.getBean(RecuperacionPasswordRepository.class);
        var usuarios = context.getBean(UsuarioRepository.class);
        Long usuarioId = jdbc.queryForObject("SELECT u.id_usuario FROM usuarios u JOIN personas p "
                + "ON p.id_persona = u.persona_id WHERE p.email = 'prueba@example.invalid'", Long.class);
        var ahora = java.time.OffsetDateTime.parse("2030-01-01T00:00:00Z");
        tx.executeWithoutResult(status -> {
            var usuario = usuarios.findById(usuarioId).orElseThrow();
            var invitacion = new Invitacion();
            invitacion.setEmailDestinatario("invitado@example.invalid");
            invitacion.setRol(usuario.getRol());
            invitacion.setCreadoPorUsuario(usuario);
            invitacion.setTokenHash("a".repeat(64));
            invitacion.setFechaCreacion(ahora);
            invitacion.setFechaExpiracion(ahora.plusHours(48));
            invitaciones.saveAndFlush(invitacion);
            var recuperacion = new RecuperacionPassword();
            recuperacion.setUsuario(usuario);
            recuperacion.setTokenHash("b".repeat(64));
            recuperacion.setFechaCreacion(ahora);
            recuperacion.setFechaExpiracion(ahora.plusHours(1));
            recuperaciones.saveAndFlush(recuperacion);
        });
        verificarBloqueo(tx, () -> {
            var invitacion = invitaciones.findByTokenHash("a".repeat(64)).orElseThrow();
            assertTrue(!invitacion.isUsada());
            invitacion.setUsada(true);
            invitacion.setFechaUso(ahora.plusMinutes(1));
        }, () -> invitaciones.findByTokenHash("a".repeat(64)).orElseThrow().isUsada());
        verificarBloqueo(tx, () -> {
            var recuperacion = recuperaciones.findByTokenHash("b".repeat(64)).orElseThrow();
            assertTrue(!recuperacion.isUsada());
            recuperacion.setUsada(true);
            recuperacion.setFechaUso(ahora.plusMinutes(1));
        }, () -> recuperaciones.findByTokenHash("b".repeat(64)).orElseThrow().isUsada());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM invitaciones WHERE token_hash = ? "
                + "AND usada = 1 AND fecha_uso IS NOT NULL", Integer.class, "a".repeat(64)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recuperaciones_password WHERE token_hash = ? "
                + "AND usada = 1 AND fecha_uso IS NOT NULL", Integer.class, "b".repeat(64)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE invitaciones SET usada = 2 WHERE token_hash = ?", "a".repeat(64)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE recuperaciones_password SET fecha_uso = NULL WHERE token_hash = ?", "b".repeat(64)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE invitaciones SET fecha_expiracion = fecha_creacion WHERE token_hash = ?", "a".repeat(64)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO invitaciones (email_destinatario, rol_id, creado_por_usuario_id, token_hash,
                    fecha_creacion, fecha_expiracion, usada)
                SELECT email_destinatario, rol_id, creado_por_usuario_id, token_hash,
                    fecha_creacion, fecha_expiracion, 0 FROM invitaciones WHERE token_hash = ?
                """, "a".repeat(64)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO recuperaciones_password (usuario_id, token_hash, fecha_creacion, fecha_expiracion, usada)
                SELECT -1, ?, fecha_creacion, fecha_expiracion, 0 FROM recuperaciones_password WHERE token_hash = ?
                """, "c".repeat(64), "b".repeat(64)));
    }

    private void verificarBloqueo(TransactionTemplate tx, Runnable marcar,
                                   java.util.function.Supplier<Boolean> leerUsada) throws Exception {
        var inicio = new CountDownLatch(1);
        var futura = new AtomicReference<Future<Boolean>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                marcar.run();
                futura.set(executor.submit(() -> {
                    inicio.countDown();
                    return tx.execute(estado -> leerUsada.get());
                }));
                try {
                    assertTrue(inicio.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                assertThrows(TimeoutException.class, () -> futura.get().get(500, TimeUnit.MILLISECONDS));
            });
            assertTrue(futura.get().get(10, TimeUnit.SECONDS));
        }
    }

    private void verificarPasajeros(ConfigurableApplicationContext context) throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        var pasajeros = context.getBean(PasajeroService.class);
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES ('Pais pasajeros Oracle')");
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES ('ORACLE-PAS')");
        long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = 'Pais pasajeros Oracle'", Long.class);
        long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = 'ORACLE-PAS'", Long.class);
        long empresa = jdbc.queryForObject("SELECT id_empresa FROM empresas WHERE rut_empresa = 'ORACLE-TEST-1'", Long.class);
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email) "
                + "VALUES (?, ?, 'ORACLE-U1', 'Usuario Oracle', 'usuario-pasajeros@example.invalid')", pais, tipo);
        long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = 'usuario-pasajeros@example.invalid'",
                Long.class);
        long rol = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = 'ADMINISTRADOR'", Long.class);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, ?, 1)",
                persona, rol, new BCryptPasswordEncoder().encode("password"));
        long actor = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, persona);
        jdbc.update("INSERT INTO sesiones_usuario (sesion_id, usuario_id, fecha_inicio, fecha_expiracion, "
                + "fecha_expiracion_instante, activa) VALUES ('pasajero-oracle', ?, SYSTIMESTAMP, "
                + "SYSTIMESTAMP + 1, SYSTIMESTAMP + 1, 1)", actor);
        var identidad = new IdentidadSesion(actor, "pasajero-oracle",
                context.getBean(RelojBaseDatos.class).ahora().plusMinutes(10));
        var request = new PasajeroRequest("Pasajero Oracle", pais, tipo, "ORACLE-P1",
                "pasajero-oracle@example.invalid", "123", empresa);
        var creado = pasajeros.crear(identidad, request);
        assertTrue(creado.idPasajero() > 0);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> pasajeros.crear(identidad, request))
                .getStatusCode().value());
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email) "
                + "VALUES (?, ?, 'ORACLE-U2', 'Otro usuario', 'otro-usuario@example.invalid')", pais, tipo);
        long otraPersona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = 'otro-usuario@example.invalid'", Long.class);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, 'test', 1)", otraPersona, rol);
        long otroActor = jdbc.queryForObject("SELECT id_usuario FROM usuarios WHERE persona_id = ?", Long.class, otraPersona);
        jdbc.update("INSERT INTO sesiones_usuario (sesion_id, usuario_id, fecha_inicio, fecha_expiracion, "
                + "fecha_expiracion_instante, activa) VALUES ('pasajero-oracle-2', ?, SYSTIMESTAMP, "
                + "SYSTIMESTAMP + 1, SYSTIMESTAMP + 1, 1)", otroActor);
        var otraIdentidad = new IdentidadSesion(otroActor, "pasajero-oracle-2", identidad.jwtExpiraEn());
        var carrera = new PasajeroRequest("Pasajero carrera Oracle", pais, tipo, "ORACLE-RACE",
                "pasajero-race@example.invalid", "789", empresa);
        CountDownLatch inicioRegistro = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tareas = new java.util.ArrayList<Future<Integer>>();
            for (var sesion : List.of(identidad, otraIdentidad)) {
                tareas.add(executor.submit(() -> {
                    inicioRegistro.await();
                    try {
                        pasajeros.crear(sesion, carrera);
                        return 201;
                    } catch (ResponseStatusException exception) {
                        return exception.getStatusCode().value();
                    }
                }));
            }
            inicioRegistro.countDown();
            assertEquals(List.of(201, 409), java.util.stream.Stream.of(
                    tareas.get(0).get(20, TimeUnit.SECONDS), tareas.get(1).get(20, TimeUnit.SECONDS))
                    .sorted().toList());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM personas WHERE email = 'pasajero-race@example.invalid'",
                Integer.class));
        var consulta = new BusquedaPasajeroRequest(BusquedaPasajeroRequest.Campo.NOMBRE, "Pasajero Oracle", 0, 50);
        var resultado = pasajeros.buscar(identidad, consulta);
        assertEquals(1, resultado.pasajeros().size());
        assertEquals(creado.idPasajero(), resultado.pasajeros().get(0).idPasajero());
        int port = Objects.requireNonNull(context.getEnvironment().getProperty("local.server.port", Integer.class));
        var http = HttpClient.newHttpClient();
        var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"usuario-pasajeros@example.invalid\",\"password\":\"password\"}")).build();
        var loginResponse = http.send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResponse.statusCode());
        String token = context.getBean(ObjectMapper.class).readTree(loginResponse.body()).get("accessToken").asString();
        String bodyAlta = context.getBean(ObjectMapper.class).writeValueAsString(
                new PasajeroRequest("Pasajero HTTP Oracle", pais, tipo, "ORACLE-P2",
                        "pasajero-http-oracle@example.invalid", "456", empresa));
        var alta = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/pasajeros"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyAlta)).build();
        var altaResponse = http.send(alta, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, altaResponse.statusCode());
        assertTrue(context.getBean(ObjectMapper.class).readTree(altaResponse.body()).get("idPasajero").longValue() > 0);
        assertEquals(409, http.send(alta, HttpResponse.BodyHandlers.discarding()).statusCode());
        var buscar = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/pasajeros/busquedas"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"campo\":\"NOMBRE\",\"texto\":\"Pasajero Oracle\",\"pagina\":0,\"tamano\":50}"))
                .build();
        var response = http.send(buscar, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(creado.idPasajero(),
                context.getBean(ObjectMapper.class).readTree(response.body()).get("pasajeros").get(0).get("idPasajero").longValue());
        for (int i = 0; i < 27; i++) {
            assertTrue(!pasajeros.buscar(identidad, consulta).limitada());
        }
        CountDownLatch inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tarea = (java.util.concurrent.Callable<Boolean>) () -> {
                inicio.await();
                return pasajeros.buscar(identidad, consulta).limitada();
            };
            var primero = executor.submit(tarea);
            var segundo = executor.submit(tarea);
            inicio.countDown();
            assertTrue(primero.get(20, TimeUnit.SECONDS) != segundo.get(20, TimeUnit.SECONDS));
        }
        assertEquals(30, jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_busqueda_pasajeros "
                + "WHERE actor_id = ? AND resultado <> 'LIMITADA'", Integer.class, actor));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_busqueda_pasajeros "
                + "WHERE actor_id = ? AND resultado = 'LIMITADA'", Integer.class, actor));
        assertEquals(429, http.send(buscar, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private void verificarEmpresas(ConfigurableApplicationContext context) {
        var empresas = context.getBean(EmpresaService.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        var request = new EmpresaRequest("ORACLE-TEST-1", "Empresa Oracle", "123", "test@example.invalid");
        var empresa = empresas.crear(request);
        assertTrue(empresa.getIdEmpresa() > 0);
        assertEquals("Empresa Oracle", empresas.buscar(empresa.getIdEmpresa()).getRazonSocial());
        assertTrue(empresas.listar().stream().anyMatch(item -> item.getIdEmpresa().equals(empresa.getIdEmpresa())));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> empresas.crear(request))
                .getStatusCode().value());
        String largo = "\u00e9".repeat(150);
        String semantica = jdbc.queryForObject(
                "SELECT char_used FROM user_tab_columns WHERE table_name = 'EMPRESAS' AND column_name = 'RAZON_SOCIAL'",
                String.class);
        Integer bytes = jdbc.queryForObject("SELECT LENGTHB(?) FROM DUAL", Integer.class, largo);
        if ("B".equals(semantica) && bytes != null && bytes > 150) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> empresas.crear(new EmpresaRequest("ORACLE-TEST-2", largo, null, null)))
                    .getStatusCode().value());
        }
    }

    private void verificarComunasHttp(ConfigurableApplicationContext context, ComunaService.Comuna esperada)
            throws Exception {
        int port = Objects.requireNonNull(context.getEnvironment().getProperty("local.server.port", Integer.class));
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/comunas");
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var anonima = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        assertEquals(401, http.send(anonima, HttpResponse.BodyHandlers.discarding()).statusCode());
        Long usuarioId = context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT u.id_usuario FROM usuarios u JOIN personas p ON p.id_persona = u.persona_id "
                        + "WHERE p.email = ?", Long.class, "migracion@example.invalid");
        String token = context.getBean(JwtService.class).createToken("migracion@example.invalid", usuarioId,
                "sesion-v7", List.of("ROL_MIG_SES"), context.getBean(RelojBaseDatos.class).ahora().toInstant());
        var autenticada = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token).GET().build();
        var response = http.send(autenticada, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var json = context.getBean(ObjectMapper.class).readTree(response.body());
        assertTrue(json.isArray());
        assertEquals(1, json.size());
        assertEquals(esperada.idComuna().longValue(), json.get(0).get("idComuna").longValue());
        assertEquals(esperada.nombreComuna(), json.get(0).get("nombreComuna").asString());
        assertEquals(esperada.idRegion().longValue(), json.get(0).get("idRegion").longValue());
        assertEquals(esperada.nombreRegion(), json.get(0).get("nombreRegion").asString());
        assertEquals(esperada.idPais().longValue(), json.get(0).get("idPais").longValue());
    }

    private void verifyConstraintsAndRepository(String url, String username, String password,
                                                ConfigurableApplicationContext context) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM operaciones_cambio_usuario WHERE clave = 'legacy-pendiente' "
                    + "AND estado = 'PENDIENTE' AND sesion_original IS NULL AND tipo_solicitud IS NULL "
                    + "AND solicitud_activo IS NULL AND solicitud_rol_id IS NULL AND jwt_original_expira_en IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM operaciones_cambio_usuario WHERE clave = 'sin-vencimiento' "
                    + "AND sesion_original = 'sesion' AND jwt_original_expira_en IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM decisiones_autorizacion WHERE decision_id = 'VEHICULOS_ESTADO:antigua' "
                    + "AND sesion_expira_instante IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM decisiones_autorizacion WHERE decision_id = 'VEHICULOS_ESTADO:autorizada-v8' "
                    + "AND estado = 'AUTORIZADA' AND tiempo_limite_superior IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE decisiones_autorizacion SET tiempo_limite_superior = jwt_expira_en "
                    + "WHERE decision_id = 'VEHICULOS_ESTADO:autorizada-v8'")).getErrorCode());
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM sesiones_usuario WHERE sesion_id IN ('antigua-activa', 'antigua-inactiva') "
                    + "AND activa = 0 AND fecha_expiracion_instante IS NULL")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM sesiones_usuario WHERE sesion_id = 'sesion-v7' "
                    + "AND activa = 1 AND fecha_expiracion_instante IS NOT NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE sesiones_usuario SET activa = 1 WHERE sesion_id = 'antigua-activa'")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE sesiones_usuario SET fecha_expiracion_instante = NULL WHERE sesion_id = 'sesion-v7'")).getErrorCode());
            statement.executeUpdate("DELETE FROM sesiones_usuario WHERE sesion_id IN ('antigua-activa', 'antigua-inactiva', 'sesion-v7')");
            statement.executeUpdate("DELETE FROM usuarios WHERE persona_id = (SELECT id_persona FROM personas WHERE email = 'migracion@example.invalid')");
            statement.executeUpdate("DELETE FROM personas WHERE email = 'migracion@example.invalid'");
            statement.executeUpdate("DELETE FROM roles WHERE nombre_rol = 'ROL_MIG_SES'");
            statement.executeUpdate("DELETE FROM tipos_documento WHERE codigo_tipo = 'MIGSES'");
            statement.executeUpdate("DELETE FROM comunas WHERE nombre_comuna = 'Comuna migracion'");
            statement.executeUpdate("DELETE FROM regiones WHERE nombre_region = 'Region migracion'");
            statement.executeUpdate("DELETE FROM paises WHERE nombre_pais = 'Pais migracion sesion'");
            connection.commit();
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE operaciones_cambio_usuario SET jwt_original_expira_en = SYSTIMESTAMP "
                    + "WHERE clave = 'legacy-pendiente'")).getErrorCode());
            assertEquals(2290, assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO operaciones_cambio_usuario (clave, estado, sesion_original) "
                    + "VALUES ('incompleta', 'PENDIENTE', 'sesion')")).getErrorCode());
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN "
                    + "('PAISES', 'REGIONES', 'COMUNAS', 'EMPRESAS', 'ROLES', "
                    + "'TIPOS_DOCUMENTO', 'PERSONAS', 'USUARIOS', 'PASAJEROS', "
                    + "'SESIONES_USUARIO')")) {
                assertTrue(result.next());
                assertEquals(10, result.getInt(1));
            }

            statement.executeUpdate("INSERT INTO paises (nombre_pais) VALUES ('Pais de prueba')");
            statement.executeUpdate("INSERT INTO tipos_documento (codigo_tipo) VALUES ('PRUEBA')");
            statement.executeUpdate("INSERT INTO roles (nombre_rol) VALUES ('SOLICITANTE')");
            statement.executeUpdate("INSERT INTO empresas (rut_empresa, razon_social) VALUES ('TEST-1', 'Empresa de prueba')");
            statement.executeUpdate("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, telefono, email) "
                    + "SELECT p.id_pais, t.id_tipo_documento, 'TEST-1', 'Persona de prueba', '123', 'prueba@example.invalid' "
                    + "FROM paises p CROSS JOIN tipos_documento t");
            statement.executeUpdate("INSERT INTO usuarios (persona_id, rol_id, password) "
                    + "SELECT p.id_persona, r.id_rol, '"
                    + new BCryptPasswordEncoder().encode("password")
                    + "' FROM personas p CROSS JOIN roles r");
            statement.executeUpdate("INSERT INTO pasajeros (persona_id, empresa_id) "
                    + "SELECT p.id_persona, e.id_empresa FROM personas p CROSS JOIN empresas e");
            statement.executeUpdate("INSERT INTO sesiones_usuario "
                    + "(sesion_id, usuario_id, fecha_inicio, fecha_expiracion, fecha_expiracion_instante, activa) "
                    + "SELECT 'test-session', id_usuario, SYSTIMESTAMP, SYSTIMESTAMP + 1, SYSTIMESTAMP + 1, 1 "
                    + "FROM usuarios");

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
            Long usuarioId;
            try (ResultSet result = statement.executeQuery("SELECT id_usuario FROM usuarios")) {
                assertTrue(result.next());
                usuarioId = result.getLong(1);
            }
            UsuarioService usuarios = context.getBean(UsuarioService.class);
            statement.executeUpdate("INSERT INTO roles (nombre_rol) VALUES ('ADMINISTRADOR')");
            statement.executeUpdate("UPDATE usuarios SET rol_id = (SELECT id_rol FROM roles WHERE nombre_rol = 'ADMINISTRADOR')");
            statement.executeUpdate("INSERT INTO sesiones_usuario "
                    + "(sesion_id, usuario_id, fecha_inicio, fecha_expiracion, fecha_expiracion_instante, activa) "
                    + "SELECT 'decision-session', id_usuario, SYSTIMESTAMP, SYSTIMESTAMP + 1, SYSTIMESTAMP + 1, 1 FROM usuarios");
            connection.commit();
            var decision = context.getBean(DecisionAutorizacionService.class);
            assertEquals(503, assertThrows(ResponseStatusException.class, () -> decision.decidir(new DecisionAutorizacionService.Solicitud(
                    "VEHICULOS_ESTADO", "antigua", 1L, "sesion-antigua", "a".repeat(64),
                    java.time.OffsetDateTime.parse("2030-01-02T03:04:05Z")))).getStatusCode().value());
            var solicitud = new DecisionAutorizacionService.Solicitud(
                    "VEHICULOS_ESTADO", "oracle-decision", usuarioId, "decision-session", "a".repeat(64),
                    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0).plusMinutes(5));
            assertEquals(503, assertThrows(ResponseStatusException.class, () -> decision.decidir(solicitud))
                    .getStatusCode().value());
            usuarios.cerrarSesion("decision-session");
            assertEquals(503, assertThrows(ResponseStatusException.class, () -> decision.decidir(solicitud))
                    .getStatusCode().value());
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM decisiones_autorizacion WHERE decision_id = 'VEHICULOS_ESTADO:oracle-decision'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
            var inicio = usuarios.iniciarSesion("prueba@example.invalid", "password");
            assertEquals("Bearer", inicio.get("tokenType"));
            String sesionLogin = context.getBean(JwtService.class)
                    .parseToken((String) inicio.get("accessToken")).get("sessionId", String.class);
            try (ResultSet result = statement.executeQuery(
                    "SELECT fecha_expiracion_instante FROM sesiones_usuario WHERE sesion_id = '" + sesionLogin + "'")) {
                assertTrue(result.next());
                assertTrue(result.getObject(1, java.time.OffsetDateTime.class).toInstant().isAfter(java.time.Instant.now()));
                assertEquals(context.getBean(JwtService.class).parseToken((String) inicio.get("accessToken"))
                                .getExpiration().toInstant(),
                        result.getObject(1, java.time.OffsetDateTime.class).toInstant());
            }
            CambioUsuarioTransacciones cambios = context.getBean(CambioUsuarioTransacciones.class);
            String barrera = java.util.UUID.randomUUID().toString();
            cambios.reservar("oracle-estado", usuarioId, "test-session", usuarioId, "hash", barrera, false, null,
                    java.time.OffsetDateTime.parse("2030-01-02T03:04:05Z"));
            assertEquals("PENDIENTE", cambios.leer("oracle-estado", usuarioId, usuarioId, "hash").estado());
            try (ResultSet result = statement.executeQuery(
                    "SELECT sesion_original, tipo_solicitud, solicitud_activo, jwt_original_expira_en FROM operaciones_cambio_usuario "
                    + "WHERE clave = 'oracle-estado'")) {
                assertTrue(result.next());
                assertEquals("test-session", result.getString(1));
                assertEquals("ESTADO", result.getString(2));
                assertEquals(0, result.getInt(3));
                assertEquals(java.time.Instant.parse("2030-01-02T03:04:05Z"),
                        result.getObject(4, java.time.OffsetDateTime.class).toInstant());
            }
            UsuarioRepository repository = context.getBean(UsuarioRepository.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            CountDownLatch logoutStarted = new CountDownLatch(1);
            AtomicReference<Future<?>> logout = new AtomicReference<>();
            try (var executor = Executors.newSingleThreadExecutor()) {
                tx.executeWithoutResult(status -> {
                    repository.findForUpdate(usuarioId).orElseThrow();
                    logout.set(executor.submit(() -> {
                        logoutStarted.countDown();
                        usuarios.cerrarSesion(sesionLogin);
                    }));
                    try {
                        assertTrue(logoutStarted.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    assertThrows(TimeoutException.class, () -> logout.get().get(500, TimeUnit.MILLISECONDS));
                });
                logout.get().get(10, TimeUnit.SECONDS);
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM sesiones_usuario WHERE activa = 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Future<?>> login = new AtomicReference<>();
            try (var executor = Executors.newSingleThreadExecutor()) {
                tx.executeWithoutResult(status -> {
                    repository.findForUpdate(usuarioId).orElseThrow();
                    login.set(executor.submit(() -> {
                        started.countDown();
                        return usuarios.iniciarSesion("prueba@example.invalid", "password");
                    }));
                    try {
                        assertTrue(started.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    assertThrows(TimeoutException.class, () -> login.get().get(500, TimeUnit.MILLISECONDS));
                    assertEquals("APLICADA",
                            cambios.aplicar("oracle-estado", usuarioId, usuarioId, "hash", false, null).estado());
                });
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> login.get().get(10, TimeUnit.SECONDS));
                assertEquals(401, ((ResponseStatusException) failure.getCause()).getStatusCode().value());
            }
            assertEquals(1, cambios.aplicadas(null).size());
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM operaciones_cambio_usuario WHERE clave = 'oracle-estado' "
                    + "AND sesion_original IS NULL AND tipo_solicitud IS NULL AND solicitud_activo IS NULL "
                    + "AND jwt_original_expira_en IS NULL")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertEquals(200, cambios.finalizar("oracle-estado", usuarioId, usuarioId, "hash").codigo());
            cambios.reservar("oracle-rol-inexistente", usuarioId, "test-session", usuarioId, "otro-hash",
                    java.util.UUID.randomUUID().toString(), null, 999999999L, null);
            assertEquals(404, cambios.leer("oracle-rol-inexistente", usuarioId, usuarioId, "otro-hash")
                    .resultado().codigo());
            statement.executeUpdate("UPDATE operaciones_cambio_usuario SET expira_en = SYSTIMESTAMP - INTERVAL '1' DAY "
                    + "WHERE clave = 'oracle-estado'");
            connection.commit();
            cambios.limpiarRespuestasExpiradas();
            assertEquals("EXPIRADA", cambios.leer("oracle-estado", usuarioId, usuarioId, "hash").estado());
            try (ResultSet result = statement.executeQuery(
                    "SELECT activo, (SELECT COUNT(*) FROM sesiones_usuario WHERE activa = 1) FROM usuarios")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
                assertEquals(0, result.getInt(2));
            }
            assertEquals(401, assertThrows(ResponseStatusException.class,
                    () -> usuarios.iniciarSesion("prueba@example.invalid", "password")).getStatusCode().value());
        }
    }
}
