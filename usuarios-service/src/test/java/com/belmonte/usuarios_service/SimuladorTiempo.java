package com.belmonte.usuarios_service;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class SimuladorTiempo {
    private static final int TAMANO_DESAFIO = 32;
    private static final int TAMANO_FIRMA = 64;

    record Respuesta(byte[] desafio, Instant limiteSuperior, Duration incertidumbre,
                     Duration edadSincronizacion, boolean sincronizada, byte[] firma) {}

    static Respuesta firmar(KeyPair claves, byte[] desafio, Instant limite, Duration incertidumbre,
                            Duration edad, boolean sincronizada) throws GeneralSecurityException {
        var respuesta = new Respuesta(desafio.clone(), limite, incertidumbre, edad, sincronizada, null);
        var firmador = Signature.getInstance("Ed25519");
        firmador.initSign(claves.getPrivate());
        firmador.update(contenido(respuesta));
        return new Respuesta(respuesta.desafio(), limite, incertidumbre, edad, sincronizada, firmador.sign());
    }

    static Instant consultar(PublicKey clave, Duration maxIncertidumbre, Duration maxEdad,
                             Function<byte[], Respuesta> fuente) {
        if (clave == null || maxIncertidumbre == null || maxIncertidumbre.isNegative()
                || maxEdad == null || maxEdad.isNegative() || fuente == null) {
            throw noDisponible();
        }
        byte[] desafio = new byte[TAMANO_DESAFIO];
        new SecureRandom().nextBytes(desafio);
        Respuesta respuesta = fuente.apply(desafio.clone());
        if (respuesta == null || respuesta.desafio() == null || respuesta.desafio().length != TAMANO_DESAFIO
                || !Arrays.equals(desafio, respuesta.desafio()) || respuesta.limiteSuperior() == null
                || respuesta.incertidumbre() == null || respuesta.incertidumbre().isNegative()
                || respuesta.incertidumbre().compareTo(maxIncertidumbre) > 0
                || respuesta.edadSincronizacion() == null || respuesta.edadSincronizacion().isNegative()
                || respuesta.edadSincronizacion().compareTo(maxEdad) > 0 || !respuesta.sincronizada()
                || respuesta.firma() == null || respuesta.firma().length != TAMANO_FIRMA) {
            throw noDisponible();
        }
        try {
            var verificador = Signature.getInstance("Ed25519");
            verificador.initVerify(clave);
            verificador.update(contenido(respuesta));
            if (!verificador.verify(respuesta.firma())) throw noDisponible();
        } catch (GeneralSecurityException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firma de tiempo invalida", exception);
        }
        return respuesta.limiteSuperior();
    }

    private static byte[] contenido(Respuesta respuesta) {
        return ByteBuffer.allocate(4 + TAMANO_DESAFIO + 3 * (Long.BYTES + Integer.BYTES) + 1)
                .putInt(1)
                .put(respuesta.desafio())
                .putLong(respuesta.limiteSuperior().getEpochSecond())
                .putInt(respuesta.limiteSuperior().getNano())
                .putLong(respuesta.incertidumbre().getSeconds())
                .putInt(respuesta.incertidumbre().getNano())
                .putLong(respuesta.edadSincronizacion().getSeconds())
                .putInt(respuesta.edadSincronizacion().getNano())
                .put((byte) (respuesta.sincronizada() ? 1 : 0))
                .array();
    }

    private static ResponseStatusException noDisponible() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Prueba de tiempo simulada invalida");
    }

    private SimuladorTiempo() {}
}
