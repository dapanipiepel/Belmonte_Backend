package com.belmonte.vehiculos_service.service;

import com.belmonte.vehiculos_service.repository.OperacionEstadoVehiculoRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
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
public class EstadoVehiculoIdempotenteService {
    private static final Logger log = LoggerFactory.getLogger(EstadoVehiculoIdempotenteService.class);
    private final EstadoVehiculoTransacciones transacciones;
    private final OperacionEstadoVehiculoRepository operaciones;
    private final BarreraViajesClient viajes;

    public EstadoVehiculoIdempotenteService(EstadoVehiculoTransacciones transacciones,
                                           OperacionEstadoVehiculoRepository operaciones, BarreraViajesClient viajes) {
        this.transacciones = transacciones;
        this.operaciones = operaciones;
        this.viajes = viajes;
    }

    @Transactional(propagation = Propagation.NEVER)
    public EstadoVehiculoTransacciones.Resultado cambiar(
            String clave, Long actorId, String sesionId, Long vehiculoId, boolean activo, OffsetDateTime jwtExpiraEn) {
        validar(clave, actorId);
        if (sesionId == null || sesionId.isBlank() || sesionId.length() > 36) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        if (vehiculoId == null || vehiculoId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vehiculo invalido");
        }
        String hash = huella(vehiculoId, activo);
        try {
            transacciones.reservar(clave, actorId, sesionId, vehiculoId, hash, UUID.randomUUID().toString(),
                    activo ? "CAMBIO_COMPATIBLE" : "BAJA", activo, jwtExpiraEn);
        } catch (DuplicateKeyException exception) {
            if (!operaciones.existsById(clave)) throw exception;
        }
        var op = transacciones.leer(clave, actorId, vehiculoId, hash);
        if ("COMPLETADA".equals(op.estado()) || "EXPIRADA".equals(op.estado())) return op.resultado();
        if ("PENDIENTE".equals(op.estado())) {
            String estadoBarrera = viajes.preparar(op.barreraId(), vehiculoId, op.proposito());
            if ("RECHAZADA".equals(estadoBarrera)) {
                op = transacciones.rechazar(clave, actorId, vehiculoId, hash);
            } else if ("ABIERTA".equals(estadoBarrera)) {
                op = transacciones.aplicar(clave, actorId, vehiculoId, hash, activo);
            } else {
                op = transacciones.leer(clave, actorId, vehiculoId, hash);
                if ("PENDIENTE".equals(op.estado())) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Barrera cerrada sin resultado local");
                }
            }
        }
        if ("COMPLETADA".equals(op.estado()) || "EXPIRADA".equals(op.estado())) return op.resultado();
        if (!"APLICADA".equals(op.estado())) throw new IllegalStateException("Estado de operacion invalido");
        viajes.cerrar(op.barreraId(), vehiculoId, op.proposito());
        return transacciones.finalizar(clave, actorId, vehiculoId, hash);
    }

    public EstadoVehiculoTransacciones.Consulta consultar(String clave, Long actorId) {
        validar(clave, actorId);
        return transacciones.consultar(clave, actorId);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional(propagation = Propagation.NEVER)
    public void recuperarAplicadas() {
        String ultimaClave = null;
        List<EstadoVehiculoTransacciones.Recuperacion> pagina;
        do {
            pagina = transacciones.aplicadas(ultimaClave);
            for (var op : pagina) {
                ultimaClave = op.clave();
                try {
                    viajes.cerrar(op.barreraId(), op.vehiculoId(), op.proposito());
                    transacciones.finalizar(op.clave(), op.actorId(), op.vehiculoId(), op.hash());
                } catch (ResponseStatusException exception) {
                    log.warn("No se pudo cerrar una barrera de vehiculo: HTTP {}", exception.getStatusCode().value());
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

    private String huella(Long id, boolean activo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((id + ":" + activo).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
