package com.belmonte.vehiculos_service.controller;

import com.belmonte.vehiculos_service.dto.VehiculoRequest;
import com.belmonte.vehiculos_service.dto.VehiculoResponse;
import com.belmonte.vehiculos_service.dto.VehiculoEstadoRequest;
import com.belmonte.vehiculos_service.service.VehiculoService;
import com.belmonte.vehiculos_service.service.EstadoVehiculoIdempotenteService;
import com.belmonte.vehiculos_service.service.EstadoVehiculoTransacciones;
import com.belmonte.vehiculos_service.security.IdentidadSesion;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/vehiculos")
@CrossOrigin(origins = "*")
public class VehiculoController {

    private final VehiculoService vehiculoService;
    private final EstadoVehiculoIdempotenteService estados;

    public VehiculoController(VehiculoService vehiculoService, EstadoVehiculoIdempotenteService estados) {
        this.vehiculoService = vehiculoService;
        this.estados = estados;
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

    @PatchMapping("/{id}/estado")
    public ResponseEntity<String> cambiarEstado(
            @PathVariable Long id,
            @Valid @RequestBody VehiculoEstadoRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String clave) {
        var actor = identidad();
        var resultado = estados.cambiar(clave, actor.usuarioId(), actor.sesionId(), id, request.activo(), actor.jwtExpiraEn());
        return ResponseEntity.status(resultado.codigo()).contentType(MediaType.APPLICATION_JSON).body(resultado.cuerpo());
    }

    @GetMapping("/operaciones/estado")
    public EstadoVehiculoTransacciones.Consulta consultarOperacion(@RequestParam String clave) {
        return estados.consultar(clave, identidad().usuarioId());
    }

    private IdentidadSesion identidad() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof IdentidadSesion identidad)
                || identidad.usuarioId() == null || identidad.usuarioId() <= 0
                || identidad.sesionId() == null || identidad.sesionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identidad de usuario invalida");
        }
        return identidad;
    }

}