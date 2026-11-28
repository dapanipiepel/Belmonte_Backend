package com.belmonte.usuarios_service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PasajeroRequest(
        @NotBlank @Size(max = 100) String nombreCompleto,
        @NotNull @Positive Long paisDocumentoId,
        @NotNull @Positive Long tipoDocumentoId,
        @NotBlank @Size(max = 30) String numeroDocumento,
        @NotBlank @Email @Size(max = 120) String email,
        @NotBlank @Size(max = 20) String telefono,
        @NotNull @Positive Long empresaId) {}
