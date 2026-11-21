package com.belmonte.viajes_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.belmonte.viajes_service.dto.TrasladoRequest;
import com.belmonte.viajes_service.dto.UbicacionRequest;
import com.belmonte.viajes_service.model.EstadoTraslado;
import com.belmonte.viajes_service.model.Traslado;
import com.belmonte.viajes_service.repository.EstadoTrasladoRepository;
import com.belmonte.viajes_service.repository.HistorialEstadoRepository;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
import com.belmonte.viajes_service.repository.RecursoTrasladoRepository;
import com.belmonte.viajes_service.repository.TrasladoRepository;
import com.belmonte.viajes_service.repository.UbicacionRepository;
import com.belmonte.viajes_service.service.TrasladoService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrasladoServiceTest {

    @Test
    void rejectsEndBeforeStart() {
        TrasladoRepository traslados = mock(TrasladoRepository.class);
        UbicacionRepository ubicaciones = mock(UbicacionRepository.class);
        EstadoTrasladoRepository estados = mock(EstadoTrasladoRepository.class);
        HistorialEstadoRepository historial = mock(HistorialEstadoRepository.class);
        OperacionTrasladoRepository operaciones =
                mock(OperacionTrasladoRepository.class);
        RecursoTrasladoRepository recursos =
                mock(RecursoTrasladoRepository.class);

        TrasladoService service = new TrasladoService(
                traslados, ubicaciones, estados, historial,
                operaciones, recursos);

        TrasladoRequest request = new TrasladoRequest(
                new UbicacionRequest(1L, "Origen", null, null),
                new UbicacionRequest(2L, "Destino", null, null),
                OffsetDateTime.parse("2026-12-01T10:00:00-03:00"),
                OffsetDateTime.parse("2026-12-01T09:00:00-03:00"),
                null,
                Set.of(101L));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.crear(request, 1L, null));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rejectsCoordinatePairPartial() {
        TrasladoRepository traslados = mock(TrasladoRepository.class);
        UbicacionRepository ubicaciones = mock(UbicacionRepository.class);
        EstadoTrasladoRepository estados = mock(EstadoTrasladoRepository.class);
        HistorialEstadoRepository historial = mock(HistorialEstadoRepository.class);
        OperacionTrasladoRepository operaciones =
                mock(OperacionTrasladoRepository.class);
        RecursoTrasladoRepository recursos =
                mock(RecursoTrasladoRepository.class);

        TrasladoService service = new TrasladoService(
                traslados, ubicaciones, estados, historial,
                operaciones, recursos);

        TrasladoRequest request = new TrasladoRequest(
                new UbicacionRequest(
                        1L, "Origen",
                        java.math.BigDecimal.valueOf(-33.4),
                        null),
                new UbicacionRequest(2L, "Destino", null, null),
                OffsetDateTime.parse("2026-12-01T10:00:00-03:00"),
                OffsetDateTime.parse("2026-12-01T11:00:00-03:00"),
                null,
                Set.of(101L));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.crear(request, 1L, null));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void createsPendingHistoryAtomically() {
        TrasladoRepository traslados = mock(TrasladoRepository.class);
        UbicacionRepository ubicaciones = mock(UbicacionRepository.class);
        EstadoTrasladoRepository estados = mock(EstadoTrasladoRepository.class);
        HistorialEstadoRepository historial = mock(HistorialEstadoRepository.class);
        OperacionTrasladoRepository operaciones =
                mock(OperacionTrasladoRepository.class);
        RecursoTrasladoRepository recursos =
                mock(RecursoTrasladoRepository.class);

        when(ubicaciones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(traslados.saveAndFlush(any())).thenAnswer(invocation -> {
            Traslado traslado = invocation.getArgument(0);
            var idField = Traslado.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(traslado, 10L);
            return traslado;
        });
        when(estados.findByCodigo("PENDIENTE"))
                .thenReturn(java.util.Optional.of(
                        new EstadoTraslado("PENDIENTE", "Pendiente")));

        TrasladoService service = new TrasladoService(
                traslados, ubicaciones, estados, historial,
                operaciones, recursos);

        TrasladoRequest request = new TrasladoRequest(
                new UbicacionRequest(1L, "Origen", null, null),
                new UbicacionRequest(2L, "Destino", null, null),
                OffsetDateTime.parse("2026-12-01T10:00:00-03:00"),
                OffsetDateTime.parse("2026-12-01T11:00:00-03:00"),
                null,
                Set.of(101L));

        Traslado creado = service.crear(request, 1L, null);
        assertEquals(10L, creado.getId());
    }
}
