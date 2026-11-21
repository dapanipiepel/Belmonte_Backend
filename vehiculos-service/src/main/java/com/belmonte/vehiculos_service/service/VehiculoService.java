package com.belmonte.vehiculos_service.service;

import com.belmonte.vehiculos_service.model.Vehiculo;
import com.belmonte.vehiculos_service.model.ModeloVehiculo;
import com.belmonte.vehiculos_service.model.TipoVehiculo;
import com.belmonte.vehiculos_service.dto.VehiculoRequest;
import com.belmonte.vehiculos_service.repository.ModeloVehiculoRepository;
import com.belmonte.vehiculos_service.repository.TipoVehiculoRepository;
import com.belmonte.vehiculos_service.repository.VehiculoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class VehiculoService {

    private final VehiculoRepository vehiculoRepository;
    private final TipoVehiculoRepository tipoRepository;
    private final ModeloVehiculoRepository modeloRepository;

    public VehiculoService(VehiculoRepository vehiculoRepository, TipoVehiculoRepository tipoRepository,
                           ModeloVehiculoRepository modeloRepository) {
        this.vehiculoRepository = vehiculoRepository;
        this.tipoRepository = tipoRepository;
        this.modeloRepository = modeloRepository;
    }

    public List<Vehiculo> obtenerTodos() {
        return vehiculoRepository.findAll();
    }

    public List<Vehiculo> obtenerDisponibles() {
        return vehiculoRepository.findByActivoTrue();
    }

    public Optional<Vehiculo> buscarPorId(Long id) {
        return vehiculoRepository.findById(id);
    }

    @Transactional
    public Vehiculo guardar(VehiculoRequest request) {
        TipoVehiculo tipo = tipoRepository.findById(request.tipoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo de vehiculo no encontrado"));
        ModeloVehiculo modelo = modeloRepository.findById(request.modeloId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Modelo de vehiculo no encontrado"));
        return vehiculoRepository.saveAndFlush(new Vehiculo(tipo, modelo, request.patente(), request.anio(),
                request.color(), request.capacidadPasajeros(), request.activo() == null || request.activo()));
    }

    @Transactional
    public Vehiculo cambiarEstado(Long id, boolean activo) {
        Vehiculo vehiculo = vehiculoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Vehiculo no encontrado"));
        if (!activo) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La desactivacion requiere el protocolo de asignacion y viajes");
        }
        vehiculo.setActivo(activo);
        return vehiculoRepository.saveAndFlush(vehiculo);
    }
}