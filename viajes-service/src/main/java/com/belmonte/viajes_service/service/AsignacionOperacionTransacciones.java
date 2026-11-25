package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.AsignacionRequest;
import com.belmonte.viajes_service.dto.TrasladoResponse;
import com.belmonte.viajes_service.model.OperacionTraslado;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AsignacionOperacionTransacciones {
    private final OperacionTrasladoRepository operaciones;
    private final TrasladoService traslados;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final BarreraRecursoService barreras;
    private final EntityManager entityManager;

    public AsignacionOperacionTransacciones(OperacionTrasladoRepository operaciones,
                                            TrasladoService traslados, ObjectMapper json, JdbcTemplate jdbc,
                                            BarreraRecursoService barreras, EntityManager entityManager) {
        this.operaciones = operaciones;
        this.traslados = traslados;
        this.json = json;
        this.jdbc = jdbc;
        this.barreras = barreras;
        this.entityManager = entityManager;
    }

    @Transactional
    public void reservar(String clave, Long actorId, String sesionId, Long trasladoId, String hash,
                         AsignacionRequest request, OffsetDateTime jwtExpiraEn) {
        jdbc.update("""
                INSERT INTO operaciones_traslados
                (clave, actor_id, operacion, traslado_id, respuesta_codigo, creado_en, estado_operacion,
                 solicitud_hash, sesion_original, solicitud_conductor_id, solicitud_vehiculo_id, jwt_original_expira_en)
                VALUES (?, ?, 'asignar', ?, 102, ?, 'PENDIENTE', ?, ?, ?, ?, ?)
                """, clave, actorId, trasladoId, ahora(), hash, sesionId, request.conductorId(),
                request.vehiculoId(), jwtExpiraEn);
    }

    @Transactional
    public Preparacion preparar(String clave, Long actorId, Long trasladoId,
                                AsignacionRequest request, String hash) {
        OperacionTraslado operacion = cargarOperacion(clave);
        Resultado anterior = resultadoAnterior(operacion, actorId, trasladoId, hash);
        if (anterior != null) {
            return new Preparacion(anterior, 0);
        }
        barreras.prepararAsignacion(idBarrera(clave, "CONDUCTOR"), request.conductorId(),
                idBarrera(clave, "VEHICULO"), request.vehiculoId());
        var traslado = traslados.buscar(trasladoId).orElse(null);
        if (traslado == null) {
            Resultado resultado = completar(operacion, clave, request,
                    error(HttpStatus.NOT_FOUND.value(), "Traslado no encontrado"));
            return new Preparacion(resultado, 0);
        }
        return new Preparacion(null, traslado.getPasajeros().size());
    }

    @Transactional
    public Resultado rechazar(String clave, Long actorId, Long trasladoId, AsignacionRequest request,
                              String hash, int codigo, String mensaje) {
        if (codigo != HttpStatus.NOT_FOUND.value() && codigo != HttpStatus.CONFLICT.value()) {
            throw new IllegalArgumentException("Rechazo de recurso invalido");
        }
        OperacionTraslado operacion = cargarOperacion(clave);
        Resultado anterior = resultadoAnterior(operacion, actorId, trasladoId, hash);
        if (anterior != null) {
            return anterior;
        }
        return completar(operacion, clave, request, error(codigo, mensaje));
    }

    @Transactional
    public Resultado procesar(String clave, Long actorId, Long trasladoId,
                              AsignacionRequest request, String hash) {
        OperacionTraslado operacion = cargarOperacion(clave);
        Resultado anterior = resultadoAnterior(operacion, actorId, trasladoId, hash);
        if (anterior != null) {
            return anterior;
        }
        String conductor = idBarrera(clave, "CONDUCTOR");
        String vehiculo = idBarrera(clave, "VEHICULO");
        barreras.verificarAsignacion(conductor, request.conductorId(), vehiculo, request.vehiculoId());
        Resultado resultado;
        try {
            var traslado = traslados.asignar(trasladoId, request, actorId, conductor, vehiculo);
            resultado = new Resultado(200,
                    json.writeValueAsString(TrasladoResponse.from(traslado, traslados.estadoActual(traslado))));
        } catch (ResponseStatusException exception) {
            if (!exception.getStatusCode().is4xxClientError()) {
                throw exception;
            }
            resultado = error(exception.getStatusCode().value(), exception.getReason());
        }
        return completar(operacion, clave, request, resultado);
    }

    private Resultado completar(OperacionTraslado operacion, String clave, AsignacionRequest request,
                               Resultado resultado) {
        barreras.cerrarAsignacion(idBarrera(clave, "CONDUCTOR"), request.conductorId(),
                idBarrera(clave, "VEHICULO"), request.vehiculoId());
        operacion.completar(resultado.codigo(), resultado.cuerpo(), ahora().plusDays(30));
        return resultado;
    }

    private Resultado resultadoAnterior(OperacionTraslado operacion, Long actorId, Long trasladoId, String hash) {
        if ("EXPIRADA".equals(operacion.getEstadoOperacion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia expirada");
        }
        if (!"asignar".equals(operacion.getOperacion())
                || !Objects.equals(operacion.getActorId(), actorId)
                || !Objects.equals(operacion.getTrasladoId(), trasladoId)
                || !Objects.equals(operacion.getSolicitudHash(), hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia reutilizada");
        }
        if ("COMPLETADA".equals(operacion.getEstadoOperacion())) {
            if (!operacion.getExpiraEn().isAfter(ahora())) {
                operacion.expirar();
                return error(HttpStatus.CONFLICT.value(), "Clave de idempotencia expirada");
            }
            return new Resultado(operacion.getRespuestaCodigo(), operacion.getRespuestaJson());
        }
        if (!"PENDIENTE".equals(operacion.getEstadoOperacion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia anterior no verificable");
        }
        return null;
    }

    private OperacionTraslado cargarOperacion(String clave) {
        OperacionTraslado operacion = operaciones.findForUpdate(clave).orElseThrow(() ->
                new IllegalStateException("No se encontro la reserva de asignacion"));
        entityManager.refresh(operacion, LockModeType.PESSIMISTIC_WRITE);
        return operacion;
    }

    @Transactional(readOnly = true)
    public Consulta consultar(String clave, Long actorId) {
        var operacion = operaciones.findByClave(clave).orElse(null);
        if (operacion == null || !"asignar".equals(operacion.getOperacion())
                || !Objects.equals(operacion.getActorId(), actorId)
                || operacion.getEstadoOperacion() == null
                || "EXPIRADA".equals(operacion.getEstadoOperacion())) {
            return new Consulta("NO_DISPONIBLE", null, null);
        }
        if ("PENDIENTE".equals(operacion.getEstadoOperacion())) {
            return new Consulta("PENDIENTE", null, null);
        }
        if (!operacion.getExpiraEn().isAfter(ahora())) {
            return new Consulta("NO_DISPONIBLE", null, null);
        }
        return new Consulta("COMPLETADA", operacion.getRespuestaCodigo(),
                json.readTree(operacion.getRespuestaJson()));
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void limpiarRespuestasExpiradas() {
        operaciones.expirarRespuestas(ahora());
    }

    private Resultado error(int codigo, String mensaje) {
        return new Resultado(codigo, json.writeValueAsString(Map.of("error", mensaje)));
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private String idBarrera(String clave, String tipo) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("asignacion:" + tipo + ":" + clave).getBytes(StandardCharsets.UTF_8));
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong()).toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    public record Resultado(int codigo, String cuerpo) {}
    public record Preparacion(Resultado resultado, int pasajeros) {}
    public record Consulta(String estado, Integer codigo, tools.jackson.databind.JsonNode resultado) {}
}
