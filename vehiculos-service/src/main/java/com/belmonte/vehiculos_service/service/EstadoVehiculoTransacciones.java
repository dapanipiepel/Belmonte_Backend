package com.belmonte.vehiculos_service.service;

import com.belmonte.vehiculos_service.dto.VehiculoResponse;
import com.belmonte.vehiculos_service.model.OperacionEstadoVehiculo;
import com.belmonte.vehiculos_service.repository.OperacionEstadoVehiculoRepository;
import com.belmonte.vehiculos_service.repository.VehiculoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class EstadoVehiculoTransacciones {
    private final OperacionEstadoVehiculoRepository operaciones;
    private final VehiculoRepository vehiculos;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EstadoVehiculoTransacciones(OperacionEstadoVehiculoRepository operaciones, VehiculoRepository vehiculos,
                                       EntityManager entityManager, JdbcTemplate jdbc, ObjectMapper json) {
        this.operaciones = operaciones;
        this.vehiculos = vehiculos;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void reservar(String clave, Long actorId, String sesionId, Long vehiculoId, String hash,
                         String barreraId, String proposito, boolean activo, OffsetDateTime jwtExpiraEn) {
        jdbc.update("""
                INSERT INTO operaciones_estado_vehiculo
                (clave, actor_id, vehiculo_id, solicitud_hash, barrera_id, proposito, estado,
                 sesion_original, solicitud_activo, jwt_original_expira_en)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDIENTE', ?, ?, ?)
                """, clave, actorId, vehiculoId, hash, barreraId, proposito, sesionId, activo ? 1 : 0, jwtExpiraEn);
    }

    @Transactional
    public Operacion leer(String clave, Long actorId, Long vehiculoId, String hash) {
        return verificar(cargar(clave), actorId, vehiculoId, hash);
    }

    @Transactional
    public Operacion aplicar(String clave, Long actorId, Long vehiculoId, String hash, boolean activo) {
        OperacionEstadoVehiculo op = cargar(clave);
        Operacion actual = verificar(op, actorId, vehiculoId, hash);
        if (!"PENDIENTE".equals(actual.estado())) return actual;

        var vehiculo = vehiculos.findForUpdate(vehiculoId).orElse(null);
        Resultado resultado;
        if (vehiculo == null) {
            resultado = error(404, "Vehiculo no encontrado");
        } else {
            entityManager.refresh(vehiculo, LockModeType.PESSIMISTIC_WRITE);
            vehiculo.setActivo(activo);
            resultado = new Resultado(200, json.writeValueAsString(VehiculoResponse.from(vehiculo)));
        }
        op.aplicar(resultado.codigo(), resultado.cuerpo());
        return new Operacion("APLICADA", op.getBarreraId(), op.getProposito(), resultado);
    }

    @Transactional
    public Operacion rechazar(String clave, Long actorId, Long vehiculoId, String hash) {
        OperacionEstadoVehiculo op = cargar(clave);
        Operacion actual = verificar(op, actorId, vehiculoId, hash);
        if (!"PENDIENTE".equals(actual.estado())) return actual;
        Resultado resultado = error(409, "Vehiculo con traslados activos");
        op.aplicar(resultado.codigo(), resultado.cuerpo());
        op.completar(ahora().plusDays(30));
        return new Operacion("COMPLETADA", op.getBarreraId(), op.getProposito(), resultado);
    }

    @Transactional
    public Resultado finalizar(String clave, Long actorId, Long vehiculoId, String hash) {
        OperacionEstadoVehiculo op = cargar(clave);
        Operacion actual = verificar(op, actorId, vehiculoId, hash);
        if ("APLICADA".equals(actual.estado())) {
            op.completar(ahora().plusDays(30));
        } else if (!"COMPLETADA".equals(actual.estado()) && !"EXPIRADA".equals(actual.estado())) {
            throw new IllegalStateException("No existe resultado para finalizar");
        }
        return actual.resultado();
    }

    @Transactional(readOnly = true)
    public Consulta consultar(String clave, Long actorId) {
        var op = operaciones.findById(clave).orElse(null);
        if (op == null || !Objects.equals(op.getActorId(), actorId) || "EXPIRADA".equals(op.getEstado())) {
            return new Consulta("NO_DISPONIBLE", null, null);
        }
        if (!"COMPLETADA".equals(op.getEstado())) {
            return new Consulta("PENDIENTE", null, null);
        }
        if (!op.getExpiraEn().isAfter(ahora())) {
            return new Consulta("NO_DISPONIBLE", null, null);
        }
        return new Consulta("COMPLETADA", op.getRespuestaCodigo(), json.readTree(op.getRespuestaJson()));
    }

    @Transactional(readOnly = true)
    public List<Recuperacion> aplicadas(String despuesDe) {
        var pagina = despuesDe == null
                ? operaciones.findByEstadoOrderByClave("APLICADA", PageRequest.of(0, 100))
                : operaciones.findByEstadoAndClaveGreaterThanOrderByClave("APLICADA", despuesDe, PageRequest.of(0, 100));
        return pagina.stream()
                .map(op -> new Recuperacion(op.getClave(), op.getActorId(), op.getVehiculoId(),
                        op.getSolicitudHash(), op.getBarreraId(), op.getProposito()))
                .toList();
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void limpiarRespuestasExpiradas() {
        operaciones.expirar(ahora());
    }

    private OperacionEstadoVehiculo cargar(String clave) {
        var op = operaciones.findForUpdate(clave).orElseThrow(() ->
                new IllegalStateException("No se encontro la operacion de vehiculo"));
        entityManager.refresh(op, LockModeType.PESSIMISTIC_WRITE);
        return op;
    }

    private Operacion verificar(OperacionEstadoVehiculo op, Long actorId, Long vehiculoId, String hash) {
        if ("EXPIRADA".equals(op.getEstado())) {
            return new Operacion("EXPIRADA", null, null, error(409, "Clave de idempotencia expirada"));
        }
        if (!Objects.equals(op.getActorId(), actorId) || !Objects.equals(op.getVehiculoId(), vehiculoId)
                || !Objects.equals(op.getSolicitudHash(), hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de idempotencia reutilizada");
        }
        if ("COMPLETADA".equals(op.getEstado()) && !op.getExpiraEn().isAfter(ahora())) {
            op.expirar();
            return new Operacion("EXPIRADA", null, null, error(409, "Clave de idempotencia expirada"));
        }
        Resultado resultado = op.getRespuestaCodigo() == null ? null
                : new Resultado(op.getRespuestaCodigo(), op.getRespuestaJson());
        return new Operacion(op.getEstado(), op.getBarreraId(), op.getProposito(), resultado);
    }

    private Resultado error(int codigo, String mensaje) {
        return new Resultado(codigo, json.writeValueAsString(Map.of("error", mensaje)));
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record Resultado(int codigo, String cuerpo) {}
    public record Operacion(String estado, String barreraId, String proposito, Resultado resultado) {}
    public record Consulta(String estado, Integer codigo, JsonNode resultado) {}
    public record Recuperacion(String clave, Long actorId, Long vehiculoId, String hash,
                               String barreraId, String proposito) {}
}
