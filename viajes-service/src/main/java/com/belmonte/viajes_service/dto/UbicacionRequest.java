package com.belmonte.viajes_service.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record UbicacionRequest(
        @NotNull @Positive Long comunaId,
        @NotBlank String direccion,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitud,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitud) {}
