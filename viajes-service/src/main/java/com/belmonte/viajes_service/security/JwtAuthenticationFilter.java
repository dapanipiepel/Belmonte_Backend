package com.belmonte.viajes_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URLEncoder;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final SecretKey key;
    private final String usuariosServiceUri;
    private final String internalApiSecret;
    private final HttpClient httpClient;

    public JwtAuthenticationFilter(
            @Value("${jwt.secret}") String secret,
            @Value("${usuarios.service.uri:http://127.0.0.1:8081}") String usuariosServiceUri,
            @Value("${internal.api.secret}") String internalApiSecret) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = Keys.hmacShaKeyFor(keyBytes);
            this.usuariosServiceUri = usuariosServiceUri;
            this.internalApiSecret = internalApiSecret;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo configurar JWT", exception);
        }
    }

    private boolean sessionIsActive(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(usuariosServiceUri + "/api/auth/sessions/"
                                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                                    + "/validate"))
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Belmonte-Internal-Secret", internalApiSecret)
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");

        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(authorization.substring(7))
                        .getPayload();
                @SuppressWarnings("unchecked")
                List<String> roles = claims.get("roles", List.class);
                String sessionId = claims.get("sessionId", String.class);
                if (!sessionIsActive(sessionId)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }
                var authorities = roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                claims.getSubject(), null, authorities));
            } catch (RuntimeException exception) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
