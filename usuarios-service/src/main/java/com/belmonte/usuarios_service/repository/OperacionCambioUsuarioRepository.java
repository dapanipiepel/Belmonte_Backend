package com.belmonte.usuarios_service.repository;

import com.belmonte.usuarios_service.model.OperacionCambioUsuario;
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

public interface OperacionCambioUsuarioRepository extends JpaRepository<OperacionCambioUsuario, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OperacionCambioUsuario o where o.clave = :clave")
    Optional<OperacionCambioUsuario> findForUpdate(@Param("clave") String clave);

    List<OperacionCambioUsuario> findByEstadoOrderByClave(String estado, Pageable pageable);
    List<OperacionCambioUsuario> findByEstadoAndClaveGreaterThanOrderByClave(
            String estado, String clave, Pageable pageable);

    @Modifying
    @Query("""
            update OperacionCambioUsuario o set o.estado = 'EXPIRADA', o.actorId = null,
            o.usuarioId = null, o.solicitudHash = null, o.barreraId = null, o.proposito = null,
            o.respuestaCodigo = null, o.respuestaJson = null, o.expiraEn = null,
            o.sesionOriginal = null, o.tipoSolicitud = null, o.solicitudActivo = null, o.solicitudRolId = null,
            o.jwtOriginalExpiraEn = null
            where o.estado = 'COMPLETADA' and o.expiraEn <= :ahora
            """)
    int expirar(@Param("ahora") OffsetDateTime ahora);
}
