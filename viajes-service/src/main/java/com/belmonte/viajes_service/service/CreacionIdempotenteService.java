package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.TrasladoRequest;
import com.belmonte.viajes_service.dto.UbicacionRequest;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CreacionIdempotenteService {
    private final CreacionOperacionTransacciones transacciones;
    private final OperacionTrasladoRepository operaciones;
    private final ValidacionRecursosClient recursos;
    private final ObjectMapper json;

    public CreacionIdempotenteService(CreacionOperacionTransacciones transacciones,
                                     OperacionTrasladoRepository operaciones,
                                     ValidacionRecursosClient recursos, ObjectMapper json) {
        this.transacciones = transacciones;
        this.operaciones = operaciones;
        this.recursos = recursos;
        this.json = json;
    }

    @Transactional(propagation = Propagation.NEVER)
    public CreacionOperacionTransacciones.Resultado crear(String clave, Long actor, String sesion,
                                                          OffsetDateTime jwtExpiraEn, TrasladoRequest request) {
        validarClave(clave);
        if (sesion == null || sesion.isBlank() || sesion.length() > 36 || jwtExpiraEn == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        String hash = huella(request);
        boolean reservaNueva = true;
        try {
            transacciones.reservar(clave, actor, sesion, jwtExpiraEn, hash);
        } catch (DuplicateKeyException exception) {
            if (!operaciones.existsById(clave)) {
                throw exception;
            }
            reservaNueva = false;
        }
        var anterior = transacciones.preparar(clave, actor, sesion, hash);
        if (anterior != null) {
            return anterior;
        }
        if (!reservaNueva) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Operacion pendiente de confirmacion");
        }
        if (request.pasajeroIds().size() > 50) {
            return transacciones.rechazar(clave, actor, sesion, hash, 400,
                    "Un traslado admite hasta 50 pasajeros");
        }
        try {
            recursos.validarComunas(request.origen().comunaId(), request.destino().comunaId());
            recursos.validarPasajeros(request.pasajeroIds());
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw exception;
            }
            return transacciones.rechazar(clave, actor, sesion, hash, 404, exception.getReason());
        }
        try {
            return transacciones.procesar(clave, actor, sesion, hash, request);
        } catch (ResponseStatusException exception) {
            if (!exception.getStatusCode().is4xxClientError()) {
                throw exception;
            }
            return transacciones.rechazar(clave, actor, sesion, hash,
                    exception.getStatusCode().value(), exception.getReason());
        }
    }

    public CreacionOperacionTransacciones.Consulta consultar(String clave, Long actor) {
        validarClave(clave);
        return transacciones.consultar(clave, actor);
    }

    private void validarClave(String clave) {
        if (clave == null || clave.isBlank() || clave.codePointCount(0, clave.length()) > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key es obligatoria y debe tener hasta 100 caracteres");
        }
    }

    private String huella(TrasladoRequest request) {
        SolicitudCanonica datos = new SolicitudCanonica(request.origen(), request.destino(),
                request.fechaHoraViaje().toInstant(), request.fechaHoraTerminoEstimado().toInstant(),
                request.numeroVuelo(), request.pasajeroIds().stream().sorted().toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(datos)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private record SolicitudCanonica(UbicacionRequest origen, UbicacionRequest destino, Instant inicio,
                                     Instant termino, String vuelo, List<Long> pasajeros) {}
}
