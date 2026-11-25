package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import com.belmonte.usuarios_service.service.JwtService;
import com.belmonte.usuarios_service.service.UsuarioService;
import com.belmonte.usuarios_service.service.RelojBaseDatos;
import com.belmonte.usuarios_service.security.InternalApiAuthentication;
import io.jsonwebtoken.JwtException;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final UsuarioService usuarioService;
    private final SesionUsuarioRepository sesionRepository;
    private final JwtService jwtService;
    private final InternalApiAuthentication internalApiAuthentication;
    private final RelojBaseDatos reloj;

    public AuthController(UsuarioService usuarioService,
                          SesionUsuarioRepository sesionRepository,
                          JwtService jwtService,
                          InternalApiAuthentication internalApiAuthentication,
                          RelojBaseDatos reloj) {
        this.usuarioService = usuarioService;
        this.sesionRepository = sesionRepository;
        this.jwtService = jwtService;
        this.internalApiAuthentication = internalApiAuthentication;
        this.reloj = reloj;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> credentials) {
        String email = credentials.get("email");
        String password = credentials.get("password");

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debe ingresar correo y contrasena");
        }

        return usuarioService.iniciarSesion(email, password);
    }

    @GetMapping("/sessions/{sessionId}/validate")
    public Map<String, Object> validateSession(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Belmonte-Internal-Secret", required = false)
            String suppliedSecret,
            @RequestParam(value = "jwtExpiraEn", required = false) Long jwtExpiraEn) {
        internalApiAuthentication.requireValidSecret(suppliedSecret);

        boolean active;
        try {
            active = sesionRepository
                    .findBySesionIdAndActiva(sessionId, 1)
                    .filter(session -> {
                        Instant ahora = reloj.ahora().toInstant();
                        return session.vigenteEn(ahora)
                                && (jwtExpiraEn == null || Instant.ofEpochMilli(jwtExpiraEn).isAfter(ahora));
                    })
                    .isPresent();
        } catch (DataAccessException exception) {
            log.warn("No se pudo consultar la sesion o el reloj de base de datos", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Validacion de sesion no disponible", exception);
        }

        if (!active) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion revocada o vencida");
        }
        return Map.of("active", true);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
        }

        String sessionId;
        try {
            sessionId = jwtService.parseToken(authorization.substring(7), reloj.ahora().toInstant()).get("sessionId", String.class);
        } catch (DataAccessException exception) {
            log.warn("No se pudo consultar el reloj de base de datos", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Validacion de sesion no disponible", exception);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token invalido");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token invalido");
        }
        usuarioService.cerrarSesion(sessionId);

        return Map.of("mensaje", "Sesion cerrada");
    }
}
