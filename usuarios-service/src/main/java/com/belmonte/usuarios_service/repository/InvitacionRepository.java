package com.belmonte.usuarios_service.repository;

import com.belmonte.usuarios_service.model.Invitacion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InvitacionRepository extends JpaRepository<Invitacion, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invitacion> findByTokenHash(String tokenHash);

    @Query("select i.persona.idPersona from Invitacion i where i.tokenHash = :hash")
    Optional<Long> findPersonaIdByTokenHash(@Param("hash") String hash);

    @Query("select max(i.idInvitacion) from Invitacion i where i.persona.idPersona = :id")
    Optional<Long> findUltimaIdPorPersona(@Param("id") Long id);

    @Query("select max(i.idInvitacion) from Invitacion i where i.emailDestinatario = :email")
    Optional<Long> findUltimaIdPorEmail(@Param("email") String email);
}