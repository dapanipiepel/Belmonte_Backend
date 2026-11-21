package com.belmonte.vehiculos_service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record VehiculoRequest(
        @NotNull @Positive Long tipoId,
        @NotNull @Positive Long modeloId,
        @NotBlank @Size(max = 10) String patente,
        @NotNull @Min(1) @Max(9999) Integer anio,
        @Size(max = 30) String color,
        @NotNull @Min(1) @Max(999) Integer capacidadPasajeros,
        Boolean activo) {}
