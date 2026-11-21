package com.belmonte.viajes_service.repository;

import com.belmonte.viajes_service.model.RecursoTraslado;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecursoTrasladoRepository
        extends JpaRepository<RecursoTraslado, RecursoTraslado.RecursoId> {}
