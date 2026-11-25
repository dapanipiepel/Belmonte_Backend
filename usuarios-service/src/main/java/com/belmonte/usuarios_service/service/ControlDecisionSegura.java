package com.belmonte.usuarios_service.service;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ControlDecisionSegura {
    public void exigirDisponibilidad() {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La certificacion durable requiere una fuente de tiempo independiente");
    }

    public Instant obtenerLimiteSuperior() {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La fuente de tiempo independiente no esta implementada");
    }
}
