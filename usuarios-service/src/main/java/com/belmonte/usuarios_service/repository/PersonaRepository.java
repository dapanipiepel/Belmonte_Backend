
package com.belmonte.usuarios_service.repository;

import com.belmonte.usuarios_service.model.Persona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PersonaRepository extends JpaRepository<Persona, Long> {

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Persona p where p.idPersona = :id")
    Optional<Persona> findForUpdate(@Param("id") Long id);
}
