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

    @Modifying
    @Transactional
    @Query("UPDATE SesionUsuario s SET s.activa = 0 WHERE s.usuario.idUsuario = :usuarioId AND s.activa = 1")
    int revokeAllByUsuarioId(@Param("usuarioId") Long usuarioId);
}
