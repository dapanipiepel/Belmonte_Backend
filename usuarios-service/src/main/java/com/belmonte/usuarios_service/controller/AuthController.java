package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.model.SesionUsuario;
import com.belmonte.usuarios_service.model.Usuario;
import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import com.belmonte.usuarios_service.service.JwtService;
import com.belmonte.usuarios_service.service.UsuarioService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UsuarioService usuarioService;
    private final SesionUsuarioRepository sesionRepository;
    private final JwtService jwtService;
    private final String internalApiSecret;

    public AuthController(UsuarioService usuarioService,
                          SesionUsuarioRepository sesionRepository,
                          JwtService jwtService,
                          @Value("${internal.api.secret}") String internalApiSecret) {
        this.usuarioService = usuarioService;
        this.sesionRepository = sesionRepository;
        this.jwtService = jwtService;
        this.internalApiSecret = internalApiSecret;
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

        Usuario usuario = usuarioService.iniciarSesion(email, password);
        LocalDateTime started = LocalDateTime.now();
        LocalDateTime expires = started.plusNanos(jwtService.getExpirationMillis() * 1_000_000);

        SesionUsuario session = new SesionUsuario();
        session.setSesionId(UUID.randomUUID().toString());
        session.setUsuario(usuario);
        session.setFechaInicio(started);
        session.setFechaExpiracion(expires);
        session.setActiva(1);
        sesionRepository.save(session);

        String token = jwtService.createToken(
                usuario.getPersona().getEmail(),
                usuario.getIdUsuario(),
                session.getSesionId(),
                List.of(usuario.getRol().getNombreRol()));

        return Map.of(
                "accessToken", token,
                "tokenType", "Bearer",
                "expiresIn", jwtService.getExpirationMillis() / 1000,
                "usuario", Map.of(
                        "idUsuario", usuario.getIdUsuario(),
                        "idPersona", usuario.getPersona().getIdPersona(),
                        "nombreCompleto", usuario.getPersona().getNombreCompleto(),
                        "email", usuario.getPersona().getEmail(),
                        "rol", usuario.getRol().getNombreRol(),
                        "activo", usuario.getActivo()));
    }

    @GetMapping("/sessions/{sessionId}/validate")
    public Map<String, Object> validateSession(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Belmonte-Internal-Secret", required = false)
            String suppliedSecret) {
        boolean secretMatches = suppliedSecret != null
                && MessageDigest.isEqual(
                        suppliedSecret.getBytes(StandardCharsets.UTF_8),
                        internalApiSecret.getBytes(StandardCharsets.UTF_8));
        if (!secretMatches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credencial interna invalida");
        }

        boolean active = sesionRepository
                .findBySesionIdAndActiva(sessionId, 1)
                .filter(session -> session.getFechaExpiracion().isAfter(LocalDateTime.now()))
                .isPresent();

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

        try {
            String sessionId = jwtService.parseToken(
                    authorization.substring(7)).get("sessionId", String.class);
            sesionRepository.findById(sessionId).ifPresent(session -> {
                session.setActiva(0);
                sesionRepository.save(session);
            });
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token invalido");
        }

        return Map.of("mensaje", "Sesion cerrada");
    }
}
