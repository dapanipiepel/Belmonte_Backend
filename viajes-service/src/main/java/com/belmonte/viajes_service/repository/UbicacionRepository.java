package com.belmonte.viajes_service.repository;

import com.belmonte.viajes_service.model.Ubicacion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UbicacionRepository extends JpaRepository<Ubicacion, Long> {}
