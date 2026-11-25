package com.belmonte.viajes_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.net.URLEncoder;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

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
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo configurar JWT", exception);
        }
    }

    private boolean sessionIsActive(String sessionId, java.util.Date jwtExpiration) throws IOException, InterruptedException {
        String expirationQuery = jwtExpiration == null ? "" : "?jwtExpiraEn=" + jwtExpiration.getTime();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(usuariosServiceUri + "/api/auth/sessions/"
                                + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                                + "/validate" + expirationQuery))
                .timeout(Duration.ofSeconds(3))
                .header("X-Belmonte-Internal-Secret", internalApiSecret)
                .GET()
                .build();
        int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status == 200) return true;
        if (status == 401) return false;
        log.warn("La validacion interna de sesion respondio HTTP {}", status);
        throw new IOException("La validacion interna de sesion respondio HTTP " + status);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");

        if (authorization != null && authorization.startsWith("Bearer ")) {
            Claims claims;
            try {
                claims = Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(authorization.substring(7))
                        .getPayload();
            } catch (JwtException | IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            Object rolesClaim = claims.get("roles");
            Object sessionClaim = claims.get("sessionId");
            if (!(rolesClaim instanceof List<?> rolesList) || !(sessionClaim instanceof String sessionId)) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            List<String> roles = rolesList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
            boolean active;
            try {
                active = sessionIsActive(sessionId, claims.getExpiration());
            } catch (IOException exception) {
                log.warn("No se pudo validar la sesion con usuarios: {}", exception.getClass().getSimpleName());
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("Se interrumpio la validacion de la sesion con usuarios");
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
            if (!active) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            var authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            var authentication = new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);
            var vencimiento = claims.getExpiration() == null ? null
                    : claims.getExpiration().toInstant().atOffset(ZoneOffset.UTC);
            authentication.setDetails(new IdentidadSesion(sessionId, vencimiento));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
