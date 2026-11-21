package com.belmonte.vehiculos_service.controller;

import com.belmonte.vehiculos_service.dto.VehiculoRequest;
import com.belmonte.vehiculos_service.dto.VehiculoResponse;
import com.belmonte.vehiculos_service.service.VehiculoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehiculos")
@CrossOrigin(origins = "*")
public class VehiculoController {

    private final VehiculoService vehiculoService;

    public VehiculoController(VehiculoService vehiculoService) {
        this.vehiculoService = vehiculoService;
    }

    @GetMapping
    public List<VehiculoResponse> listarVehiculos(@RequestParam(required = false, defaultValue = "true") boolean soloActivos) {
        if (soloActivos) {
            return vehiculoService.obtenerDisponibles().stream().map(VehiculoResponse::from).toList();
        }
        return vehiculoService.obtenerTodos().stream().map(VehiculoResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehiculoResponse> obtenerPorId(@PathVariable Long id) {
        return vehiculoService.buscarPorId(id)
                .map(VehiculoResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<VehiculoResponse> crearVehiculo(@Valid @RequestBody VehiculoRequest vehiculo) {
        return ResponseEntity.status(HttpStatus.CREATED).body(VehiculoResponse.from(vehiculoService.guardar(vehiculo)));
    }

}