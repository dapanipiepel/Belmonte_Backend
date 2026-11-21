package com.belmonte.viajes_service.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelacionRequest(@NotBlank String motivo) {}
