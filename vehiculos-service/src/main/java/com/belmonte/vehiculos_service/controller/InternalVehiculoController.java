package com.belmonte.vehiculos_service.controller;

import com.belmonte.vehiculos_service.repository.VehiculoRepository;
import com.belmonte.vehiculos_service.security.InternalApiAuthentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalVehiculoController {
    private final VehiculoRepository vehiculos;
    private final InternalApiAuthentication authentication;

    public InternalVehiculoController(VehiculoRepository vehiculos, InternalApiAuthentication authentication) {
        this.vehiculos = vehiculos;
        this.authentication = authentication;
    }

    @GetMapping("/internal/vehiculos/{vehiculoId}")
    public VehiculoInternoResponse consultar(
            @PathVariable Long vehiculoId,
            @RequestHeader(value = "X-Belmonte-Internal-Secret", required = false) String secret) {
        authentication.requireValidSecret(secret);
        var vehiculo = vehiculos.findById(vehiculoId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehiculo no encontrado"));
        return new VehiculoInternoResponse(vehiculo.getId(), vehiculo.getActivo(),
                vehiculo.getCapacidadPasajeros());
    }

    public record VehiculoInternoResponse(Long vehiculoId, boolean activo, int capacidadPasajeros) {}
}
