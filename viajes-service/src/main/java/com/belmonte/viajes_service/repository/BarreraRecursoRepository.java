package com.belmonte.viajes_service.repository;

import com.belmonte.viajes_service.model.BarreraRecurso;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarreraRecursoRepository extends JpaRepository<BarreraRecurso, String> {
    boolean existsByTipoRecursoAndRecursoIdAndEstado(String tipoRecurso, Long recursoId, String estado);
    boolean existsByTipoRecursoAndRecursoIdAndEstadoAndOperacionIdNot(
            String tipoRecurso, Long recursoId, String estado, String operacionId);
}
