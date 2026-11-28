
package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.model.TipoDocumento;
import com.belmonte.usuarios_service.repository.TipoDocumentoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class TipoDocumentoService {

    private final TipoDocumentoRepository tipoDocumentoRepository;

    public TipoDocumentoService(TipoDocumentoRepository tipoDocumentoRepository) {
        this.tipoDocumentoRepository = tipoDocumentoRepository;
    }

    public List<TipoDocumento> listarTiposDocumento() {
        return tipoDocumentoRepository.findAll();
    }

    public TipoDocumento crearTipoDocumento(TipoDocumento tipoDocumento) {
        if (tipoDocumento.getIdTipoDocumento() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se permite indicar el ID al crear un tipo de documento");
        }
        return tipoDocumentoRepository.save(tipoDocumento);
    }
}
