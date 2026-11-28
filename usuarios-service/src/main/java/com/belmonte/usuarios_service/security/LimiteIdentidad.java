package com.belmonte.usuarios_service.security;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LimiteIdentidad {
    private static final long VENTANA_NANOS = 60_000_000_000L;
    private static final int MAX_ORIGENES = 10000;
    private final Map<String, Ventana> ventanas = new HashMap<>();

    public synchronized void comprobar(String origen) {
        long ahora = System.nanoTime();
        ventanas.values().removeIf(ventana -> ahora - ventana.inicio() >= VENTANA_NANOS);
        Ventana actual = ventanas.get(origen);
        if (actual == null) {
            if (ventanas.size() >= MAX_ORIGENES) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Limite de solicitudes alcanzado");
            }
            ventanas.put(origen, new Ventana(ahora, 1));
        } else if (actual.cantidad() >= 10) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Limite de solicitudes alcanzado");
        } else {
            ventanas.put(origen, new Ventana(actual.inicio(), actual.cantidad() + 1));
        }
    }

    private record Ventana(long inicio, int cantidad) {}
}
