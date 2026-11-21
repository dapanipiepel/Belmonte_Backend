package com.belmonte.usuarios_service.dto;

import jakarta.validation.constraints.NotNull;

public record UsuarioEstadoRequest(@NotNull Boolean activo) {}
