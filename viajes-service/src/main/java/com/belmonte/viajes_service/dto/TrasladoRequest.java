package com.belmonte.viajes_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.Set;

public record TrasladoRequest(
        @Valid @NotNull UbicacionRequest origen,
        @Valid @NotNull UbicacionRequest destino,
        @NotNull OffsetDateTime fechaHoraViaje,
        @NotNull OffsetDateTime fechaHoraTerminoEstimado,
        String numeroVuelo,
        @NotEmpty Set<@NotNull @Positive Long> pasajeroIds) {}
