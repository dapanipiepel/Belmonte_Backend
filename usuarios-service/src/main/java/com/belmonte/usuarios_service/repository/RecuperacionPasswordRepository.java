package com.belmonte.usuarios_service.repository;

import com.belmonte.usuarios_service.model.RecuperacionPassword;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RecuperacionPasswordRepository extends JpaRepository<RecuperacionPassword, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecuperacionPassword> findByTokenHash(String tokenHash);

    @Query("select r.usuario.idUsuario from RecuperacionPassword r where r.tokenHash = :hash")
    Optional<Long> findUsuarioIdByTokenHash(@Param("hash") String hash);

    @Query("select max(r.idRecuperacion) from RecuperacionPassword r where r.usuario.idUsuario = :usuarioId")
    Optional<Long> findUltimaIdPorUsuario(@Param("usuarioId") Long usuarioId);
}