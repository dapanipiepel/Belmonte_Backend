package com.belmonte.usuarios_service.security;

import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import com.belmonte.usuarios_service.service.JwtService;
import com.belmonte.usuarios_service.service.RelojBaseDatos;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;
    private final SesionUsuarioRepository sesionRepository;
    private final RelojBaseDatos reloj;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   SesionUsuarioRepository sesionRepository, RelojBaseDatos reloj) {
        this.jwtService = jwtService;
        this.sesionRepository = sesionRepository;
        this.reloj = reloj;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");

        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                String token = authorization.substring(7);
                Claims claims = jwtService.parseToken(token, reloj.ahora().toInstant());
                String sessionId = claims.get("sessionId", String.class);
                if (sessionId == null || sessionId.isBlank()) throw new JwtException("Sesion ausente");

                var session = sesionRepository
                        .findBySesionIdAndActiva(sessionId, 1)
                        .filter(item -> item.vigenteEn(reloj.ahora().toInstant()))
                        .orElse(null);

                if (session != null) {
                    @SuppressWarnings("unchecked")
                    List<String> roles = claims.get("roles", List.class);
                    if (roles == null) throw new JwtException("Roles ausentes");
                    var authorities = roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();

                    var authentication = new UsernamePasswordAuthenticationToken(
                            claims.getSubject(),
                            null,
                            authorities
                    );
                    Object usuarioId = claims.get("usuarioId");
                    Long id = null;
                    if (usuarioId instanceof Integer value) id = value.longValue();
                    else if (usuarioId instanceof Long value) id = value;
                    if (id != null && id > 0 && id.equals(session.getUsuario().getIdUsuario())) {
                        var vencimiento = claims.getExpiration() == null ? null
                                : claims.getExpiration().toInstant().atOffset(ZoneOffset.UTC);
                        authentication.setDetails(new IdentidadSesion(id, sessionId, vencimiento));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (DataAccessException exception) {
                SecurityContextHolder.clearContext();
                log.warn("No se pudo consultar la sesion o el reloj de base de datos", exception);
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            } catch (JwtException | IllegalArgumentException | ClassCastException exception) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
