package com.belmonte.usuarios_service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmpresaRequest(
        @NotBlank @Size(max = 20) String rutEmpresa,
        @NotBlank @Size(max = 150) String razonSocial,
        @Size(max = 20) String telefono,
        @Email @Size(max = 120) String email) {}
