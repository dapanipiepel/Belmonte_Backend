package com.belmonte.viajes_service.repository;

import com.belmonte.viajes_service.model.OperacionTraslado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OperacionTrasladoRepository
        extends JpaRepository<OperacionTraslado, String> {
    Optional<OperacionTraslado> findByClave(String clave);
}
