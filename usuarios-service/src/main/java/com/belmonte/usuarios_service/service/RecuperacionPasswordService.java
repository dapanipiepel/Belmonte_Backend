package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.model.RecuperacionPassword;
import com.belmonte.usuarios_service.model.Usuario;
import com.belmonte.usuarios_service.repository.RecuperacionPasswordRepository;
import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.security.TokenUtil;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecuperacionPasswordService {
    private final UsuarioRepository usuarios;
    private final RecuperacionPasswordRepository recuperaciones;
    private final SesionUsuarioRepository sesiones;
    private final TokenUtil tokens;
    private final RelojBaseDatos reloj;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public RecuperacionPasswordService(UsuarioRepository usuarios, RecuperacionPasswordRepository recuperaciones,
            SesionUsuarioRepository sesiones, TokenUtil tokens, RelojBaseDatos reloj) {
        this.usuarios = usuarios;
        this.recuperaciones = recuperaciones;
        this.sesiones = sesiones;
        this.tokens = tokens;
        this.reloj = reloj;
    }

    @Transactional
    public TokenRecuperacion emitir(Long usuarioId) {
        return emitir(usuarioId, null);
    }

    @Transactional
    public TokenRecuperacion emitirParaEmail(Long usuarioId, String email) {
        return emitir(usuarioId, email);
    }

    private TokenRecuperacion emitir(Long usuarioId, String email) {
        Usuario usuario = usuarios.findForUpdate(usuarioId)
                .filter(u -> Integer.valueOf(1).equals(u.getActivo()))
                .filter(u -> email == null || email.equals(u.getPersona().getEmail()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no disponible"));
        OffsetDateTime ahora = reloj.ahora();
        String token = tokens.generarTokenSeguro();
        RecuperacionPassword recuperacion = new RecuperacionPassword();
        recuperacion.setUsuario(usuario);
        recuperacion.setTokenHash(tokens.hashTokenSHA256(token));
        recuperacion.setFechaCreacion(ahora);
        recuperacion.setFechaExpiracion(ahora.plusHours(1));
        recuperaciones.save(recuperacion);
        return new TokenRecuperacion(token, recuperacion.getFechaExpiracion());
    }

    @Transactional
    public void cambiarPassword(String token, String nuevaPassword) {
        if (token == null || token.isBlank() || nuevaPassword == null || nuevaPassword.isBlank()
                || nuevaPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token o contrasena invalidos");
        }
        String hash = tokens.hashTokenSHA256(token);
        Long usuarioId = recuperaciones.findUsuarioIdByTokenHash(hash).orElseThrow(this::tokenInvalido);
        Usuario usuario = usuarios.findForUpdate(usuarioId).orElseThrow(this::tokenInvalido);
        RecuperacionPassword recuperacion = recuperaciones.findByTokenHash(hash).orElseThrow(this::tokenInvalido);
        OffsetDateTime ahora = reloj.ahora();
        if (!Integer.valueOf(1).equals(usuario.getActivo()) || recuperacion.isUsada()
                || ahora.isBefore(recuperacion.getFechaCreacion())
                || !ahora.isBefore(recuperacion.getFechaExpiracion())
                || !recuperaciones.findUltimaIdPorUsuario(usuarioId).orElseThrow(this::tokenInvalido)
                        .equals(recuperacion.getIdRecuperacion())) {
            throw tokenInvalido();
        }
        usuario.setPassword(passwordEncoder.encode(nuevaPassword));
        recuperacion.setUsada(true);
        recuperacion.setFechaUso(ahora);
        sesiones.revokeAllByUsuarioId(usuarioId);
    }

    private ResponseStatusException tokenInvalido() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enlace de recuperacion invalido o vencido");
    }

    public record TokenRecuperacion(String token, OffsetDateTime fechaExpiracion) {
        @Override
        public String toString() {
            return "TokenRecuperacion[fechaExpiracion=" + fechaExpiracion + "]";
        }
    }
}
