package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.model.OperacionCambioUsuario;
import com.belmonte.usuarios_service.repository.OperacionCambioUsuarioRepository;
import com.belmonte.usuarios_service.repository.RolRepository;
import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CambioUsuarioTransacciones {
    private final OperacionCambioUsuarioRepository operaciones;
    private final UsuarioRepository usuarios;
    private final RolRepository roles;
    private final SesionUsuarioRepository sesiones;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CambioUsuarioTransacciones(OperacionCambioUsuarioRepository operaciones, UsuarioRepository usuarios,
                                     RolRepository roles, SesionUsuarioRepository sesiones,
                                     EntityManager entityManager, JdbcTemplate jdbc, ObjectMapper json) {
        this.operaciones = operaciones;
        this.usuarios = usuarios;
        this.roles = roles;
        this.sesiones = sesiones;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void reservar(String clave, Long actorId, String sesionId, Long usuarioId, String hash, String barreraId,
                         Boolean activo, Long rolId, OffsetDateTime jwtExpiraEn) {
        Resultado error = null;
        String proposito;
        if (!usuarios.existsById(usuarioId)) {
            error = error(404, "Usuario no encontrado");
        }
        if (rolId != null) {
            var rol = roles.findById(rolId).orElse(null);
            if (rol == null && error == null) error = error(404, "Rol no encontrado");
            proposito = rol != null && "CONDUCTOR".equals(rol.getNombreRol()) ? "CAMBIO_COMPATIBLE" : "BAJA";
        } else {
            proposito = Boolean.TRUE.equals(activo) ? "CAMBIO_COMPATIBLE" : "BAJA";
        }
        if (error == null) {
            jdbc.update("""
                    INSERT INTO operaciones_cambio_usuario
                    (clave, actor_id, usuario_id, solicitud_hash, barrera_id, proposito, estado,
                     sesion_original, tipo_solicitud, solicitud_activo, solicitud_rol_id, jwt_original_expira_en)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDIENTE', ?, ?, ?, ?, ?)
                    """, clave, actorId, usuarioId, hash, barreraId, proposito, sesionId,
                    rolId == null ? "ESTADO" : "ROL", activo == null ? null : (activo ? 1 : 0), rolId, jwtExpiraEn);
        } else {
            jdbc.update("""
                    INSERT INTO operaciones_cambio_usuario
                    (clave, actor_id, usuario_id, solicitud_hash, barrera_id, proposito,
                     estado, respuesta_codigo, respuesta_json, expira_en)
                    VALUES (?, ?, ?, ?, ?, ?, 'COMPLETADA', ?, ?, ?)
                    """, clave, actorId, usuarioId, hash, barreraId, proposito,
                    error.codigo(), error.cuerpo(), ahora().plusDays(30));
        }
    }

    @Transactional
    public Operacion leer(String clave, Long actorId, Long usuarioId, String hash) {
        return verificar(cargar(clave), actorId, usuarioId, hash);
    }

    @Transactional
    public Operacion aplicar(String clave, Long actorId, Long usuarioId, String hash, Boolean activo, Long rolId) {
        OperacionCambioUsuario op = cargar(clave);
        Operacion actual = verificar(op, actorId, usuarioId, hash);
        if (!"PENDIENTE".equals(actual.estado())) return actual;

        var usuario = usuarios.findForUpdate(usuarioId).orElse(null);
        Resultado resultado;
        if (usuario == null) {
            resultado = error(404, "Usuario no encontrado");
        } else {
            entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
            if (rolId == null) {
                usuario.setActivo(activo ? 1 : 0);
                if (!activo) sesiones.revokeAllByUsuarioId(usuarioId);
                resultado = new Resultado(200, json.writeValueAsString(
                        Map.of("idUsuario", usuarioId, "activo", usuario.getActivo())));
            } else {
                var rol = roles.findById(rolId).orElse(null);
                if (rol == null) {
                    resultado = error(404, "Rol no encontrado");
                } else {
                    if (!rol.getIdRol().equals(usuario.getRol().getIdRol())) {
                        usuario.setRol(rol);
                        sesiones.revokeAllByUsuarioId(usuarioId);
                    }
                    resultado = new Resultado(200, json.writeValueAsString(
                            Map.of("idUsuario", usuarioId, "rol", rol.getNombreRol())));
                }
            }
        }
        op.aplicar(resultado.codigo(), resultado.cuerpo());
        return new Operacion("APLICADA", op.getBarreraId(), op.getProposito(), resultado);
    }

    @Transactional
    public Operacion rechazar(String clave, Long actorId, Long usuarioId, String hash) {
        OperacionCambioUsuario op = cargar(clave);
        Operacion actual = verificar(op, actorId, usuarioId, hash);
        if (!"PENDIENTE".equals(actual.estado())) return actual;
        Resultado resultado = error(409, "Usuario con traslados activos");
        op.aplicar(resultado.codigo(), resultado.cuerpo());
        op.completar(ahora().plusDays(30));
        return new Operacion("COMPLETADA", op.getBarreraId(), op.getProposito(), resultado);
    }

    @Transactional
    public Resultado finalizar(String clave, Long actorId, Long usuarioId, String hash) {
        OperacionCambioUsuario op = cargar(clave);
        Operacion actual = verificar(op, actorId, usuarioId, hash);
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
        if (!"COMPLETADA".equals(op.getEstado())) return new Consulta("PENDIENTE", null, null);
        if (!op.getExpiraEn().isAfter(ahora())) return new Consulta("NO_DISPONIBLE", null, null);
        return new Consulta("COMPLETADA", op.getRespuestaCodigo(), json.readTree(op.getRespuestaJson()));
    }

    @Transactional(readOnly = true)
    public List<Recuperacion> aplicadas(String despuesDe) {
        var pagina = despuesDe == null
                ? operaciones.findByEstadoOrderByClave("APLICADA", PageRequest.of(0, 100))
                : operaciones.findByEstadoAndClaveGreaterThanOrderByClave("APLICADA", despuesDe, PageRequest.of(0, 100));
        return pagina.stream()
                .map(op -> new Recuperacion(op.getClave(), op.getActorId(), op.getUsuarioId(),
                        op.getSolicitudHash(), op.getBarreraId(), op.getProposito()))
                .toList();
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void limpiarRespuestasExpiradas() {
        operaciones.expirar(ahora());
    }

    private OperacionCambioUsuario cargar(String clave) {
        var op = operaciones.findForUpdate(clave).orElseThrow(() ->
                new IllegalStateException("No se encontro la operacion de usuario"));
        entityManager.refresh(op, LockModeType.PESSIMISTIC_WRITE);
        return op;
    }

    private Operacion verificar(OperacionCambioUsuario op, Long actorId, Long usuarioId, String hash) {
        if ("EXPIRADA".equals(op.getEstado())) {
            return new Operacion("EXPIRADA", null, null, error(409, "Clave de idempotencia expirada"));
        }
        if (!Objects.equals(op.getActorId(), actorId) || !Objects.equals(op.getUsuarioId(), usuarioId)
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
    public record Recuperacion(String clave, Long actorId, Long usuarioId, String hash,
                               String barreraId, String proposito) {}
}
