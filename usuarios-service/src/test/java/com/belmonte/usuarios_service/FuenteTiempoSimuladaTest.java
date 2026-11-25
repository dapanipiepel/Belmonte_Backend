package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FuenteTiempoSimuladaTest {
    private static final Duration MAX_ERROR = Duration.ofSeconds(2);
    private static final Duration MAX_EDAD = Duration.ofSeconds(60);
    private static final Instant LIMITE = Instant.parse("2030-01-02T03:04:05Z");
    private KeyPair claves;

    @BeforeEach
    void crearClaves() throws GeneralSecurityException {
        claves = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void aceptaRespuestaFirmadaVinculadaAlDesafioConLimitesPermitidos() {
        assertEquals(LIMITE, consultar(desafio -> firmar(desafio, MAX_ERROR, MAX_EDAD, true)));
    }

    @Test
    void rechazaRespuestaRepetidaYFirmaAlterada() {
        var anterior = new AtomicReference<SimuladorTiempo.Respuesta>();
        consultar(desafio -> {
            var respuesta = firmar(desafio, Duration.ZERO, Duration.ZERO, true);
            anterior.set(respuesta);
            return respuesta;
        });
        noDisponible(desafio -> anterior.get());
        noDisponible(desafio -> {
            var original = firmar(desafio, Duration.ZERO, Duration.ZERO, true);
            return new SimuladorTiempo.Respuesta(original.desafio(), LIMITE.minusSeconds(1),
                    original.incertidumbre(), original.edadSincronizacion(), true, original.firma());
        });
        noDisponible(desafio -> {
            var original = firmar(desafio, Duration.ZERO, Duration.ZERO, true);
            byte[] firma = original.firma().clone();
            firma[0] ^= 1;
            return new SimuladorTiempo.Respuesta(original.desafio(), LIMITE,
                    original.incertidumbre(), original.edadSincronizacion(), true, firma);
        });
    }

    @Test
    void rechazaOtraIdentidadYDatosIncompletos() throws GeneralSecurityException {
        var otraClave = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertEquals(503, assertThrows(ResponseStatusException.class, () ->
                SimuladorTiempo.consultar(otraClave.getPublic(), MAX_ERROR, MAX_EDAD,
                        desafio -> firmar(desafio, Duration.ZERO, Duration.ZERO, true))).getStatusCode().value());
        noDisponible(desafio -> null);
        noDisponible(desafio -> new SimuladorTiempo.Respuesta(desafio, null, Duration.ZERO, Duration.ZERO, true, null));
    }

    @Test
    void rechazaPerdidaDeSincronizacionYFueraDePolitica() {
        noDisponible(desafio -> firmar(desafio, Duration.ZERO, Duration.ZERO, false));
        noDisponible(desafio -> firmar(desafio, MAX_ERROR.plusNanos(1), Duration.ZERO, true));
        noDisponible(desafio -> firmar(desafio, Duration.ZERO, MAX_EDAD.plusNanos(1), true));
        noDisponible(desafio -> firmar(desafio, Duration.ofNanos(-1), Duration.ZERO, true));
        noDisponible(desafio -> firmar(desafio, Duration.ZERO, Duration.ofNanos(-1), true));
    }

    private Instant consultar(Function<byte[], SimuladorTiempo.Respuesta> fuente) {
        return SimuladorTiempo.consultar(claves.getPublic(), MAX_ERROR, MAX_EDAD, fuente);
    }

    private void noDisponible(Function<byte[], SimuladorTiempo.Respuesta> fuente) {
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> consultar(fuente)).getStatusCode().value());
    }

    private SimuladorTiempo.Respuesta firmar(byte[] desafio, Duration error, Duration edad, boolean sincronizada) {
        try {
            return SimuladorTiempo.firmar(claves, desafio, LIMITE, error, edad, sincronizada);
        } catch (GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }
}
