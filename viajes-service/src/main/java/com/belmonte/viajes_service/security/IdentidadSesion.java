package com.belmonte.viajes_service.security;

import java.time.OffsetDateTime;

public record IdentidadSesion(String sesionId, OffsetDateTime jwtExpiraEn) {}
