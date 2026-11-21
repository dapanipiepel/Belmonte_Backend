package com.belmonte.viajes_service.dto;

import com.belmonte.viajes_service.model.Traslado;

import java.time.OffsetDateTime;
import java.util.Set;

public record TrasladoResponse(
        Long id,
        Long solicitanteId,
        Long conductorId,
        Long vehiculoId,
        UbicacionResponse origen,
        UbicacionResponse destino,
        OffsetDateTime fechaHoraViaje,
        OffsetDateTime fechaHoraTerminoEstimado,
        String numeroVuelo,
        Set<Long> pasajeroIds,
        String estado) {

    public static TrasladoResponse from(Traslado traslado, String estado) {
        return new TrasladoResponse(
                traslado.getId(),
                traslado.getSolicitanteId(),
                traslado.getConductorId(),
                traslado.getVehiculoId(),
                UbicacionResponse.from(traslado.getOrigen()),
                UbicacionResponse.from(traslado.getDestino()),
                traslado.getFechaHoraViaje(),
                traslado.getFechaHoraTerminoEstimado(),
                traslado.getNumeroVuelo(),
                traslado.getPasajeros(),
                estado);
    }
}
