package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.dto.BusquedaPasajeroRequest;
import com.belmonte.usuarios_service.dto.PasajeroRequest;
import com.belmonte.usuarios_service.security.IdentidadSesion;
import com.belmonte.usuarios_service.service.PasajeroService;
import jakarta.validation.Valid;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/pasajeros")
public class PasajeroController {
    private static final Logger log = LoggerFactory.getLogger(PasajeroController.class);
    private final PasajeroService pasajeros;

    public PasajeroController(PasajeroService pasajeros) {
        this.pasajeros = pasajeros;
    }

    @PostMapping
    public ResponseEntity<PasajeroService.Pasajero> crear(@Valid @RequestBody PasajeroRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(pasajeros.crear(identidad(), request));
    }

    @PostMapping("/busquedas")
    public ResponseEntity<?> buscar(@Valid @RequestBody BusquedaPasajeroRequest request) {
        var resultado = pasajeros.buscar(identidad(), request);
        if (resultado.limitada()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "Limite de busquedas alcanzado"));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "pasajeros", resultado.pasajeros(), "pagina", resultado.pagina(), "hayMas", resultado.hayMas()));
    }

    private IdentidadSesion identidad() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof IdentidadSesion identidad)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        return identidad;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> solicitudInvalida() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Datos de pasajero invalidos"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> baseDeDatosNoDisponible(DataAccessException exception) {
        log.warn("No se pudo completar la operacion de pasajeros: {}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Operacion de pasajeros no disponible"));
    }
}
