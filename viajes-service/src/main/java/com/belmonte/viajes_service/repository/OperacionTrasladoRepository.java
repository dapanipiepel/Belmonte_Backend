package com.belmonte.viajes_service.repository;

import com.belmonte.viajes_service.model.OperacionTraslado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface OperacionTrasladoRepository
        extends JpaRepository<OperacionTraslado, String> {
    Optional<OperacionTraslado> findByClave(String clave);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OperacionTraslado o where o.clave = :clave")
    Optional<OperacionTraslado> findForUpdate(@Param("clave") String clave);

    @Modifying
    @Query("""
            update OperacionTraslado o set o.actorId = null, o.trasladoId = null,
            o.solicitudHash = null, o.respuestaJson = null, o.estadoOperacion = 'EXPIRADA',
            o.sesionOriginal = null, o.solicitudConductorId = null, o.solicitudVehiculoId = null,
            o.jwtOriginalExpiraEn = null
            where o.estadoOperacion = 'COMPLETADA' and o.expiraEn <= :ahora
            """)
    int expirarRespuestas(@Param("ahora") OffsetDateTime ahora);
}
