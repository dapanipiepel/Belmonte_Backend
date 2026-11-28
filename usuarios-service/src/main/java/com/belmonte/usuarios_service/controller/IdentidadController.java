package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.security.IdentidadSesion;
import com.belmonte.usuarios_service.security.LimiteIdentidad;
import com.belmonte.usuarios_service.service.CorreoIdentidadService;
import com.belmonte.usuarios_service.service.InvitacionService;
import com.belmonte.usuarios_service.service.RecuperacionPasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class IdentidadController {
    private static final Logger log = LoggerFactory.getLogger(IdentidadController.class);
    private final InvitacionService invitaciones;
    private final RecuperacionPasswordService recuperaciones;
    private final UsuarioRepository usuarios;
    private final CorreoIdentidadService correo;
    private final LimiteIdentidad limite;

    public IdentidadController(InvitacionService invitaciones, RecuperacionPasswordService recuperaciones,
            UsuarioRepository usuarios, CorreoIdentidadService correo, LimiteIdentidad limite) {
        this.invitaciones = invitaciones;
        this.recuperaciones = recuperaciones;
        this.usuarios = usuarios;
        this.correo = correo;
        this.limite = limite;
    }

    @PostMapping("/api/invitaciones")
    public ResponseEntity<Map<String, Object>> invitar(@Valid @RequestBody InvitacionRequest request,
            Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof IdentidadSesion actor)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        var enlace = invitaciones.emitir(actor, request.personaId(), request.rolId());
        try {
            correo.invitacion(enlace.email(), enlace.token());
        } catch (MailException exception) {
            log.warn("No se pudo enviar el correo de invitacion: {}", exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo enviar la invitacion");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(Map.of("fechaExpiracion", enlace.fechaExpiracion()));
    }

    @PostMapping("/api/auth/activar")
    public ResponseEntity<Map<String, String>> activar(@Valid @RequestBody TokenRequest request,
            HttpServletRequest servletRequest) {
        limite.comprobar(servletRequest.getRemoteAddr());
        invitaciones.activar(request.token(), request.password());
        return respuesta(HttpStatus.OK, "Cuenta activada");
    }

    @PostMapping("/api/auth/recuperacion")
    public ResponseEntity<Map<String, String>> solicitar(@Valid @RequestBody CorreoRequest request,
            HttpServletRequest servletRequest) {
        limite.comprobar(servletRequest.getRemoteAddr());
        usuarios.findIdByEmail(request.email().trim()).ifPresent(id -> {
            try {
                var enlace = recuperaciones.emitirParaEmail(id, request.email().trim());
                correo.recuperacion(request.email().trim(), enlace.token());
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 404) throw exception;
            } catch (MailException exception) {
                log.warn("No se pudo enviar el correo de recuperacion: {}", exception.getClass().getSimpleName());
            }
        });
        return respuesta(HttpStatus.ACCEPTED, "Si la cuenta esta disponible, recibira un correo");
    }

    @PostMapping("/api/auth/recuperacion/cambiar")
    public ResponseEntity<Map<String, String>> cambiar(@Valid @RequestBody TokenRequest request,
            HttpServletRequest servletRequest) {
        limite.comprobar(servletRequest.getRemoteAddr());
        recuperaciones.cambiarPassword(request.token(), request.password());
        return respuesta(HttpStatus.OK, "Contrasena actualizada");
    }

    private ResponseEntity<Map<String, String>> respuesta(HttpStatus status, String mensaje) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("mensaje", mensaje));
    }

    public record InvitacionRequest(@NotNull @Positive Long personaId, @NotNull @Positive Long rolId) {}
    public record CorreoRequest(@NotBlank @Email @Size(max = 120) String email) {}
    public record TokenRequest(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 72) String password) {}
}
