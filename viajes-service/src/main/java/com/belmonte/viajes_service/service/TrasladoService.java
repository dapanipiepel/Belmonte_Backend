package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.AsignacionRequest;
import com.belmonte.viajes_service.dto.CancelacionRequest;
import com.belmonte.viajes_service.dto.EstadoRequest;
import com.belmonte.viajes_service.dto.TrasladoRequest;
import com.belmonte.viajes_service.dto.UbicacionRequest;
import com.belmonte.viajes_service.model.EstadoTraslado;
import com.belmonte.viajes_service.model.HistorialEstado;
import com.belmonte.viajes_service.model.Traslado;
import com.belmonte.viajes_service.model.Ubicacion;
import com.belmonte.viajes_service.repository.EstadoTrasladoRepository;
import com.belmonte.viajes_service.repository.BarreraRecursoRepository;
import com.belmonte.viajes_service.repository.HistorialEstadoRepository;
import com.belmonte.viajes_service.repository.TrasladoRepository;
import com.belmonte.viajes_service.repository.UbicacionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
public class TrasladoService {

    private static final Duration SEPARACION = Duration.ofMinutes(1);

    private final TrasladoRepository trasladoRepository;
    private final UbicacionRepository ubicacionRepository;
    private final EstadoTrasladoRepository estadoRepository;
    private final HistorialEstadoRepository historialRepository;
    private final BarreraRecursoRepository barreras;

    public TrasladoService(TrasladoRepository trasladoRepository,
                           UbicacionRepository ubicacionRepository,
                           EstadoTrasladoRepository estadoRepository,
                           HistorialEstadoRepository historialRepository,
                           BarreraRecursoRepository barreras) {
        this.trasladoRepository = trasladoRepository;
        this.ubicacionRepository = ubicacionRepository;
        this.estadoRepository = estadoRepository;
        this.historialRepository = historialRepository;
        this.barreras = barreras;
    }

    @Transactional
    public Traslado crear(TrasladoRequest request, Long solicitanteId) {
        validarHorario(request.fechaHoraViaje(),
                request.fechaHoraTerminoEstimado());
        validarCoordenadas(request.origen());
        validarCoordenadas(request.destino());

        if (request.origen().comunaId().equals(request.destino().comunaId())
                && request.origen().direccion().equalsIgnoreCase(
                        request.destino().direccion())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Origen y destino deben ser diferentes");
        }

        Ubicacion origen = ubicacionRepository.save(
                crearUbicacion(request.origen()));
        Ubicacion destino = ubicacionRepository.save(
                crearUbicacion(request.destino()));

        Traslado traslado = new Traslado(
                solicitanteId,
                origen,
                destino,
                request.fechaHoraViaje(),
                request.fechaHoraTerminoEstimado(),
                request.numeroVuelo(),
                request.pasajeroIds());
        traslado = trasladoRepository.saveAndFlush(traslado);

        EstadoTraslado pendiente = estadoRepository.findByCodigo("PENDIENTE")
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Estado inicial PENDIENTE no configurado"));
        historialRepository.save(new HistorialEstado(
                traslado, pendiente, solicitanteId, null));

        return traslado;
    }

    public List<Traslado> misSolicitudes(Long solicitanteId) {
        return trasladoRepository
                .findBySolicitanteIdOrderByFechaHoraViajeDesc(solicitanteId);
    }

    public List<Traslado> misViajes(Long conductorId) {
        return trasladoRepository
                .findByConductorIdOrderByFechaHoraViajeDesc(conductorId);
    }

    public List<Traslado> buscarTodos() {
        return trasladoRepository.findAll();
    }

    public List<Traslado> buscarPendientes() {
        return trasladoRepository.findAll().stream()
                .filter(traslado -> "PENDIENTE".equals(estadoActual(traslado)))
                .toList();
    }

    public Optional<Traslado> buscar(Long id) {
        return trasladoRepository.findById(id);
    }

    public String estadoActual(Traslado traslado) {
        return historialRepository
                .findByTraslado_IdOrderByFechaCambioDescIdDesc(traslado.getId())
                .stream()
                .findFirst()
                .map(historial -> historial.getEstado().getCodigo())
                .orElse("PENDIENTE");
    }

