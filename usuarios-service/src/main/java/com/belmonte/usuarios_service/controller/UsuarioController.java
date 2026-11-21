
package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.model.Usuario;
import com.belmonte.usuarios_service.service.UsuarioService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;

import java.util.Map;
import com.belmonte.usuarios_service.dto.UsuarioEstadoRequest;
import com.belmonte.usuarios_service.dto.UsuarioRolRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @PostMapping
    public Map<String, Object> registrarUsuario(
            @RequestBody Usuario usuario) {

        Usuario usuarioGuardado =
                usuarioService.registrarUsuario(usuario);

        return Map.of(
                "mensaje", "Usuario registrado correctamente",
                "idUsuario", usuarioGuardado.getIdUsuario(),
                "idPersona",
                usuarioGuardado.getPersona().getIdPersona(),
                "rol", usuarioGuardado.getRol().getNombreRol(),
                "activo", usuarioGuardado.getActivo()
        );
    }

    
@GetMapping
public List<Map<String, Object>> listarUsuarios() {

    return usuarioService.listarUsuarios()
            .stream()
            .map(usuario -> Map.<String, Object>of(
                    "idUsuario", usuario.getIdUsuario(),
                    "idPersona", usuario.getPersona().getIdPersona(),
                    "nombreCompleto", usuario.getPersona().getNombreCompleto(),
                    "email", usuario.getPersona().getEmail(),
                    "rol", usuario.getRol().getNombreRol(),
                    "activo", usuario.getActivo()
            ))
            .toList();
}
    
    @PostMapping("/{id}/revocar-sesiones")
    public Map<String, Object> revocarSesiones(@PathVariable("id") Long usuarioId) {
        int sesionesRevocadas = usuarioService.revocarSesiones(usuarioId);
        return Map.of(
                "usuarioId", usuarioId,
                "sesionesRevocadas", sesionesRevocadas
        );
    }

    @PatchMapping("/{id}/estado")
    public Map<String, Object> cambiarEstado(
            @PathVariable("id") Long usuarioId,
            @Valid @RequestBody UsuarioEstadoRequest request) {
        Usuario usuario = usuarioService.cambiarEstado(usuarioId, request.activo());
        return Map.of(
                "idUsuario", usuario.getIdUsuario(),
                "activo", usuario.getActivo()
        );
    }

    @PatchMapping("/{id}/rol")
    public Map<String, Object> cambiarRol(
            @PathVariable("id") Long usuarioId,
            @Valid @RequestBody UsuarioRolRequest request) {
        Usuario usuario = usuarioService.cambiarRol(usuarioId, request.rolId());
        return Map.of(
                "idUsuario", usuario.getIdUsuario(),
                "rol", usuario.getRol().getNombreRol()
        );
    }
}
