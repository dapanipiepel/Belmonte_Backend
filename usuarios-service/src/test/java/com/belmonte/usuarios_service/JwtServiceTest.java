package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.belmonte.usuarios_service.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;

class JwtServiceTest {

    @Test
    void createsSignedTokenWithRequiredClaims() {
        JwtService jwtService = new JwtService(
                "0123456789abcdef0123456789abcdef0123456789abcdef",
                43200000);

        String token = jwtService.createToken(
                "prueba@example.invalid", 42L, "session-42",
                List.of("ADMINISTRADOR"));
        Claims claims = jwtService.parseToken(token);

        assertEquals("prueba@example.invalid", claims.getSubject());
        assertEquals(42L, claims.get("usuarioId", Integer.class).longValue());
        assertEquals("session-42", claims.get("sessionId", String.class));
        assertEquals(List.of("ADMINISTRADOR"), claims.get("roles", List.class));
        assertTrue(claims.get("expiresAt", Long.class) > claims.get("issuedAt", Long.class));
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtService jwtService = new JwtService(
                "0123456789abcdef0123456789abcdef0123456789abcdef",
                1);
        String token = jwtService.createToken(
                "prueba@example.invalid", 42L, "session-42",
                List.of("CONDUCTOR"));

        Thread.sleep(10);
        assertThrows(ExpiredJwtException.class, () -> jwtService.parseToken(token));
    }

    @Test
    void tokenSeEmiteYValidaConLaReferenciaRecibida() {
        JwtService jwtService = new JwtService(
                "0123456789abcdef0123456789abcdef0123456789abcdef", 60000);
        Instant inicio = Instant.parse("2030-01-02T03:04:05.123Z");
        String token = jwtService.createToken("prueba@example.invalid", 42L, "session-42",
                List.of("ADMINISTRADOR"), inicio);
        Claims claims = jwtService.parseToken(token, inicio.plusSeconds(30));
        assertEquals(Instant.parse("2030-01-02T03:05:05Z"), claims.getExpiration().toInstant());
        assertThrows(ExpiredJwtException.class, () -> jwtService.parseToken(token, inicio.plusSeconds(61)));
    }
}
