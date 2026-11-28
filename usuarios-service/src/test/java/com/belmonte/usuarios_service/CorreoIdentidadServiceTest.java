package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.belmonte.usuarios_service.service.CorreoIdentidadService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class CorreoIdentidadServiceTest {
    @Test
    void enviaEnlacePorSmtp() throws Exception {
        try (var servidor = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            servidor.setSoTimeout(5000);
            var recibido = executor.submit(() -> {
                try (var socket = servidor.accept()) {
                    socket.setSoTimeout(5000);
                    var entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var salida = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII);
                    salida.print("220 localhost ESMTP\r\n");
                    salida.flush();
                    StringBuilder mensaje = new StringBuilder();
                    boolean datos = false;
                    String linea;
                    while ((linea = entrada.readLine()) != null) {
                        if (datos && linea.equals(".")) {
                            datos = false;
                            salida.print("250 aceptado\r\n");
                        } else if (datos) {
                            mensaje.append(linea).append('\n');
                            continue;
                        } else if (linea.startsWith("DATA")) {
                            datos = true;
                            salida.print("354 continuar\r\n");
                        } else if (linea.startsWith("QUIT")) {
                            salida.print("221 adios\r\n");
                            salida.flush();
                            break;
                        } else {
                            salida.print("250 OK\r\n");
                        }
                        salida.flush();
                    }
                    return mensaje.toString();
                }
            });
            var mail = new JavaMailSenderImpl();
            mail.setHost("127.0.0.1");
            mail.setPort(servidor.getLocalPort());
            mail.getJavaMailProperties().put("mail.smtp.connectiontimeout", "5000");
            mail.getJavaMailProperties().put("mail.smtp.timeout", "5000");
            var servicio = new CorreoIdentidadService(mail, "origen@example.invalid",
                    "https://cliente.example.invalid/activar", "https://cliente.example.invalid/recuperar");
            servicio.invitacion("destino@example.invalid", "token-prueba");
            String mensaje = recibido.get(10, TimeUnit.SECONDS);
            assertTrue(mensaje.contains("destino@example.invalid"));
            assertTrue(mensaje.contains("https://cliente.example.invalid/activar?token=token-prueba"));
        }
    }
}
