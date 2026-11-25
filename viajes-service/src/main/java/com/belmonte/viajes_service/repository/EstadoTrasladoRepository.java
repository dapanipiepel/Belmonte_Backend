package com.belmonte.viajes_service.repository;

import java.util.Optional;

import com.belmonte.viajes_service.model.EstadoTraslado;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EstadoTrasladoRepository extends JpaRepository<EstadoTraslado, Long> {
    Optional<EstadoTraslado> findByCodigo(String codigo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EstadoTraslado e where e.codigo = 'PENDIENTE'")
    Optional<EstadoTraslado> lockOperaciones();
}
