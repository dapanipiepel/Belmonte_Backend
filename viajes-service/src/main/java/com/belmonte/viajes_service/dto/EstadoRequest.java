package com.belmonte.viajes_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EstadoRequest(
        @NotBlank String estado,
        @Size(max = 500) String motivo) {}
