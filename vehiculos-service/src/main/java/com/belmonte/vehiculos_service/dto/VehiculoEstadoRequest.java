package com.belmonte.vehiculos_service.dto;

import jakarta.validation.constraints.NotNull;

public record VehiculoEstadoRequest(@NotNull Boolean activo) {}
