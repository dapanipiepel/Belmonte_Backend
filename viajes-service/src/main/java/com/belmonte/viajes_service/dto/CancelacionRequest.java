package com.belmonte.viajes_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelacionRequest(@NotBlank @Size(max = 500) String motivo) {}
