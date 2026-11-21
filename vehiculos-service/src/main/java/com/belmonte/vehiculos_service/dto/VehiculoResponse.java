package com.belmonte.vehiculos_service.dto;

import com.belmonte.vehiculos_service.model.Vehiculo;

public record VehiculoResponse(Long id, Long tipoId, String tipo, Long marcaId, String marca,
                              Long modeloId, String modelo, String patente, Integer anio,
                              String color, Integer capacidadPasajeros, Boolean activo) {
    public static VehiculoResponse from(Vehiculo vehiculo) {
        return new VehiculoResponse(vehiculo.getId(), vehiculo.getTipo().getId(),
                vehiculo.getTipo().getDescripcion(), vehiculo.getModelo().getMarca().getId(),
                vehiculo.getModelo().getMarca().getNombre(), vehiculo.getModelo().getId(),
                vehiculo.getModelo().getNombre(), vehiculo.getPatente(), vehiculo.getAnio(),
                vehiculo.getColor(), vehiculo.getCapacidadPasajeros(), vehiculo.getActivo());
    }
}
