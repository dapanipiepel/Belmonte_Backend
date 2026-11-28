
package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.model.Persona;
import com.belmonte.usuarios_service.model.Rol;
import com.belmonte.usuarios_service.model.Usuario;
import com.belmonte.usuarios_service.model.SesionUsuario;
import com.belmonte.usuarios_service.repository.PersonaRepository;
import com.belmonte.usuarios_service.repository.RolRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final PersonaRepository personaRepository;
    private final RolRepository rolRepository;
    private final SesionUsuarioRepository sesionUsuarioRepository;
    private final JwtService jwtService;
    private final EntityManager entityManager;
    private final RelojBaseDatos reloj;

    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    public UsuarioService(
            UsuarioRepository usuarioRepository,
            PersonaRepository personaRepository,
            RolRepository rolRepository,
            SesionUsuarioRepository sesionUsuarioRepository,
            JwtService jwtService,
            EntityManager entityManager,
            RelojBaseDatos reloj) {

        this.usuarioRepository = usuarioRepository;
        this.personaRepository = personaRepository;
        this.rolRepository = rolRepository;
        this.sesionUsuarioRepository = sesionUsuarioRepository;
        this.jwtService = jwtService;
        this.entityManager = entityManager;
        this.reloj = reloj;
    }

    @Transactional
    public Usuario registrarUsuario(Usuario usuario) {

        if (usuario.getIdUsuario() != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No se permite indicar el ID al registrar un usuario"
            );
        }

        if (usuario.getPersona() == null
                || usuario.getPersona().getIdPersona() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debe indicar una persona"
            );
        }

        if (usuario.getRol() == null
                || usuario.getRol().getIdRol() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debe indicar un rol"
            );
        }

        if (usuario.getPassword() == null
                || usuario.getPassword().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La contrasena es obligatoria"
            );
        }

        Persona persona = personaRepository
                .findForUpdate(usuario.getPersona().getIdPersona())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "La persona no existe"
                ));

        Rol rol = rolRepository
                .findById(usuario.getRol().getIdRol())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El rol no existe"
                ));

        if (usuarioRepository.existsByPersona_Email(
                persona.getEmail())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta persona ya tiene un usuario registrado"
            );
        }

        usuario.setPersona(persona);
        usuario.setRol(rol);

        usuario.setPassword(
                passwordEncoder.encode(usuario.getPassword())
        );

        usuario.setActivo(1);

        return usuarioRepository.save(usuario);
    }

    
public java.util.List<Usuario> listarUsuarios() {
    return usuarioRepository.findAll();
}


    @Transactional
    public Map<String, Object> iniciarSesion(String email, String password) {
        Long id = usuarioRepository.findIdByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Correo o contrasena incorrectos"));
        Usuario usuario = usuarioRepository.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Correo o contrasena incorrectos"));
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);

        if (!passwordEncoder.matches(password, usuario.getPassword())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Correo o contrasena incorrectos");
        }
        if (usuario.getActivo() == null || usuario.getActivo() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "La cuenta esta desactivada");
        }

        Instant instanteInicio = reloj.ahora().toInstant();
        LocalDateTime started = LocalDateTime.ofInstant(instanteInicio, ZoneId.systemDefault());
        SesionUsuario session = new SesionUsuario();
        session.setSesionId(UUID.randomUUID().toString());
        session.setUsuario(usuario);
        session.setFechaInicio(started);
        session.setFechaExpiracion(started.plusNanos(jwtService.getExpirationMillis() * 1_000_000));
        session.setFechaExpiracionInstante(instanteInicio.plusMillis(jwtService.getExpirationMillis())
                .truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
        session.setActiva(1);
        sesionUsuarioRepository.save(session);
        String token = jwtService.createToken(usuario.getPersona().getEmail(), usuario.getIdUsuario(),
                session.getSesionId(), List.of(usuario.getRol().getNombreRol()), instanteInicio);
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

    @Transactional
    public int revocarSesiones(Long usuarioId) {
        if (usuarioRepository.findForUpdate(usuarioId).isEmpty()) {
            return 0;
        }
        return sesionUsuarioRepository.revokeAllByUsuarioId(usuarioId);
    }

    @Transactional
    public void cerrarSesion(String sesionId) {
        var usuarioId = sesionUsuarioRepository.findUsuarioIdBySesionId(sesionId).orElse(null);
        if (usuarioId != null && usuarioRepository.findForUpdate(usuarioId).isPresent()) {
            sesionUsuarioRepository.revokeBySesionIdAndUsuarioId(sesionId, usuarioId);
        }
    }

}
