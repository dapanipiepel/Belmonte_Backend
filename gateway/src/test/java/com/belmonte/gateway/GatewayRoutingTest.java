package com.belmonte.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {
    private static final HttpServer usuarios = startUpstream("usuarios");
    private static final HttpServer vehiculos = startUpstream("vehiculos");
    private static final HttpServer viajes = startUpstream("viajes");

    @LocalServerPort
    private int port;

    @Autowired
    private HttpClientProperties httpClientProperties;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("USUARIOS_SERVICE_URI", () -> address(usuarios));
        registry.add("VEHICULOS_SERVICE_URI", () -> address(vehiculos));
        registry.add("VIAJES_SERVICE_URI", () -> address(viajes));
    }

    @AfterAll
    static void stopUpstreams() {
        usuarios.stop(0);
        vehiculos.stop(0);
        viajes.stop(0);
    }

    @Test
    void routesKnownPathsAndPreservesUpstreamErrors() throws Exception {
        for (String path : List.of("/api/auth/login", "/api/auth/logout", "/api/usuarios", "/api/usuarios/1",
                "/api/personas", "/api/roles", "/api/paises", "/api/tipos-documento",
                "/api/comunas", "/api/empresas", "/api/pasajeros", "/api/conductores")) {
            assertResponse(path, 200, "usuarios:" + path);
        }
        assertResponse("/api/vehiculos", 200, "vehiculos:/api/vehiculos");
        assertResponse("/api/vehiculos/catalogos/tipos", 200, "vehiculos:/api/vehiculos/catalogos/tipos");
        assertResponse("/api/traslados", 200, "viajes:/api/traslados");
        assertResponse("/api/traslados/42", 200, "viajes:/api/traslados/42");
        assertResponse("/api/vehiculos/failure", 503, "vehiculos:/api/vehiculos/failure");
        assertEquals(404, send("/api/unknown").statusCode());
        assertEquals(404, send("/api/auth/sessions/activa/validate").statusCode());
        assertEquals(404, send("/internal/conductores/1").statusCode());
        assertEquals(404, send("/internal/comunas/1").statusCode());
        HttpRequest pasajerosInterno = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/pasajeros/validar"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ids\":[1]}")).build();
        assertEquals(404, HttpClient.newHttpClient()
                .send(pasajerosInterno, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(404, send("/internal/vehiculos/1").statusCode());
        assertEquals(404, send("/internal/barreras/123").statusCode());
        HttpRequest internalPrepare = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/barreras/123"))
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(404, HttpClient.newHttpClient()
                .send(internalPrepare, HttpResponse.BodyHandlers.discarding()).statusCode());
        HttpRequest internalClose = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/barreras/123/cerrar"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(404, HttpClient.newHttpClient()
                .send(internalClose, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(404, send("/api/viajes").statusCode());
    }

    @Test
    void forwardsMethodQueryHeadersAndBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/usuarios/login?source=android"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"usuario\":\"prueba\"}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("usuarios:POST:/api/usuarios/login?source=android:application/json:{\"usuario\":\"prueba\"}",
                response.body());
        assertEquals("upstream", response.headers().firstValue("X-Test-Origin").orElseThrow());
    }

    @Test
    void routesPassengerSearchInRequestBody() throws Exception {
        String body = "{\"campo\":\"NOMBRE\",\"texto\":\"prueba\",\"pagina\":0,\"tamano\":50}";
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/pasajeros/busquedas"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("usuarios:POST:/api/pasajeros/busquedas:application/json:" + body, response.body());
    }

    @Test
    void bindsConfiguredTimeouts() {
        assertEquals(3000, httpClientProperties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(10), httpClientProperties.getResponseTimeout());
    }

    private void assertResponse(String path, int status, String body) throws Exception {
        HttpResponse<String> response = send(path);
        assertEquals(status, response.statusCode());
        assertEquals(body, response.body());
    }

    private HttpResponse<String> send(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String address(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer startUpstream(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String result = name + ":" + path;
                if ("POST".equals(exchange.getRequestMethod())) {
                    result = name + ":" + exchange.getRequestMethod() + ":" + exchange.getRequestURI()
                            + ":" + exchange.getRequestHeaders().getFirst("Content-Type") + ":"
                            + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                }
                byte[] body = result.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("X-Test-Origin", "upstream");
                exchange.sendResponseHeaders(path.endsWith("/failure") ? 503 : 200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start test upstream " + name, e);
        }
    }
}
