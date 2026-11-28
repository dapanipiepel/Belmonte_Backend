package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.dto.AsignacionRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
public class ValidacionRecursosClient {
    private static final Logger log = LoggerFactory.getLogger(ValidacionRecursosClient.class);
    private final String usuariosUri;
    private final String vehiculosUri;
    private final String secret;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public ValidacionRecursosClient(@Value("${usuarios.service.uri}") String usuariosUri,
                                   @Value("${vehiculos.service.uri}") String vehiculosUri,
                                   @Value("${internal.api.secret}") String secret, ObjectMapper json) {
        this.usuariosUri = usuariosUri;
        this.vehiculosUri = vehiculosUri;
        this.secret = secret;
        this.json = json;
    }

    public void validar(AsignacionRequest request, int pasajeros) {
        JsonNode conductor = consultar(usuariosUri + "/internal/conductores/" + request.conductorId(),
                "Conductor");
        validarId(conductor, "usuarioId", request.conductorId());
        JsonNode rol = conductor.get("rol");
        JsonNode activo = conductor.get("activo");
        if (rol == null || !rol.isTextual() || activo == null || !activo.isBoolean()) {
            throw respuestaInvalida("conductor");
        }
        if (!activo.booleanValue() || !"CONDUCTOR".equals(rol.asString())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conductor inactivo o con rol invalido");
        }

        JsonNode vehiculo = consultar(vehiculosUri + "/internal/vehiculos/" + request.vehiculoId(), "Vehiculo");
        validarId(vehiculo, "vehiculoId", request.vehiculoId());
        JsonNode vehiculoActivo = vehiculo.get("activo");
        JsonNode capacidad = vehiculo.get("capacidadPasajeros");
        if (vehiculoActivo == null || !vehiculoActivo.isBoolean()
                || capacidad == null || !capacidad.isIntegralNumber() || !capacidad.canConvertToInt()
                || capacidad.intValue() <= 0) {
            throw respuestaInvalida("vehiculo");
        }
        if (!vehiculoActivo.booleanValue() || capacidad.intValue() < pasajeros) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vehiculo inactivo o sin capacidad suficiente");
        }
    }

    public void validarComunas(Long origen, Long destino) {
        validarComuna(origen);
        if (!origen.equals(destino)) {
            validarComuna(destino);
        }
    }

    private void validarComuna(Long id) {
        JsonNode comuna = consultar(usuariosUri + "/internal/comunas/" + id, "Comuna");
        validarId(comuna, "idComuna", id);
    }

    public void validarPasajeros(Set<Long> ids) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(usuariosUri + "/internal/pasajeros/validar"))
                .timeout(Duration.ofSeconds(3))
                .header("X-Belmonte-Internal-Secret", secret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("ids", ids))))
                .build();
        JsonNode resultado = consultar(request, "Pasajero");
        JsonNode recibidos = resultado.get("ids");
        if (recibidos == null || !recibidos.isArray() || recibidos.size() != ids.size()) {
            throw respuestaInvalida("pasajeros");
        }
        Set<Long> comprobados = new HashSet<>();
        for (JsonNode id : recibidos) {
            if (!id.isIntegralNumber() || !id.canConvertToLong() || !comprobados.add(id.longValue())) {
                throw respuestaInvalida("pasajeros");
            }
        }
        if (!comprobados.equals(ids)) {
            throw respuestaInvalida("pasajeros");
        }
    }

    private JsonNode consultar(String uri, String recurso) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3))
                .header("X-Belmonte-Internal-Secret", secret).GET().build();
        return consultar(request, recurso);
    }

    private JsonNode consultar(HttpRequest request, String recurso) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            log.warn("No se pudo consultar {}: {}", recurso, exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio de " + recurso + " no disponible");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Se interrumpio la consulta de {}", recurso);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio de " + recurso + " no disponible");
        }
        if (response.statusCode() == 404) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, recurso + " no encontrado");
        }
        if (response.statusCode() != 200) {
            log.warn("La consulta de {} respondio HTTP {}", recurso, response.statusCode());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio de " + recurso + " no disponible");
        }
        try {
            JsonNode result = json.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw respuestaInvalida(recurso);
            }
            return result;
        } catch (JacksonException exception) {
            throw respuestaInvalida(recurso);
        }
    }

    private void validarId(JsonNode node, String campo, Long esperado) {
        JsonNode id = node.get(campo);
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != esperado) {
            throw respuestaInvalida(campo);
        }
    }

    private ResponseStatusException respuestaInvalida(String recurso) {
        log.warn("Respuesta interna invalida para {}", recurso);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Respuesta de dependencia invalida");
    }
}
