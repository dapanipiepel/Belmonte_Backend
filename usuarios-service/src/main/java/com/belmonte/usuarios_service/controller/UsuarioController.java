
package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.model.Usuario;
import com.belmonte.usuarios_service.service.UsuarioService;
import com.belmonte.usuarios_service.service.CambioUsuarioIdempotenteService;
import com.belmonte.usuarios_service.service.CambioUsuarioTransacciones;
import com.belmonte.usuarios_service.security.IdentidadSesion;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;
    private final CambioUsuarioIdempotenteService cambios;

    public UsuarioController(UsuarioService usuarioService, CambioUsuarioIdempotenteService cambios) {
        this.usuarioService = usuarioService;
        this.cambios = cambios;
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
    public ResponseEntity<String> cambiarEstado(
            @PathVariable("id") Long usuarioId,
            @Valid @RequestBody UsuarioEstadoRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String clave) {
        var actor = identidad();
        var resultado = cambios.cambiarEstado(clave, actor.usuarioId(), actor.sesionId(), usuarioId,
                request.activo(), actor.jwtExpiraEn());
        return ResponseEntity.status(resultado.codigo()).contentType(MediaType.APPLICATION_JSON).body(resultado.cuerpo());
    }

    @PatchMapping("/{id}/rol")
    public ResponseEntity<String> cambiarRol(
            @PathVariable("id") Long usuarioId,
            @Valid @RequestBody UsuarioRolRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String clave) {
        var actor = identidad();
        var resultado = cambios.cambiarRol(clave, actor.usuarioId(), actor.sesionId(), usuarioId,
                request.rolId(), actor.jwtExpiraEn());
        return ResponseEntity.status(resultado.codigo()).contentType(MediaType.APPLICATION_JSON).body(resultado.cuerpo());
    }

    @GetMapping("/operaciones/cambio")
    public CambioUsuarioTransacciones.Consulta consultarOperacion(@RequestParam String clave) {
        return cambios.consultar(clave, identidad().usuarioId());
    }

    private IdentidadSesion identidad() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof IdentidadSesion identidad)
                || identidad.usuarioId() == null || identidad.usuarioId() <= 0
                || identidad.sesionId() == null || identidad.sesionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identidad de usuario invalida");
        }
        return identidad;
    }
}
