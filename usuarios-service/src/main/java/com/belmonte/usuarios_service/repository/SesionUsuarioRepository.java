package com.belmonte.usuarios_service.repository;

import com.belmonte.usuarios_service.model.SesionUsuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface SesionUsuarioRepository extends JpaRepository<SesionUsuario, String> {
    Optional<SesionUsuario> findBySesionIdAndActiva(String sesionId, Integer activa);

    @Query("select s.usuario.idUsuario from SesionUsuario s where s.sesionId = :sesionId")
    Optional<Long> findUsuarioIdBySesionId(@Param("sesionId") String sesionId);

    @Modifying
    @Query("UPDATE SesionUsuario s SET s.activa = 0 WHERE s.sesionId = :sesionId AND s.usuario.idUsuario = :usuarioId")
    int revokeBySesionIdAndUsuarioId(@Param("sesionId") String sesionId, @Param("usuarioId") Long usuarioId);

    @Modifying
    @Transactional
    @Query("UPDATE SesionUsuario s SET s.activa = 0 WHERE s.usuario.idUsuario = :usuarioId AND s.activa = 1")
    int revokeAllByUsuarioId(@Param("usuarioId") Long usuarioId);
}
