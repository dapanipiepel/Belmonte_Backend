package com.belmonte.viajes_service.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AsignacionRequest(
        @NotNull @Positive Long conductorId,
        @NotNull @Positive Long vehiculoId) {}
