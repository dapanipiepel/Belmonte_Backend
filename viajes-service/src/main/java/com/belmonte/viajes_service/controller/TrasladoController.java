package com.belmonte.viajes_service.controller;

import com.belmonte.viajes_service.dto.AsignacionRequest;
import com.belmonte.viajes_service.dto.CancelacionRequest;
import com.belmonte.viajes_service.dto.EstadoRequest;
import com.belmonte.viajes_service.dto.TrasladoRequest;
import com.belmonte.viajes_service.dto.TrasladoResponse;
import com.belmonte.viajes_service.service.TrasladoService;
import com.belmonte.viajes_service.service.AsignacionIdempotenteService;
import com.belmonte.viajes_service.service.AsignacionOperacionTransacciones;
import com.belmonte.viajes_service.service.CreacionIdempotenteService;
import com.belmonte.viajes_service.service.CreacionOperacionTransacciones;
import com.belmonte.viajes_service.service.CambioTrasladoIdempotenteService;
import com.belmonte.viajes_service.service.CambioTrasladoTransacciones;
import com.belmonte.viajes_service.security.IdentidadSesion;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/traslados")
public class TrasladoController {

    private final TrasladoService trasladoService;
    private final AsignacionIdempotenteService asignaciones;
    private final CreacionIdempotenteService creaciones;
    private final CambioTrasladoIdempotenteService cambios;
    private final SecretKey key;

    public TrasladoController(TrasladoService trasladoService,
                              AsignacionIdempotenteService asignaciones,
                              CreacionIdempotenteService creaciones,
                              CambioTrasladoIdempotenteService cambios,
                              @Value("${jwt.secret}") String secret) {
        this.trasladoService = trasladoService;
        this.asignaciones = asignaciones;
        this.creaciones = creaciones;
        this.cambios = cambios;
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo configurar JWT", exception);
        }
    }

    @PostMapping
    public ResponseEntity<String> crear(
            @Valid @RequestBody TrasladoRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long usuarioId = usuarioId(authorization);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof IdentidadSesion identidad)
                || identidad.sesionId() == null || identidad.sesionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        var resultado = creaciones.crear(key, usuarioId, identidad.sesionId(), identidad.jwtExpiraEn(), request);
        return ResponseEntity.status(resultado.codigo()).contentType(MediaType.APPLICATION_JSON)
                .body(resultado.cuerpo());
    }

    @GetMapping("/operaciones/creacion")
    public CreacionOperacionTransacciones.Consulta consultarCreacion(
            @RequestParam("clave") String clave,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return creaciones.consultar(clave, usuarioId(authorization));
    }

    @GetMapping
    public List<TrasladoResponse> listar(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long usuarioId = usuarioId(authorization);
        if (hasRole("ADMINISTRADOR")) {
            return trasladoService.buscarTodos().stream()
                    .map(t -> TrasladoResponse.from(t,
                            trasladoService.estadoActual(t)))
                    .toList();
        }
        if (hasRole("CONDUCTOR")) {
            return trasladoService.misViajes(usuarioId).stream()
                    .map(t -> TrasladoResponse.from(t,
                            trasladoService.estadoActual(t)))
                    .toList();
        }
        return trasladoService.misSolicitudes(usuarioId).stream()
                .map(t -> TrasladoResponse.from(t,
                        trasladoService.estadoActual(t)))
                .toList();
    }

    @GetMapping("/pendientes")
    public List<TrasladoResponse> pendientes() {
        if (!hasRole("ADMINISTRADOR")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Solo administradores pueden consultar pendientes");
        }
        return trasladoService.buscarPendientes().stream()
                .map(t -> TrasladoResponse.from(t,
                        trasladoService.estadoActual(t)))
                .toList();
    }

    @GetMapping("/{id}")
    public TrasladoResponse detalle(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long usuarioId = usuarioId(authorization);
        var traslado = trasladoService.buscar(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Traslado no encontrado"));
        if (!hasRole("ADMINISTRADOR")
                && !traslado.getSolicitanteId().equals(usuarioId)
                && !usuarioId.equals(traslado.getConductorId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Sin permiso para consultar el traslado");
        }
        return TrasladoResponse.from(
                traslado, trasladoService.estadoActual(traslado));
    }

    @PutMapping("/{id}/asignar")
    public ResponseEntity<String> asignar(
            @PathVariable Long id,
            @Valid @RequestBody AsignacionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!hasRole("ADMINISTRADOR")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Solo administradores pueden asignar");
        }
        Long usuarioId = usuarioId(authorization);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof IdentidadSesion identidad)
                || identidad.sesionId() == null || identidad.sesionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        var resultado = asignaciones.asignar(idempotencyKey, usuarioId, identidad.sesionId(), id, request,
                identidad.jwtExpiraEn());
        return ResponseEntity.status(resultado.codigo()).contentType(MediaType.APPLICATION_JSON)
                .body(resultado.cuerpo());
    }

    @GetMapping("/operaciones/asignacion")
    public AsignacionOperacionTransacciones.Consulta consultarAsignacion(
            @RequestParam("clave") String clave,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!hasRole("ADMINISTRADOR")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo administradores pueden consultar");
        }
        return asignaciones.consultar(clave, usuarioId(authorization));
    }

    @PatchMapping("/{id}/estado")
    public ResponseEntity<String> cambiarEstado(
            @PathVariable Long id,
            @Valid @RequestBody EstadoRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!hasRole("CONDUCTOR")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Solo conductores pueden cambiar el estado");
        }
        Long usuarioId = usuarioId(authorization);
        var resultado = cambios.cambiarEstado(idempotencyKey, usuarioId, id, request);
        return ResponseEntity.status(resultado.codigo()).contentType(MediaType.APPLICATION_JSON).body(resultado.cuerpo());
    }

    @PostMapping("/{id}/cancelar")
    public ResponseEntity<String> cancelar(
            @PathVariable Long id,
            @Valid @RequestBody CancelacionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Long usuarioId = usuarioId(authorization);
        var resultado = cambios.cancelar(idempotencyKey, usuarioId, id, request,
                hasRole("ADMINISTRADOR"), hasRole("SOLICITANTE"), hasRole("CONDUCTOR"));
        return ResponseEntity.status(resultado.codigo()).contentType(MediaType.APPLICATION_JSON).body(resultado.cuerpo());
    }

    @GetMapping("/operaciones/cancelacion")
    public ResponseEntity<CambioTrasladoTransacciones.Consulta> consultarCancelacion(
            @RequestParam("clave") String clave,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(cambios.consultar(clave, usuarioId(authorization), "cancelar"));
    }

    @GetMapping("/operaciones/estado")
    public ResponseEntity<CambioTrasladoTransacciones.Consulta> consultarEstado(
            @RequestParam("clave") String clave,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(cambios.consultar(clave, usuarioId(authorization), "estado"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> solicitudInvalida() {
        return ResponseEntity.badRequest().body(Map.of("error", "Solicitud de traslado invalida"));
    }

    private Long usuarioId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Token requerido");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(authorization.substring(7))
                    .getPayload();
            Object usuarioId = claims.get("usuarioId");
            if (usuarioId instanceof Number number) {
                return number.longValue();
            }
            throw new IllegalArgumentException("usuarioId invalido");
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Token invalido");
        }
    }

    private boolean hasRole(String role) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("ROLE_" + role));
    }
}
