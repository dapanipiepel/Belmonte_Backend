package com.belmonte.vehiculos_service.repository;

import com.belmonte.vehiculos_service.model.MarcaVehiculo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarcaVehiculoRepository extends JpaRepository<MarcaVehiculo, Long> {}
