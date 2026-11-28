package com.belmonte.usuarios_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class CorreoIdentidadService {
    private final JavaMailSender mail;
    private final String remitente;
    private final String activacionUrl;
    private final String recuperacionUrl;

    public CorreoIdentidadService(JavaMailSender mail, @Value("${belmonte.mail.from}") String remitente,
            @Value("${belmonte.enlaces.activacion}") String activacionUrl,
            @Value("${belmonte.enlaces.recuperacion}") String recuperacionUrl) {
        this.mail = mail;
        this.remitente = remitente;
        this.activacionUrl = activacionUrl;
        this.recuperacionUrl = recuperacionUrl;
    }

    public void invitacion(String email, String token) {
        enviar(email, "Activacion de cuenta Belmonte", activacionUrl, token, "48 horas");
    }

    public void recuperacion(String email, String token) {
        enviar(email, "Recuperacion de contrasena Belmonte", recuperacionUrl, token, "1 hora");
    }

    private void enviar(String email, String asunto, String baseUrl, String token, String vigencia) {
        String enlace = UriComponentsBuilder.fromUriString(baseUrl).queryParam("token", token).build().encode().toUriString();
        SimpleMailMessage mensaje = new SimpleMailMessage();
        mensaje.setFrom(remitente);
        mensaje.setTo(email);
        mensaje.setSubject(asunto);
        mensaje.setText("Abre el siguiente enlace para continuar. Vence en " + vigencia + ":\n" + enlace
                + "\nSi no esperabas este correo, ignoralo.");
        mail.send(mensaje);
    }
}
