package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.security.InternalApiAuthentication;
import com.belmonte.usuarios_service.service.ComunaService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalComunaController {
    private final ComunaService comunas;
    private final InternalApiAuthentication authentication;

    public InternalComunaController(ComunaService comunas, InternalApiAuthentication authentication) {
        this.comunas = comunas;
        this.authentication = authentication;
    }

    @GetMapping("/internal/comunas/{id}")
    public ComunaService.Comuna consultar(
            @PathVariable Long id,
            @RequestHeader(value = "X-Belmonte-Internal-Secret", required = false) String secret) {
        authentication.requireValidSecret(secret);
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comuna invalida");
        }
        return comunas.buscar(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Comuna no encontrada"));
    }
}
