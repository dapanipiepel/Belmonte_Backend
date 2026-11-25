package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.repository.OperacionCambioUsuarioRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CambioUsuarioIdempotenteService {
    private static final Logger log = LoggerFactory.getLogger(CambioUsuarioIdempotenteService.class);
    private final CambioUsuarioTransacciones transacciones;
    private final OperacionCambioUsuarioRepository operaciones;
    private final BarreraViajesClient viajes;

    public CambioUsuarioIdempotenteService(CambioUsuarioTransacciones transacciones,
                                          OperacionCambioUsuarioRepository operaciones, BarreraViajesClient viajes) {
        this.transacciones = transacciones;
        this.operaciones = operaciones;
        this.viajes = viajes;
    }

    @Transactional(propagation = Propagation.NEVER)
    public CambioUsuarioTransacciones.Resultado cambiarEstado(
            String clave, Long actorId, String sesionId, Long usuarioId, boolean activo, OffsetDateTime jwtExpiraEn) {
        return cambiar(clave, actorId, sesionId, usuarioId, activo, null, jwtExpiraEn);
    }

    @Transactional(propagation = Propagation.NEVER)
    public CambioUsuarioTransacciones.Resultado cambiarRol(
            String clave, Long actorId, String sesionId, Long usuarioId, Long rolId, OffsetDateTime jwtExpiraEn) {
        if (rolId == null || rolId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rol invalido");
        return cambiar(clave, actorId, sesionId, usuarioId, null, rolId, jwtExpiraEn);
    }

    private CambioUsuarioTransacciones.Resultado cambiar(String clave, Long actorId, String sesionId, Long usuarioId,
                                                         Boolean activo, Long rolId, OffsetDateTime jwtExpiraEn) {
        validar(clave, actorId);
        if (sesionId == null || sesionId.isBlank() || sesionId.length() > 36) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        if (usuarioId == null || usuarioId <= 0 || (activo == null) == (rolId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cambio de usuario invalido");
        }
        String hash = huella(usuarioId, activo, rolId);
        try {
            transacciones.reservar(clave, actorId, sesionId, usuarioId, hash, UUID.randomUUID().toString(),
                    activo, rolId, jwtExpiraEn);
        } catch (DuplicateKeyException exception) {
            if (!operaciones.existsById(clave)) throw exception;
        }
        var op = transacciones.leer(clave, actorId, usuarioId, hash);
        if ("COMPLETADA".equals(op.estado()) || "EXPIRADA".equals(op.estado())) return op.resultado();
        if ("PENDIENTE".equals(op.estado())) {
            String estadoBarrera = viajes.preparar(op.barreraId(), usuarioId, op.proposito());
            if ("RECHAZADA".equals(estadoBarrera)) {
                op = transacciones.rechazar(clave, actorId, usuarioId, hash);
            } else if ("ABIERTA".equals(estadoBarrera)) {
                op = transacciones.aplicar(clave, actorId, usuarioId, hash, activo, rolId);
            } else {
                op = transacciones.leer(clave, actorId, usuarioId, hash);
                if ("PENDIENTE".equals(op.estado())) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Barrera cerrada sin resultado local");
                }
            }
        }
        if ("COMPLETADA".equals(op.estado()) || "EXPIRADA".equals(op.estado())) return op.resultado();
        if (!"APLICADA".equals(op.estado())) throw new IllegalStateException("Estado de operacion invalido");
        viajes.cerrar(op.barreraId(), usuarioId, op.proposito());
        return transacciones.finalizar(clave, actorId, usuarioId, hash);
    }

    public CambioUsuarioTransacciones.Consulta consultar(String clave, Long actorId) {
        validar(clave, actorId);
        return transacciones.consultar(clave, actorId);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional(propagation = Propagation.NEVER)
    public void recuperarAplicadas() {
        String ultimaClave = null;
        java.util.List<CambioUsuarioTransacciones.Recuperacion> pagina;
        do {
            pagina = transacciones.aplicadas(ultimaClave);
            for (var op : pagina) {
                ultimaClave = op.clave();
                try {
                    viajes.cerrar(op.barreraId(), op.usuarioId(), op.proposito());
                    transacciones.finalizar(op.clave(), op.actorId(), op.usuarioId(), op.hash());
                } catch (ResponseStatusException exception) {
                    log.warn("No se pudo cerrar una barrera de usuario: HTTP {}", exception.getStatusCode().value());
                }
            }
        } while (pagina.size() == 100);
    }

    private void validar(String clave, Long actorId) {
        if (actorId == null || actorId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identidad de usuario invalida");
        }
        if (clave == null || clave.isBlank() || clave.codePointCount(0, clave.length()) > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key es obligatoria y debe tener hasta 100 caracteres");
        }
    }

    private String huella(Long id, Boolean activo, Long rolId) {
        try {
            String solicitud = rolId == null ? id + ":estado:" + activo : id + ":rol:" + rolId;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(solicitud.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
