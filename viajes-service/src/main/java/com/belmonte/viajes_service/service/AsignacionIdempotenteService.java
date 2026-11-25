package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.AsignacionRequest;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AsignacionIdempotenteService {
    private final AsignacionOperacionTransacciones transacciones;
    private final OperacionTrasladoRepository operaciones;
    private final ValidacionRecursosClient recursos;

    public AsignacionIdempotenteService(AsignacionOperacionTransacciones transacciones,
                                       OperacionTrasladoRepository operaciones, ValidacionRecursosClient recursos) {
        this.transacciones = transacciones;
        this.operaciones = operaciones;
        this.recursos = recursos;
    }

    @Transactional(propagation = Propagation.NEVER)
    public AsignacionOperacionTransacciones.Resultado asignar(
            String clave, Long actorId, String sesionId, Long trasladoId, AsignacionRequest request,
            OffsetDateTime jwtExpiraEn) {
        validarClave(clave);
        if (sesionId == null || sesionId.isBlank() || sesionId.length() > 36) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        String hash = huella(trasladoId, request);
        try {
            transacciones.reservar(clave, actorId, sesionId, trasladoId, hash, request, jwtExpiraEn);
        } catch (DuplicateKeyException exception) {
            if (!operaciones.existsById(clave)) {
                throw exception;
            }
        }
        var preparacion = transacciones.preparar(clave, actorId, trasladoId, request, hash);
        if (preparacion.resultado() != null) {
            return preparacion.resultado();
        }
        try {
            recursos.validar(request, preparacion.pasajeros());
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 404 && exception.getStatusCode().value() != 409) {
                throw exception;
            }
            return transacciones.rechazar(clave, actorId, trasladoId, request, hash,
                    exception.getStatusCode().value(), exception.getReason());
        }
        return transacciones.procesar(clave, actorId, trasladoId, request, hash);
    }

    public AsignacionOperacionTransacciones.Consulta consultar(String clave, Long actorId) {
        validarClave(clave);
        return transacciones.consultar(clave, actorId);
    }

    private void validarClave(String clave) {
        if (clave == null || clave.isBlank() || clave.codePointCount(0, clave.length()) > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key es obligatoria y debe tener hasta 100 caracteres");
        }
    }

    private String huella(Long trasladoId, AsignacionRequest request) {
        try {
            String datos = trasladoId + ":" + request.conductorId() + ":" + request.vehiculoId();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(datos.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
