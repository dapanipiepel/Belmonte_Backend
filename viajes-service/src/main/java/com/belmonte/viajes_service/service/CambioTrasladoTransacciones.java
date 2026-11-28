package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.CancelacionRequest;
import com.belmonte.viajes_service.dto.EstadoRequest;
import com.belmonte.viajes_service.dto.TrasladoResponse;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
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
public class CambioTrasladoTransacciones {
    private final TrasladoService traslados;
    private final OperacionTrasladoRepository operaciones;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CambioTrasladoTransacciones(TrasladoService traslados, OperacionTrasladoRepository operaciones,
                                      JdbcTemplate jdbc, ObjectMapper json) {
        this.traslados = traslados;
        this.operaciones = operaciones;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Resultado ejecutar(String clave, Long actor, Intencion intencion, String hash,
                              boolean administrador, boolean solicitante, boolean conductor) {
        traslados.bloquearOperaciones();
        var previa = operaciones.findByClave(clave).orElse(null);
        if (previa != null) {
            if (!intencion.tipo().equals(previa.getOperacion()) || !Objects.equals(actor, previa.getActorId())
                    || !Objects.equals(intencion.id(), previa.getTrasladoId())
                    || !Objects.equals(hash, previa.getSolicitudHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia reutilizada");
            }
            if (!"COMPLETADA".equals(previa.getEstadoOperacion()) || previa.getExpiraEn() == null
                    || !previa.getExpiraEn().isAfter(ahora())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia no disponible");
            }
            return new Resultado(previa.getRespuestaCodigo(), previa.getRespuestaJson());
        }

        Resultado resultado;
        try {
            var traslado = switch (intencion) {
                case Cancelacion cancelacion -> traslados.cancelar(cancelacion.id(), cancelacion.request(), actor,
                        administrador, solicitante, conductor);
                case Estado estado -> traslados.cambiarEstado(estado.id(), estado.request(), actor);
            };
            resultado = new Resultado(200,
                    json.writeValueAsString(TrasladoResponse.from(traslado, traslados.estadoActual(traslado))));
        } catch (ResponseStatusException exception) {
            if (!exception.getStatusCode().is4xxClientError() || exception.getReason() == null) {
                throw exception;
            }
            resultado = new Resultado(exception.getStatusCode().value(),
                    json.writeValueAsString(Map.of("error", exception.getReason())));
        }
        OffsetDateTime ahora = ahora();
        jdbc.update("""
                INSERT INTO operaciones_traslados
                (clave, actor_id, operacion, traslado_id, respuesta_codigo, creado_en,
                 estado_operacion, solicitud_hash, respuesta_json, expira_en)
                VALUES (?, ?, ?, ?, ?, ?, 'COMPLETADA', ?, ?, ?)
                """, clave, actor, intencion.tipo(), intencion.id(), resultado.codigo(), ahora, hash,
                resultado.cuerpo(), ahora.plusDays(30));
        return resultado;
    }

    @Transactional(readOnly = true)
    public Consulta consultar(String clave, Long actor, String tipo) {
        var operacion = operaciones.findByClave(clave).orElse(null);
        if (operacion == null || !tipo.equals(operacion.getOperacion())
                || !Objects.equals(actor, operacion.getActorId())
                || !"COMPLETADA".equals(operacion.getEstadoOperacion())
                || operacion.getExpiraEn() == null || !operacion.getExpiraEn().isAfter(ahora())) {
            return new Consulta("NO_DISPONIBLE", null, null);
        }
        return new Consulta("COMPLETADA", operacion.getRespuestaCodigo(),
                json.readTree(operacion.getRespuestaJson()));
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record Resultado(int codigo, String cuerpo) {}
    public record Consulta(String estado, Integer codigo, JsonNode resultado) {}
    public sealed interface Intencion permits Cancelacion, Estado {
        Long id();
        String tipo();
    }
    public record Cancelacion(Long id, CancelacionRequest request) implements Intencion {
        public String tipo() { return "cancelar"; }
    }
    public record Estado(Long id, EstadoRequest request) implements Intencion {
        public String tipo() { return "estado"; }
    }
}
