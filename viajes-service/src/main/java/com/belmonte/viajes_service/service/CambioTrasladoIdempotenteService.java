package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.CancelacionRequest;
import com.belmonte.viajes_service.dto.EstadoRequest;
import com.belmonte.viajes_service.repository.OperacionTrasladoRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CambioTrasladoIdempotenteService {
    private final CambioTrasladoTransacciones transacciones;
    private final OperacionTrasladoRepository operaciones;
    private final ObjectMapper json;

    public CambioTrasladoIdempotenteService(CambioTrasladoTransacciones transacciones,
                                            OperacionTrasladoRepository operaciones, ObjectMapper json) {
        this.transacciones = transacciones;
        this.operaciones = operaciones;
        this.json = json;
    }

    @Transactional(propagation = Propagation.NEVER)
    public CambioTrasladoTransacciones.Resultado cancelar(String clave, Long actor, Long id,
            CancelacionRequest request, boolean administrador, boolean solicitante, boolean conductor) {
        validarMotivo(request.motivo());
        return ejecutar(clave, actor, new CambioTrasladoTransacciones.Cancelacion(id, request),
                administrador, solicitante, conductor);
    }

    @Transactional(propagation = Propagation.NEVER)
    public CambioTrasladoTransacciones.Resultado cambiarEstado(String clave, Long actor, Long id,
                                                                EstadoRequest request) {
        validarMotivo(request.motivo());
        return ejecutar(clave, actor, new CambioTrasladoTransacciones.Estado(id, request), false, false, true);
    }

    private CambioTrasladoTransacciones.Resultado ejecutar(String clave, Long actor,
            CambioTrasladoTransacciones.Intencion intencion, boolean administrador, boolean solicitante, boolean conductor) {
        validarClave(clave);
        String hash = huella(intencion);
        try {
            return transacciones.ejecutar(clave, actor, intencion, hash,
                    administrador, solicitante, conductor);
        } catch (DuplicateKeyException exception) {
            if (!operaciones.existsById(clave)) {
                throw exception;
            }
            return transacciones.ejecutar(clave, actor, intencion, hash,
                    administrador, solicitante, conductor);
        }
    }

    public CambioTrasladoTransacciones.Consulta consultar(String clave, Long actor, String tipo) {
        validarClave(clave);
        return transacciones.consultar(clave, actor, tipo);
    }

    private void validarClave(String clave) {
        if (clave == null || clave.isBlank() || clave.codePointCount(0, clave.length()) > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key es obligatoria y debe tener hasta 100 caracteres");
        }
    }

    private void validarMotivo(String motivo) {
        if (motivo != null && motivo.getBytes(StandardCharsets.UTF_8).length > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Motivo excede 500 bytes UTF-8");
        }
    }

    private String huella(CambioTrasladoTransacciones.Intencion intencion) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(intencion)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