    public Traslado asignar(Long trasladoId, AsignacionRequest request, Long actorId,
                            String barreraConductor, String barreraVehiculo) {
        bloquearOperaciones();
        Traslado traslado = trasladoRepository.findById(trasladoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Traslado no encontrado"));
        String estado = estadoActual(traslado);
        if (!"PENDIENTE".equals(estado) && !"ASIGNADO".equals(estado)
                && !"EN_CURSO".equals(estado)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El traslado no admite reasignacion");
        }
        if (barreras.existsByTipoRecursoAndRecursoIdAndEstadoAndOperacionIdNot(
                "CONDUCTOR", request.conductorId(), "ABIERTA", barreraConductor)
                || barreras.existsByTipoRecursoAndRecursoIdAndEstadoAndOperacionIdNot(
                        "VEHICULO", request.vehiculoId(), "ABIERTA", barreraVehiculo)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recurso con operacion pendiente");
        }

        OffsetDateTime inicio = traslado.getFechaHoraViaje()
                .withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime termino = traslado.getFechaHoraTerminoEstimado()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .plus(SEPARACION);

        verificarSolapamientos(
                request.conductorId(), request.vehiculoId(),
                trasladoId, inicio, termino);
        if ("EN_CURSO".equals(estado)) {
            verificarRecursosEnCurso(request.conductorId(), request.vehiculoId(), trasladoId);
        }

        traslado.asignar(request.conductorId(), request.vehiculoId());
        traslado = trasladoRepository.saveAndFlush(traslado);
        registrarEstado(
                traslado, "EN_CURSO".equals(estado) ? "EN_CURSO" : "ASIGNADO", actorId,
                "PENDIENTE".equals(estado) ? null : "Reasignacion");
        return traslado;
    }

    Traslado cambiarEstado(Long trasladoId, EstadoRequest request, Long actorId) {
        Traslado traslado = trasladoRepository.findById(trasladoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Traslado no encontrado"));
        if (!actorEsConductor(traslado, actorId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Solo el conductor asignado puede cambiar el estado");
        }

        String estadoActual = estadoActual(traslado);
        String nuevo = request.estado();
        if (!transicionPermitida(estadoActual, nuevo)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Transicion invalida desde " + estadoActual);
        }

        if ("EN_CURSO".equals(nuevo)) {
            verificarRecursosEnCurso(traslado.getConductorId(), traslado.getVehiculoId(), trasladoId);
        }

        registrarEstado(
                traslado, nuevo, actorId,
                request.motivo());
        return traslado;
    }

    Traslado cancelar(Long trasladoId, CancelacionRequest request, Long actorId,
                      boolean administrador, boolean solicitante, boolean conductor) {
        Traslado traslado = trasladoRepository.findById(trasladoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Traslado no encontrado"));
        if (!administrador && !(solicitante && traslado.getSolicitanteId().equals(actorId))
                && !(conductor && actorId.equals(traslado.getConductorId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sin permiso para cancelar el traslado");
        }
        String estado = estadoActual(traslado);
        if (!List.of("PENDIENTE", "ASIGNADO", "EN_CURSO").contains(estado)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El traslado no admite cancelacion");
        }
        if (!administrador && !(solicitante && traslado.getSolicitanteId().equals(actorId))
                && "PENDIENTE".equals(estado)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El conductor no puede cancelar un traslado pendiente");
        }
        registrarEstado(
                traslado, "CANCELADO", actorId, request.motivo());
        return traslado;
    }

    private Ubicacion crearUbicacion(UbicacionRequest request) {
        return new Ubicacion(
                request.comunaId(),
                request.direccion(),
                request.latitud(),
                request.longitud());
    }

    private void validarCoordenadas(UbicacionRequest request) {
        if ((request.latitud() == null) != (request.longitud() == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Latitud y longitud deben suministrarse juntas");
        }
    }

    private void validarHorario(OffsetDateTime inicio, OffsetDateTime termino) {
        if (inicio == null || termino == null
                || !termino.isAfter(inicio)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El termino debe ser posterior al inicio");
        }
    }

    private void verificarSolapamientos(Long conductorId, Long vehiculoId,
                                        Long trasladoId,
                                        OffsetDateTime inicio,
                                        OffsetDateTime termino) {
        List<Traslado> conductor = trasladoRepository.conductorSolapado(
                conductorId, inicio.minus(SEPARACION), termino);
        if (conductor.stream().anyMatch(t -> !t.getId().equals(trasladoId)
                && List.of("PENDIENTE", "ASIGNADO", "EN_CURSO")
                        .contains(estadoActual(t)))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Conductor ocupado en el horario solicitado");
        }
        List<Traslado> vehiculo = trasladoRepository.vehiculoSolapado(
                vehiculoId, inicio.minus(SEPARACION), termino);
        if (vehiculo.stream().anyMatch(t -> !t.getId().equals(trasladoId)
                && List.of("PENDIENTE", "ASIGNADO", "EN_CURSO")
                        .contains(estadoActual(t)))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Vehiculo ocupado en el horario solicitado");
        }
    }

    void bloquearOperaciones() {
        estadoRepository.lockOperaciones().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Estado PENDIENTE no configurado"));
    }

    private void verificarRecursosEnCurso(Long conductorId, Long vehiculoId, Long trasladoId) {
        boolean ocupado = trasladoRepository.findByConductorId(conductorId).stream()
                .anyMatch(t -> !t.getId().equals(trasladoId) && "EN_CURSO".equals(estadoActual(t)))
                || trasladoRepository.findByVehiculoId(vehiculoId).stream()
                .anyMatch(t -> !t.getId().equals(trasladoId) && "EN_CURSO".equals(estadoActual(t)));
        if (ocupado) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hay un viaje en curso con esos recursos");
        }
    }

    private void registrarEstado(Traslado traslado, String codigo,
                                 Long actorId, String motivo) {
        EstadoTraslado estado = estadoRepository.findByCodigo(codigo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Estado no configurado: " + codigo));
        historialRepository.save(new HistorialEstado(
                traslado, estado, actorId, motivo));
    }

    private boolean transicionPermitida(String actual, String nuevo) {
        return switch (actual) {
            case "ASIGNADO" -> "EN_CURSO".equals(nuevo);
            case "EN_CURSO" -> "FINALIZADO".equals(nuevo);
            default -> false;
        };
    }

    private boolean actorEsConductor(Traslado traslado, Long actorId) {
        return traslado.getConductorId() != null
                && traslado.getConductorId().equals(actorId);
    }
}
