package com.belmonte.usuarios_service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BusquedaPasajeroRequest(
        @NotNull Campo campo,
        @NotBlank @Size(max = 120) String texto,
        @NotNull @Min(0) Integer pagina,
        @NotNull @Min(1) @Max(50) Integer tamano) {
    public enum Campo { NOMBRE, DOCUMENTO, CORREO, EMPRESA }
}
