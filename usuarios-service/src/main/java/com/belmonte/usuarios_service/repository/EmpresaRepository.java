package com.belmonte.usuarios_service.repository;

import com.belmonte.usuarios_service.model.Empresa;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmpresaRepository extends JpaRepository<Empresa, Long> {
    List<Empresa> findAllByOrderByRazonSocialAscIdEmpresaAsc();
}
