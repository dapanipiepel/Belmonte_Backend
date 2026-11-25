package com.belmonte.vehiculos_service.repository;

import com.belmonte.vehiculos_service.model.OperacionEstadoVehiculo;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperacionEstadoVehiculoRepository extends JpaRepository<OperacionEstadoVehiculo, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OperacionEstadoVehiculo o where o.clave = :clave")
    Optional<OperacionEstadoVehiculo> findForUpdate(@Param("clave") String clave);

    List<OperacionEstadoVehiculo> findByEstadoOrderByClave(String estado, Pageable pageable);
    List<OperacionEstadoVehiculo> findByEstadoAndClaveGreaterThanOrderByClave(
            String estado, String clave, Pageable pageable);

    @Modifying
    @Query("""
            update OperacionEstadoVehiculo o set o.estado = 'EXPIRADA', o.actorId = null,
            o.vehiculoId = null, o.solicitudHash = null, o.barreraId = null, o.proposito = null,
            o.respuestaCodigo = null, o.respuestaJson = null, o.expiraEn = null,
            o.sesionOriginal = null, o.solicitudActivo = null, o.jwtOriginalExpiraEn = null
            where o.estado = 'COMPLETADA' and o.expiraEn <= :ahora
            """)
    int expirar(@Param("ahora") OffsetDateTime ahora);
}
