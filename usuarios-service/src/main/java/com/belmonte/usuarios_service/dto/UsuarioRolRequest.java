package com.belmonte.usuarios_service.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UsuarioRolRequest(@NotNull @Positive Long rolId) {}
