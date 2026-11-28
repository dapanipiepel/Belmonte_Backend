package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.model.Invitacion;
import com.belmonte.usuarios_service.model.Persona;
import com.belmonte.usuarios_service.model.Usuario;
import com.belmonte.usuarios_service.repository.InvitacionRepository;
import com.belmonte.usuarios_service.repository.PersonaRepository;
import com.belmonte.usuarios_service.repository.RolRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.security.IdentidadSesion;
import com.belmonte.usuarios_service.security.TokenUtil;
import jakarta.validation.constraints.Email;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvitacionService {
    private final InvitacionRepository invitaciones;
    private final PersonaRepository personas;
    private final UsuarioRepository usuarios;
    private final RolRepository roles;
    private final TokenUtil tokens;
    private final RelojBaseDatos reloj;
    private final JdbcTemplate jdbc;
    private final jakarta.validation.Validator validator;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public InvitacionService(InvitacionRepository invitaciones, PersonaRepository personas,
            UsuarioRepository usuarios, RolRepository roles, TokenUtil tokens, RelojBaseDatos reloj,
            JdbcTemplate jdbc, jakarta.validation.Validator validator) {
        this.invitaciones = invitaciones;
        this.personas = personas;
        this.usuarios = usuarios;
        this.roles = roles;
        this.tokens = tokens;
        this.reloj = reloj;
        this.jdbc = jdbc;
        this.validator = validator;
    }

    @Transactional
    public Enlace emitir(IdentidadSesion actor, long personaId, long rolId) {
        if (actor == null || actor.usuarioId() == null || actor.sesionId() == null || actor.jwtExpiraEn() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        Usuario creador = usuarios.findForUpdate(actor.usuarioId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida"));
        OffsetDateTime ahora = reloj.ahora();
        Integer validas = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sesiones_usuario s JOIN usuarios u ON u.id_usuario = s.usuario_id
                JOIN roles r ON r.id_rol = u.rol_id
                WHERE s.sesion_id = ? AND s.usuario_id = ? AND s.activa = 1
                  AND s.fecha_expiracion_instante > ? AND u.activo = 1 AND r.nombre_rol = 'ADMINISTRADOR'
                """, Integer.class, actor.sesionId(), actor.usuarioId(), ahora);
        if (!actor.jwtExpiraEn().isAfter(ahora) || validas == null || validas != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        Persona persona = personas.findForUpdate(personaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona no encontrada"));
        if (usuarios.existsByPersona_IdPersona(personaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La persona ya tiene una cuenta");
        }
        String email = persona.getEmail();
        if (email == null || email.isBlank() || !email.equals(email.trim())
                || !validator.validateValue(Correo.class, "email", email).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Correo de persona invalido");
        }
        var rol = roles.findById(rolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rol no encontrado"));
        String token = tokens.generarTokenSeguro();
        Invitacion invitacion = new Invitacion();
        invitacion.setPersona(persona);
        invitacion.setEmailDestinatario(email);
        invitacion.setRol(rol);
        invitacion.setCreadoPorUsuario(creador);
        invitacion.setTokenHash(tokens.hashTokenSHA256(token));
        invitacion.setFechaCreacion(ahora);
        invitacion.setFechaExpiracion(ahora.plusHours(48));
        invitaciones.save(invitacion);
        return new Enlace(email, token, invitacion.getFechaExpiracion());
    }

    @Transactional
    public void activar(String token, String password) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || password == null || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalida();
        }
        String hash = tokens.hashTokenSHA256(token);
        Long personaId = invitaciones.findPersonaIdByTokenHash(hash).orElseThrow(this::invalida);
        Persona persona = personas.findForUpdate(personaId).orElseThrow(this::invalida);
        Invitacion invitacion = invitaciones.findByTokenHash(hash).orElseThrow(this::invalida);
        OffsetDateTime ahora = reloj.ahora();
        if (invitacion.isUsada() || ahora.isBefore(invitacion.getFechaCreacion())
                || !ahora.isBefore(invitacion.getFechaExpiracion())
                || !persona.getEmail().equals(invitacion.getEmailDestinatario())
                || !invitaciones.findUltimaIdPorPersona(personaId).orElseThrow(this::invalida)
                        .equals(invitacion.getIdInvitacion())
                || !invitaciones.findUltimaIdPorEmail(persona.getEmail()).orElseThrow(this::invalida)
                        .equals(invitacion.getIdInvitacion())
                || usuarios.existsByPersona_IdPersona(personaId)) {
            throw invalida();
        }
        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        usuario.setRol(invitacion.getRol());
        usuario.setPassword(encoder.encode(password));
        usuario.setActivo(1);
        usuarios.save(usuario);
        invitacion.setUsada(true);
        invitacion.setFechaUso(ahora);
    }

    private ResponseStatusException invalida() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enlace o contrasena invalidos");
    }

    private record Correo(@Email String email) {}

    public record Enlace(String email, String token, OffsetDateTime fechaExpiracion) {
        @Override public String toString() { return "Enlace[fechaExpiracion=" + fechaExpiracion + "]"; }
    }
}
