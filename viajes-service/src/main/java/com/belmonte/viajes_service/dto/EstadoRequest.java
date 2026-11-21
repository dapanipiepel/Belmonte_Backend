package com.belmonte.viajes_service.dto;

import jakarta.validation.constraints.NotBlank;

public record EstadoRequest(
        @NotBlank String estado,
        String motivo) {}
