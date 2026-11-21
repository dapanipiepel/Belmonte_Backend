package com.belmonte.viajes_service.repository;

import java.util.Optional;

import com.belmonte.viajes_service.model.EstadoTraslado;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstadoTrasladoRepository extends JpaRepository<EstadoTraslado, Long> {
    Optional<EstadoTraslado> findByCodigo(String codigo);
}
