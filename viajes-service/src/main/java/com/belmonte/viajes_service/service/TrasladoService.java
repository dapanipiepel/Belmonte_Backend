package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.AsignacionRequest;
import com.belmonte.viajes_service.dto.CancelacionRequest;
import com.belmonte.viajes_service.dto.EstadoRequest;
import com.belmonte.viajes_service.dto.TrasladoRequest;
import com.belmonte.viajes_service.dto.UbicacionRequest;
import com.belmonte.viajes_service.model.EstadoTraslado;
import com.belmonte.viajes_service.model.HistorialEstado;
import com.belmonte.viajes_service.model.OperacionTraslado;
import com.belmonte.viajes_service.model.Traslado;
import com.belmonte.viajes_service.model.Ubicacion;
import com.belmonte.viajes_service.repository.EstadoTrasladoRepository;
import com.belmonte.viajes_service.repository.BarreraRecursoRepository;
import com.belmonte.viajes_service.repository.HistorialEstadoRepository;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
import com.belmonte.viajes_service.repository.TrasladoRepository;
import com.belmonte.viajes_service.repository.UbicacionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

@Service
public class TrasladoService {

    private static final Duration SEPARACION = Duration.ofMinutes(1);

    private final TrasladoRepository trasladoRepository;
    private final UbicacionRepository ubicacionRepository;
    private final EstadoTrasladoRepository estadoRepository;
    private final HistorialEstadoRepository historialRepository;
    private final OperacionTrasladoRepository operacionRepository;
    private final JdbcTemplate jdbc;
    private final BarreraRecursoRepository barreras;

    public TrasladoService(TrasladoRepository trasladoRepository,
                           UbicacionRepository ubicacionRepository,
                           EstadoTrasladoRepository estadoRepository,
                           HistorialEstadoRepository historialRepository,
                           OperacionTrasladoRepository operacionRepository,
                           JdbcTemplate jdbc,
                           BarreraRecursoRepository barreras) {
        this.trasladoRepository = trasladoRepository;
        this.ubicacionRepository = ubicacionRepository;
        this.estadoRepository = estadoRepository;
        this.historialRepository = historialRepository;
        this.operacionRepository = operacionRepository;
        this.jdbc = jdbc;
        this.barreras = barreras;
    }

    @Transactional
    public Traslado crear(TrasladoRequest request, Long solicitanteId,
                          String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<OperacionTraslado> repetida =
                    operacionRepository.findByClave(idempotencyKey);
            if (repetida.isPresent()) {
                if (!Objects.equals(repetida.get().getActorId(), solicitanteId)
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
            registrarOperacion(idempotencyKey, solicitanteId, "crear", traslado.getId(), 201);
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

    @Transactional
    public Traslado cambiarEstado(Long trasladoId, EstadoRequest request,
                                  Long actorId, Long conductorId,
                                  String idempotencyKey) {
        validarClaveIdempotencia(idempotencyKey, actorId, "estado");
        bloquearOperaciones();

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

        if ("EN_CURSO".equals(nuevo)) {
            verificarRecursosEnCurso(traslado.getConductorId(), traslado.getVehiculoId(), trasladoId);
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
                             Long actorId, boolean administrador, boolean conductor,
                             String idempotencyKey) {
        validarClaveIdempotencia(idempotencyKey, actorId, "cancelar");
        bloquearOperaciones();
        Traslado traslado = trasladoRepository.findById(trasladoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Traslado no encontrado"));
        if (!administrador && !traslado.getSolicitanteId().equals(actorId)
                && !(conductor && actorId.equals(traslado.getConductorId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sin permiso para cancelar el traslado");
        }
        String estado = estadoActual(traslado);
        if (!List.of("PENDIENTE", "ASIGNADO", "EN_CURSO").contains(estado)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El traslado no admite cancelacion");
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

    private void bloquearOperaciones() {
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

    private void validarClaveIdempotencia(String clave, Long actorId,
                                          String operacion) {
        if (clave == null) return;
        operacionRepository.findByClave(clave).ifPresent(previa -> {
            if (!Objects.equals(previa.getActorId(), actorId)
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
            int actualizadas = jdbc.update("""
                    UPDATE operaciones_traslados SET traslado_id = ?, respuesta_codigo = ?
                    WHERE clave = ? AND actor_id = ? AND operacion = ?
                    """, trasladoId, respuestaCodigo, clave, actorId, operacion);
            if (actualizadas == 0) {
                try {
                    jdbc.update("""
                            INSERT INTO operaciones_traslados
                            (clave, actor_id, operacion, traslado_id, respuesta_codigo, creado_en)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """, clave, actorId, operacion, trasladoId, respuestaCodigo, OffsetDateTime.now());
                } catch (DuplicateKeyException exception) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia reutilizada");
                }
            }
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
