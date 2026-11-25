package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DecisionAutorizacionService {
    private static final Set<String> SERVICIOS = Set.of("USUARIOS_CAMBIO", "VEHICULOS_ESTADO", "VIAJES_ASIGNACION");
    private final UsuarioRepository usuarios;
    private final SesionUsuarioRepository sesiones;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final TransactionTemplate transaccion;
    private final RelojBaseDatos reloj;
    private final ControlDecisionSegura control;

    public DecisionAutorizacionService(UsuarioRepository usuarios, SesionUsuarioRepository sesiones,
                                       JdbcTemplate jdbc, EntityManager entityManager,
                                       PlatformTransactionManager transactionManager, RelojBaseDatos reloj,
                                       ControlDecisionSegura control) {
        this.usuarios = usuarios;
        this.sesiones = sesiones;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.transaccion = new TransactionTemplate(transactionManager);
        this.reloj = reloj;
        this.control = control;
    }

    @Transactional(propagation = Propagation.NEVER)
    public String decidir(Solicitud solicitud) {
        validar(solicitud);
        control.exigirDisponibilidad();
        boolean existe = existe(solicitud.id());
        if (!existe) {
            try {
                if (!Boolean.TRUE.equals(transaccion.execute(status -> preparar(solicitud)))) {
                    return "INCIERTA";
                }
            } catch (DuplicateKeyException exception) {
                if (!existe(solicitud.id())) throw exception;
            }
        }
        String estado = Objects.requireNonNull(transaccion.execute(status -> certificar(solicitud, null)));
        if (!"ESPERA_PRUEBA".equals(estado)) return estado;

        // La prueba independiente se obtiene despues del commit y sin transaccion SQL.
        Instant limiteSuperior = control.obtenerLimiteSuperior();
        if (limiteSuperior == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Prueba de tiempo no disponible");
        }
        return Objects.requireNonNull(transaccion.execute(status -> certificar(solicitud, limiteSuperior)));
    }

    private boolean preparar(Solicitud solicitud) {
        var usuario = usuarios.findForUpdate(solicitud.actorId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Actor no autorizado"));
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        var sesion = sesiones.findById(solicitud.sesionId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion no autorizada"));
        entityManager.refresh(sesion);
        var ahora = reloj.ahora();
        if (!Objects.equals(sesion.getUsuario().getIdUsuario(), solicitud.actorId())
                || !Integer.valueOf(1).equals(sesion.getActiva())
                || !Integer.valueOf(1).equals(usuario.getActivo())
                || !"ADMINISTRADOR".equals(usuario.getRol().getNombreRol())
                || !solicitud.jwtExpiraEn().toInstant().isAfter(ahora.toInstant())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion no autorizada");
        }
        if (sesion.getFechaExpiracionInstante() == null) return false;
        if (!sesion.getFechaExpiracionInstante().toInstant().isAfter(ahora.toInstant())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion no autorizada");
        }
        jdbc.update("""
                INSERT INTO decisiones_autorizacion
                (decision_id, actor_id, sesion_id, solicitud_hash, jwt_expira_en, sesion_expira_en,
                 sesion_expira_instante, estado)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARADA')
                """, solicitud.id(), solicitud.actorId(), solicitud.sesionId(), solicitud.hash(),
                solicitud.jwtExpiraEn(), sesion.getFechaExpiracion(), sesion.getFechaExpiracionInstante());
        return true;
    }

    private String certificar(Solicitud solicitud, Instant limiteSuperior) {
        var decisiones = jdbc.query("""
                SELECT actor_id, sesion_id, solicitud_hash, jwt_expira_en, sesion_expira_instante,
                       tiempo_limite_superior, estado
                FROM decisiones_autorizacion WHERE decision_id = ? FOR UPDATE
                """, (rs, row) -> new Decision(rs.getLong("actor_id"), rs.getString("sesion_id"),
                rs.getString("solicitud_hash"), rs.getObject("jwt_expira_en", OffsetDateTime.class),
                rs.getObject("sesion_expira_instante", OffsetDateTime.class),
                rs.getObject("tiempo_limite_superior", OffsetDateTime.class), rs.getString("estado")), solicitud.id());
        if (decisiones.isEmpty()) throw new IllegalStateException("Decision de autorizacion inexistente");
        var decision = decisiones.get(0);
        if (!Objects.equals(decision.actorId(), solicitud.actorId())
                || !Objects.equals(decision.sesionId(), solicitud.sesionId())
                || !Objects.equals(decision.hash(), solicitud.hash())
                || !decision.jwtExpiraEn().toInstant().equals(solicitud.jwtExpiraEn().toInstant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Decision de autorizacion reutilizada");
        }
        if (decision.sesionExpiraInstante() == null) return "INCIERTA";
        if ("AUTORIZADA".equals(decision.estado())) {
            return decision.tiempoLimiteSuperior() == null ? "INCIERTA" : "AUTORIZADA";
        }
        if (!"PREPARADA".equals(decision.estado())) throw new IllegalStateException("Estado de decision invalido");
        if (limiteSuperior == null) return "ESPERA_PRUEBA";
        if (!decision.jwtExpiraEn().toInstant().isAfter(limiteSuperior)
                || !decision.sesionExpiraInstante().toInstant().isAfter(limiteSuperior)) {
            return "INCIERTA";
        }
        jdbc.update("UPDATE decisiones_autorizacion SET estado = 'AUTORIZADA', tiempo_limite_superior = ? WHERE decision_id = ?",
                limiteSuperior.atOffset(ZoneOffset.UTC), solicitud.id());
        return "AUTORIZADA";
    }

    private boolean existe(String id) {
        Integer cantidad = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decisiones_autorizacion WHERE decision_id = ?", Integer.class, id);
        return Objects.requireNonNull(cantidad) > 0;
    }

    private void validar(Solicitud solicitud) {
        if (solicitud == null || solicitud.servicio() == null || !SERVICIOS.contains(solicitud.servicio())
                || solicitud.clave() == null || solicitud.clave().isBlank()
                || solicitud.clave().codePointCount(0, solicitud.clave().length()) > 100
                || solicitud.actorId() == null || solicitud.actorId() <= 0
                || solicitud.sesionId() == null || solicitud.sesionId().isBlank() || solicitud.sesionId().length() > 36
                || solicitud.hash() == null || !solicitud.hash().matches("[0-9a-f]{64}")
                || solicitud.jwtExpiraEn() == null || solicitud.jwtExpiraEn().getNano() % 1_000_000 != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud de autorizacion invalida");
        }
    }

    public record Solicitud(String servicio, String clave, Long actorId, String sesionId,
                            String hash, OffsetDateTime jwtExpiraEn) {
        public String id() { return servicio + ":" + clave; }
    }

    private record Decision(Long actorId, String sesionId, String hash, OffsetDateTime jwtExpiraEn,
                            OffsetDateTime sesionExpiraInstante, OffsetDateTime tiempoLimiteSuperior, String estado) {}
}
