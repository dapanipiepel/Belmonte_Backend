package com.belmonte.viajes_service.dto;

import com.belmonte.viajes_service.model.Ubicacion;

import java.math.BigDecimal;

public record UbicacionResponse(
        Long id, Long comunaId, String direccion,
        BigDecimal latitud, BigDecimal longitud) {

    public static UbicacionResponse from(Ubicacion ubicacion) {
        return new UbicacionResponse(
                ubicacion.getId(),
                ubicacion.getComunaId(),
                ubicacion.getDireccion(),
                ubicacion.getLatitud(),
                ubicacion.getLongitud());
    }
}
