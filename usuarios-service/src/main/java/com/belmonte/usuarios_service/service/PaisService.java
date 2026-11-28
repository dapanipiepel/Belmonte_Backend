
package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.model.Pais;
import com.belmonte.usuarios_service.repository.PaisRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class PaisService {

    private final PaisRepository paisRepository;

    public PaisService(PaisRepository paisRepository) {
        this.paisRepository = paisRepository;
    }

    public List<Pais> listarPaises() {
        return paisRepository.findAll();
    }

    public Pais crearPais(Pais pais) {
        if (pais.getIdPais() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se permite indicar el ID al crear un pais");
        }
        return paisRepository.save(pais);
    }
}
