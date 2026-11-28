package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.TrasladoRequest;
import com.belmonte.viajes_service.dto.TrasladoResponse;
import com.belmonte.viajes_service.model.OperacionTraslado;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CreacionOperacionTransacciones {
    private final OperacionTrasladoRepository operaciones;
    private final TrasladoService traslados;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EntityManager entityManager;

    public CreacionOperacionTransacciones(OperacionTrasladoRepository operaciones, TrasladoService traslados,
                                          JdbcTemplate jdbc, ObjectMapper json, EntityManager entityManager) {
        this.operaciones = operaciones;
        this.traslados = traslados;
        this.jdbc = jdbc;
        this.json = json;
        this.entityManager = entityManager;
    }

    @Transactional
    public void reservar(String clave, Long actor, String sesion, OffsetDateTime jwtExpiraEn, String hash) {
        jdbc.update("""
                INSERT INTO operaciones_traslados
                (clave, actor_id, operacion, respuesta_codigo, creado_en, estado_operacion,
                 solicitud_hash, sesion_original, jwt_original_expira_en)
                VALUES (?, ?, 'crear', 102, ?, 'PENDIENTE', ?, ?, ?)
                """, clave, actor, ahora(), hash, sesion, jwtExpiraEn);
    }

    @Transactional
    public Resultado preparar(String clave, Long actor, String sesion, String hash) {
        return anterior(cargar(clave), actor, sesion, hash);
    }

    @Transactional
    public Resultado procesar(String clave, Long actor, String sesion, String hash, TrasladoRequest request) {
        OperacionTraslado operacion = cargar(clave);
        Resultado anterior = anterior(operacion, actor, sesion, hash);
        if (anterior != null) {
            return anterior;
        }
        var traslado = traslados.crear(request, actor);
        String cuerpo = json.writeValueAsString(TrasladoResponse.from(traslado, traslados.estadoActual(traslado)));
        operacion.completarCreacion(traslado.getId(), 201, cuerpo, ahora().plusDays(30));
        return new Resultado(201, cuerpo);
    }

    @Transactional
    public Resultado rechazar(String clave, Long actor, String sesion, String hash, int codigo, String mensaje) {
        OperacionTraslado operacion = cargar(clave);
        Resultado anterior = anterior(operacion, actor, sesion, hash);
        if (anterior != null) {
            return anterior;
        }
        if (mensaje == null) {
            throw new IllegalArgumentException("Rechazo sin mensaje");
        }
        String cuerpo = json.writeValueAsString(Map.of("error", mensaje));
        operacion.completar(codigo, cuerpo, ahora().plusDays(30));
        return new Resultado(codigo, cuerpo);
    }

    @Transactional(readOnly = true)
    public Consulta consultar(String clave, Long actor) {
        var operacion = operaciones.findByClave(clave).orElse(null);
        if (operacion == null || !"crear".equals(operacion.getOperacion())
                || !Objects.equals(actor, operacion.getActorId())) {
            return new Consulta("NO_DISPONIBLE", null, null);
        }
        if ("PENDIENTE".equals(operacion.getEstadoOperacion())) {
            return new Consulta("PENDIENTE", null, null);
        }
        if (!"COMPLETADA".equals(operacion.getEstadoOperacion()) || operacion.getExpiraEn() == null
                || !operacion.getExpiraEn().isAfter(ahora())) {
            return new Consulta("NO_DISPONIBLE", null, null);
        }
        return new Consulta("COMPLETADA", operacion.getRespuestaCodigo(), json.readTree(operacion.getRespuestaJson()));
    }

    private Resultado anterior(OperacionTraslado operacion, Long actor, String sesion, String hash) {
        if ("EXPIRADA".equals(operacion.getEstadoOperacion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia expirada");
        }
        if (!"crear".equals(operacion.getOperacion()) || !Objects.equals(operacion.getActorId(), actor)
                || !Objects.equals(operacion.getSolicitudHash(), hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia reutilizada");
        }
        if ("COMPLETADA".equals(operacion.getEstadoOperacion())) {
            if (operacion.getExpiraEn() == null || !operacion.getExpiraEn().isAfter(ahora())) {
                operacion.expirar();
                return new Resultado(409, json.writeValueAsString(Map.of("error", "Clave de idempotencia expirada")));
            }
            return new Resultado(operacion.getRespuestaCodigo(), operacion.getRespuestaJson());
        }
        if (!"PENDIENTE".equals(operacion.getEstadoOperacion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia anterior no verificable");
        }
        if (!Objects.equals(operacion.getSesionOriginal(), sesion)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Operacion pendiente de la sesion original");
        }
        return null;
    }

    private OperacionTraslado cargar(String clave) {
        OperacionTraslado operacion = operaciones.findForUpdate(clave).orElseThrow(() ->
                new IllegalStateException("No se encontro la reserva de creacion"));
        entityManager.refresh(operacion, LockModeType.PESSIMISTIC_WRITE);
        return operacion;
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record Resultado(int codigo, String cuerpo) {}
    public record Consulta(String estado, Integer codigo, JsonNode resultado) {}
}
