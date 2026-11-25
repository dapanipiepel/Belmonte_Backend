package com.belmonte.vehiculos_service.security;

import java.time.OffsetDateTime;

public record IdentidadSesion(Long usuarioId, String sesionId, OffsetDateTime jwtExpiraEn) {}
