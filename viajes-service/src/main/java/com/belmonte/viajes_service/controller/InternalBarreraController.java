package com.belmonte.viajes_service.controller;

import com.belmonte.viajes_service.security.InternalApiAuthentication;
import com.belmonte.viajes_service.service.BarreraRecursoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/barreras")
public class InternalBarreraController {
    private final BarreraRecursoService barreras;
    private final InternalApiAuthentication authentication;

    public InternalBarreraController(BarreraRecursoService barreras, InternalApiAuthentication authentication) {
        this.barreras = barreras;
        this.authentication = authentication;
    }

    @PutMapping("/{operacionId}")
    public ResponseEntity<EstadoBarrera> preparar(@PathVariable String operacionId, @Valid @RequestBody Recurso request,
            @RequestHeader(value = "X-Belmonte-Internal-Secret", required = false) String secret) {
        authentication.requireValidSecret(secret);
        String estado = barreras.preparar(operacionId, request.tipo(), request.recursoId(), request.propositoEfectivo());
        return ResponseEntity.status("RECHAZADA".equals(estado) ? HttpStatus.CONFLICT : HttpStatus.OK)
                .body(new EstadoBarrera(estado));
    }

    @PostMapping("/{operacionId}/cerrar")
    public ResponseEntity<Void> cerrar(@PathVariable String operacionId, @Valid @RequestBody Recurso request,
            @RequestHeader(value = "X-Belmonte-Internal-Secret", required = false) String secret) {
        authentication.requireValidSecret(secret);
        barreras.cerrar(operacionId, request.tipo(), request.recursoId(), request.propositoEfectivo());
        return ResponseEntity.noContent().build();
    }

    public record Recurso(@NotBlank String tipo, @NotNull @Positive Long recursoId, String proposito) {
        public String propositoEfectivo() {
            return proposito == null ? "BAJA" : proposito;
        }
    }
    public record EstadoBarrera(String estado) {}
}
