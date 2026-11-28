package com.belmonte.usuarios_service.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"No autorizado\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"Acceso denegado\"}");
                        }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/activar",
                                "/api/auth/recuperacion", "/api/auth/recuperacion/cambiar").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/auth/sessions/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/conductores/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/comunas/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/pasajeros/validar").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/empresas").hasAnyRole("ADMINISTRADOR", "SOLICITANTE")
                        .requestMatchers(HttpMethod.GET, "/api/empresas/**").hasRole("ADMINISTRADOR")
                        .requestMatchers(HttpMethod.POST, "/api/empresas/**").hasRole("ADMINISTRADOR")
                        .requestMatchers("/api/pasajeros", "/api/pasajeros/**")
                            .hasAnyRole("ADMINISTRADOR", "SOLICITANTE")
                        .requestMatchers("/api/personas", "/api/personas/**", "/api/roles", "/api/roles/**")
                            .hasRole("ADMINISTRADOR")
                        .requestMatchers("/api/invitaciones", "/api/invitaciones/**").hasRole("ADMINISTRADOR")
                        .requestMatchers(HttpMethod.POST, "/api/paises/**", "/api/tipos-documento/**")
                            .hasRole("ADMINISTRADOR")
                        .requestMatchers(HttpMethod.POST, "/api/usuarios/**")
                            .hasRole("ADMINISTRADOR")
                        .requestMatchers(HttpMethod.GET, "/api/usuarios/**")
                            .hasRole("ADMINISTRADOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/usuarios/**")
                            .hasRole("ADMINISTRADOR")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
