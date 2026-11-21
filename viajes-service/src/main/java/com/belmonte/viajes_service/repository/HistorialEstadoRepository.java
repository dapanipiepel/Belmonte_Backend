package com.belmonte.viajes_service.repository;

import com.belmonte.viajes_service.model.HistorialEstado;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HistorialEstadoRepository extends JpaRepository<HistorialEstado, Long> {
    List<HistorialEstado> findByTraslado_IdOrderByFechaCambioDescIdDesc(
            Long trasladoId);
}
