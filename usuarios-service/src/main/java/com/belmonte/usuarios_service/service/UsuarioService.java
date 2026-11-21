
package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.model.Persona;
import com.belmonte.usuarios_service.model.Rol;
import com.belmonte.usuarios_service.model.Usuario;
import com.belmonte.usuarios_service.repository.PersonaRepository;
import com.belmonte.usuarios_service.repository.RolRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;

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

    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    public UsuarioService(
            UsuarioRepository usuarioRepository,
            PersonaRepository personaRepository,
            RolRepository rolRepository,
            SesionUsuarioRepository sesionUsuarioRepository) {

        this.usuarioRepository = usuarioRepository;
        this.personaRepository = personaRepository;
        this.rolRepository = rolRepository;
        this.sesionUsuarioRepository = sesionUsuarioRepository;
    }

    public Usuario registrarUsuario(Usuario usuario) {

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
                .findById(usuario.getPersona().getIdPersona())
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


public Usuario iniciarSesion(String email, String password) {

    Usuario usuario = usuarioRepository
            .findByPersona_Email(email)
            .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Correo o contrasena incorrectos"
            ));

    if (!passwordEncoder.matches(
            password, usuario.getPassword())) {
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Correo o contrasena incorrectos"
        );
    }

    if (usuario.getActivo() == null
            || usuario.getActivo() != 1) {
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "La cuenta esta desactivada"
        );
    }

    return usuario;
}

    public int revocarSesiones(Long usuarioId) {
        return sesionUsuarioRepository.revokeAllByUsuarioId(usuarioId);
    }

    @Transactional
    public Usuario cambiarEstado(Long usuarioId, boolean activo) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        usuario.setActivo(activo ? 1 : 0);
        if (!activo) {
            sesionUsuarioRepository.revokeAllByUsuarioId(usuarioId);
        }
        return usuarioRepository.save(usuario);
    }

    @Transactional
    public Usuario cambiarRol(Long usuarioId, Long rolId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        Rol rol = rolRepository.findById(rolId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Rol no encontrado"));
        if (!rol.getIdRol().equals(usuario.getRol().getIdRol())) {
            usuario.setRol(rol);
            usuarioRepository.save(usuario);
            sesionUsuarioRepository.revokeAllByUsuarioId(usuarioId);
        }
        return usuario;
    }

}
