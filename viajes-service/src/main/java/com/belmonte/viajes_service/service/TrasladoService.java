package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.AsignacionRequest;
import com.belmonte.viajes_service.dto.CancelacionRequest;
import com.belmonte.viajes_service.dto.EstadoRequest;
import com.belmonte.viajes_service.dto.TrasladoRequest;
import com.belmonte.viajes_service.dto.UbicacionRequest;
import com.belmonte.viajes_service.model.EstadoTraslado;
import com.belmonte.viajes_service.model.HistorialEstado;
import com.belmonte.viajes_service.model.OperacionTraslado;
import com.belmonte.viajes_service.model.RecursoTraslado;
import com.belmonte.viajes_service.model.Traslado;
import com.belmonte.viajes_service.model.Ubicacion;
import com.belmonte.viajes_service.repository.EstadoTrasladoRepository;
import com.belmonte.viajes_service.repository.HistorialEstadoRepository;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
import com.belmonte.viajes_service.repository.RecursoTrasladoRepository;
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
    private final OperacionTrasladoRepository operacionRepository;
    private final RecursoTrasladoRepository recursoRepository;

    public TrasladoService(TrasladoRepository trasladoRepository,
                           UbicacionRepository ubicacionRepository,
                           EstadoTrasladoRepository estadoRepository,
                           HistorialEstadoRepository historialRepository,
                           OperacionTrasladoRepository operacionRepository,
                           RecursoTrasladoRepository recursoRepository) {
        this.trasladoRepository = trasladoRepository;
        this.ubicacionRepository = ubicacionRepository;
        this.estadoRepository = estadoRepository;
        this.historialRepository = historialRepository;
        this.operacionRepository = operacionRepository;
        this.recursoRepository = recursoRepository;
    }

    @Transactional
    public Traslado crear(TrasladoRequest request, Long solicitanteId,
                          String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<OperacionTraslado> repetida =
                    operacionRepository.findByClave(idempotencyKey);
            if (repetida.isPresent()) {
                if (!repetida.get().getActorId().equals(solicitanteId)
                        || !"crear".equals(repetida.get().getOperacion())) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Clave de idempotencia reutilizada");
                }
                if (repetida.get().getTrasladoId() != null) {
                    return trasladoRepository.findById(repetida.get().getTrasladoId())
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Operacion confirmada pero traslado no disponible"));
                }
            }
        }

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

        if (idempotencyKey != null) {
            operacionRepository.save(new OperacionTraslado(
                    idempotencyKey, solicitanteId, "crear",
                    traslado.getId(), 201));
        }
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

    @Transactional
    public Traslado asignar(Long trasladoId, AsignacionRequest request,
                            Long actorId, String idempotencyKey) {
        validarClaveIdempotencia(idempotencyKey, actorId, "asignar");

        Traslado traslado = trasladoRepository.findById(trasladoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Traslado no encontrado"));
        String estado = estadoActual(traslado);
        if (!"PENDIENTE".equals(estado) && !"ASIGNADO".equals(estado)
                && !"EN_CURSO".equals(estado)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El traslado no admite reasignacion");
        }

        OffsetDateTime inicio = traslado.getFechaHoraViaje()
                .withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime termino = traslado.getFechaHoraTerminoEstimado()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .plus(SEPARACION);

        verificarSolapamientos(
                request.conductorId(), request.vehiculoId(),
                trasladoId, inicio, termino);
        Long conductorAnterior = traslado.getConductorId();
        Long vehiculoAnterior = traslado.getVehiculoId();
        bloquearRecurso("CONDUCTOR", request.conductorId());
        bloquearRecurso("VEHICULO", request.vehiculoId());
        if (conductorAnterior != null
                && !conductorAnterior.equals(request.conductorId())) {
            liberarRecurso("CONDUCTOR", conductorAnterior);
        }
        if (vehiculoAnterior != null
                && !vehiculoAnterior.equals(request.vehiculoId())) {
            liberarRecurso("VEHICULO", vehiculoAnterior);
        }

        traslado.asignar(request.conductorId(), request.vehiculoId());
        traslado = trasladoRepository.saveAndFlush(traslado);
        registrarEstado(
                traslado, "ASIGNADO", actorId,
                "ASIGNADO".equals(estado) ? "Reasignacion" : null);
        registrarOperacion(idempotencyKey, actorId, "asignar",
                trasladoId, 200);
        return traslado;
    }

    @Transactional
    public Traslado cambiarEstado(Long trasladoId, EstadoRequest request,
                                  Long actorId, Long conductorId,
                                  String idempotencyKey) {
        validarClaveIdempotencia(idempotencyKey, actorId, "estado");

        Traslado traslado = trasladoRepository.findById(trasladoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Traslado no encontrado"));
        if (!actorEsConductor(traslado, conductorId, actorId)) {
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

        if ("FINALIZADO".equals(nuevo) || "CANCELADO".equals(nuevo)) {
            liberarRecurso("CONDUCTOR", traslado.getConductorId());
            liberarRecurso("VEHICULO", traslado.getVehiculoId());
        }

        registrarEstado(
                traslado, nuevo, actorId,
                request.motivo());
        registrarOperacion(idempotencyKey, actorId, "estado",
                trasladoId, 200);
        return traslado;
    }

    @Transactional
    public Traslado cancelar(Long trasladoId, CancelacionRequest request,
                             Long actorId, String idempotencyKey) {
        validarClaveIdempotencia(idempotencyKey, actorId, "cancelar");
        Traslado traslado = trasladoRepository.findById(trasladoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Traslado no encontrado"));
        String estado = estadoActual(traslado);
        if (!List.of("PENDIENTE", "ASIGNADO", "EN_CURSO").contains(estado)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El traslado no admite cancelacion");
        }
        if (traslado.getConductorId() != null) {
            liberarRecurso("CONDUCTOR", traslado.getConductorId());
            liberarRecurso("VEHICULO", traslado.getVehiculoId());
        }
        registrarEstado(
                traslado, "CANCELADO", actorId, request.motivo());
        registrarOperacion(idempotencyKey, actorId, "cancelar",
                trasladoId, 200);
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

    private void bloquearRecurso(String tipo, Long recursoId) {
        RecursoTraslado recurso = recursoRepository.findById(
                        new RecursoTraslado.RecursoId(tipo, recursoId))
                .orElse(new RecursoTraslado(tipo, recursoId, "LIBRE"));
        if ("BLOQUEADO".equals(recurso.getEstado())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El recurso esta bloqueado por una operacion");
        }
        recurso.setEstado("BLOQUEADO");
        recursoRepository.save(recurso);
    }

    private void liberarRecurso(String tipo, Long recursoId) {
        if (recursoId == null) return;
        RecursoTraslado recurso = recursoRepository.findById(
                        new RecursoTraslado.RecursoId(tipo, recursoId))
                .orElse(new RecursoTraslado(tipo, recursoId, "LIBRE"));
        recurso.setEstado("LIBRE");
        recursoRepository.save(recurso);
    }

    private void validarClaveIdempotencia(String clave, Long actorId,
                                          String operacion) {
        if (clave == null) return;
        operacionRepository.findByClave(clave).ifPresent(previa -> {
            if (!previa.getActorId().equals(actorId)
                    || !previa.getOperacion().equals(operacion)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Clave de idempotencia reutilizada");
            }
        });
    }

    private void registrarOperacion(String clave, Long actorId,
                                    String operacion, Long trasladoId,
                                    Integer respuestaCodigo) {
        if (clave != null) {
            operacionRepository.save(new OperacionTraslado(
                    clave, actorId, operacion,
                    trasladoId, respuestaCodigo));
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
            case "PENDIENTE" -> "ASIGNADO".equals(nuevo)
                    || "CANCELADO".equals(nuevo);
            case "ASIGNADO" -> "EN_CURSO".equals(nuevo)
                    || "CANCELADO".equals(nuevo);
            case "EN_CURSO" -> "FINALIZADO".equals(nuevo)
                    || "CANCELADO".equals(nuevo);
            default -> false;
        };
    }

    private boolean actorEsConductor(Traslado traslado,
                                     Long conductorId, Long actorId) {
        return traslado.getConductorId() != null
                && traslado.getConductorId().equals(conductorId)
                && traslado.getConductorId().equals(actorId);
    }
}
