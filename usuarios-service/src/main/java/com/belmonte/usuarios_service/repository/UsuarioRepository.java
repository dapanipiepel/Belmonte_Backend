
package com.belmonte.usuarios_service.repository;

import com.belmonte.usuarios_service.model.Usuario;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    boolean existsByPersona_Email(String email);
    boolean existsByPersona_IdPersona(Long idPersona);
    @Query("select u.idUsuario from Usuario u where u.persona.email = :email")
    Optional<Long> findIdByEmail(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.idUsuario = :id")
    Optional<Usuario> findForUpdate(@Param("id") Long id);
}
