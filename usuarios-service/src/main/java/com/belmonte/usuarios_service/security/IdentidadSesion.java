package com.belmonte.usuarios_service.security;

import java.time.OffsetDateTime;

public record IdentidadSesion(Long usuarioId, String sesionId, OffsetDateTime jwtExpiraEn) {}
