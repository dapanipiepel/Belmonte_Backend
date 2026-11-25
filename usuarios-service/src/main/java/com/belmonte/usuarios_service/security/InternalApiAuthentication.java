package com.belmonte.usuarios_service.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InternalApiAuthentication {
    private final String secret;

    public InternalApiAuthentication(@Value("${internal.api.secret}") String secret) {
        this.secret = secret;
    }

    public void requireValidSecret(String suppliedSecret) {
        if (suppliedSecret == null || !MessageDigest.isEqual(
                suppliedSecret.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Credencial interna invalida");
        }
    }
}
