package com.belmonte.usuarios_service.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class BarreraViajesClient {
    private static final Logger log = LoggerFactory.getLogger(BarreraViajesClient.class);
    private final String viajesUri;
    private final String secret;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public BarreraViajesClient(@Value("${viajes.service.uri}") String viajesUri,
                               @Value("${internal.api.secret}") String secret, ObjectMapper json) {
        this.viajesUri = viajesUri;
        this.secret = secret;
        this.json = json;
    }

    public String preparar(String barrera, Long usuarioId, String proposito) {
        var response = enviar(barrera, usuarioId, proposito, false);
        if (response.statusCode() == 200 || response.statusCode() == 409) {
            try {
                JsonNode body = json.readTree(response.body());
                JsonNode estado = body == null || !body.isObject() ? null : body.get("estado");
                if (estado != null && estado.isTextual()) {
                    String valor = estado.asString();
                    if (response.statusCode() == 200 && ("ABIERTA".equals(valor) || "CERRADA".equals(valor))) {
                        return valor;
                    }
                    if (response.statusCode() == 409 && "BAJA".equals(proposito) && "RECHAZADA".equals(valor)) {
                        return valor;
                    }
                }
            } catch (JacksonException exception) {
                log.warn("La preparacion de barrera devolvio JSON invalido");
            }
        }
        log.warn("Respuesta interna de preparacion de barrera invalida o fallida: HTTP {}", response.statusCode());
        throw noDisponible();
    }

    public void cerrar(String barrera, Long usuarioId, String proposito) {
        var response = enviar(barrera, usuarioId, proposito, true);
        if (response.statusCode() != 204) {
            log.warn("El cierre de barrera respondio HTTP {}", response.statusCode());
            throw noDisponible();
        }
    }

    private HttpResponse<String> enviar(String barrera, Long usuarioId, String proposito, boolean cierre) {
        String body = json.writeValueAsString(Map.of("tipo", "CONDUCTOR", "recursoId", usuarioId,
                "proposito", proposito));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(viajesUri + "/internal/barreras/"
                        + barrera + (cierre ? "/cerrar" : "")))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("X-Belmonte-Internal-Secret", secret);
        if (cierre) request.POST(HttpRequest.BodyPublishers.ofString(body));
        else request.PUT(HttpRequest.BodyPublishers.ofString(body));
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            log.warn("No se pudo contactar viajes: {}", exception.getClass().getSimpleName());
            throw noDisponible();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Se interrumpio la comunicacion con viajes");
            throw noDisponible();
        }
    }

    private ResponseStatusException noDisponible() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coordinacion con viajes no disponible");
    }
}
