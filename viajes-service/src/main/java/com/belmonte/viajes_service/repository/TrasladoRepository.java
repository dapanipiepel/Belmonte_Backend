package com.belmonte.viajes_service.repository;

import com.belmonte.viajes_service.model.Traslado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface TrasladoRepository extends JpaRepository<Traslado, Long> {
    List<Traslado> findBySolicitanteIdOrderByFechaHoraViajeDesc(Long solicitanteId);
    List<Traslado> findByConductorIdOrderByFechaHoraViajeDesc(Long conductorId);
    List<Traslado> findByConductorId(Long conductorId);
    List<Traslado> findByVehiculoId(Long vehiculoId);

    @Query("""
            SELECT t FROM Traslado t
            WHERE t.conductorId = :conductorId
              AND t.fechaHoraViaje < :termino
              AND t.fechaHoraTerminoEstimado > :inicio
            """)
    List<Traslado> conductorSolapado(
            @Param("conductorId") Long conductorId,
            @Param("inicio") OffsetDateTime inicio,
            @Param("termino") OffsetDateTime termino);

    @Query("""
            SELECT t FROM Traslado t
            WHERE t.vehiculoId = :vehiculoId
              AND t.fechaHoraViaje < :termino
              AND t.fechaHoraTerminoEstimado > :inicio
            """)
    List<Traslado> vehiculoSolapado(
            @Param("vehiculoId") Long vehiculoId,
            @Param("inicio") OffsetDateTime inicio,
            @Param("termino") OffsetDateTime termino);
}
