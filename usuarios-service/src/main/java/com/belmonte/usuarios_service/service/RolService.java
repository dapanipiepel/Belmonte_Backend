
package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.model.Rol;
import com.belmonte.usuarios_service.repository.RolRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class RolService {

    private final RolRepository rolRepository;

    public RolService(RolRepository rolRepository) {
        this.rolRepository = rolRepository;
    }

    public List<Rol> listarRoles() {
        return rolRepository.findAll();
    }

    public Rol crearRol(Rol rol) {
        if (rol.getIdRol() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se permite indicar el ID al crear un rol");
        }
        return rolRepository.save(rol);
    }
}
