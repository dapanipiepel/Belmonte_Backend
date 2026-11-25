package com.belmonte.usuarios_service.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SesionUsuarioTest {
    @Test
    void instanteDistingueHorasRepetidasEnCambioDeHorario() {
        var sesion = new SesionUsuario();
        sesion.setFechaExpiracion(LocalDateTime.parse("2024-11-03T01:45:00"));
        sesion.setFechaExpiracionInstante(OffsetDateTime.parse("2024-11-03T06:00:00Z"));
        ZoneId zona = ZoneId.of("America/New_York");
        Instant primera = Instant.parse("2024-11-03T05:30:00Z");
        Instant segunda = Instant.parse("2024-11-03T06:30:00Z");
        assertEquals(LocalDateTime.ofInstant(primera, zona), LocalDateTime.ofInstant(segunda, zona));
        assertTrue(sesion.vigenteEn(primera));
        assertFalse(sesion.vigenteEn(Instant.parse("2024-11-03T06:00:00Z")));
        assertFalse(sesion.vigenteEn(segunda));
    }

    @Test
    void sesionSinInstanteNuncaEstaVigente() {
        var sesion = new SesionUsuario();
        sesion.setFechaExpiracion(LocalDateTime.parse("2024-11-03T01:45:00"));
        assertFalse(sesion.vigenteEn(Instant.parse("2024-11-03T05:30:00Z")));
    }
}
