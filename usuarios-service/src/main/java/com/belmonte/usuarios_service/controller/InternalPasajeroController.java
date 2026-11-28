package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.security.InternalApiAuthentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalPasajeroController {
    private final InternalApiAuthentication authentication;
    private final JdbcTemplate jdbc;

    public InternalPasajeroController(InternalApiAuthentication authentication, JdbcTemplate jdbc) {
        this.authentication = authentication;
        this.jdbc = jdbc;
    }

    @PostMapping("/internal/pasajeros/validar")
    public Resultado validar(@Valid @RequestBody Solicitud solicitud,
            @RequestHeader(value = "X-Belmonte-Internal-Secret", required = false) String secret) {
        authentication.requireValidSecret(secret);
        String parametros = String.join(",", Collections.nCopies(solicitud.ids().size(), "?"));
        List<Long> encontrados = jdbc.queryForList(
                "SELECT id_pasajero FROM pasajeros WHERE id_pasajero IN (" + parametros + ")",
                Long.class, solicitud.ids().toArray());
        if (encontrados.size() != solicitud.ids().size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pasajero no encontrado");
        }
        return new Resultado(encontrados.stream().sorted().toList());
    }

    public record Solicitud(@NotEmpty @Size(max = 50) Set<@NotNull @Positive Long> ids) {}
    public record Resultado(List<Long> ids) {}

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> solicitudInvalida() {
        return ResponseEntity.badRequest().body(Map.of("error", "Solicitud de pasajeros invalida"));
    }
}
