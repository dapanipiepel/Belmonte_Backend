package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.security.InternalApiAuthentication;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalConductorController {
    private final UsuarioRepository usuarios;
    private final InternalApiAuthentication authentication;

    public InternalConductorController(UsuarioRepository usuarios, InternalApiAuthentication authentication) {
        this.usuarios = usuarios;
        this.authentication = authentication;
    }

    @GetMapping("/internal/conductores/{usuarioId}")
    @Transactional(readOnly = true)
    public ConductorResponse consultar(
            @PathVariable Long usuarioId,
            @RequestHeader(value = "X-Belmonte-Internal-Secret", required = false) String secret) {
        authentication.requireValidSecret(secret);
        var usuario = usuarios.findById(usuarioId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        return new ConductorResponse(usuario.getIdUsuario(), usuario.getRol().getNombreRol(),
                Integer.valueOf(1).equals(usuario.getActivo()));
    }

    public record ConductorResponse(Long usuarioId, String rol, boolean activo) {}
}
