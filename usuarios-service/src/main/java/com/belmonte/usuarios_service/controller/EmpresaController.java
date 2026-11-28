package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.dto.EmpresaRequest;
import com.belmonte.usuarios_service.model.Empresa;
import com.belmonte.usuarios_service.service.EmpresaService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/empresas")
public class EmpresaController {
    private final EmpresaService empresas;

    public EmpresaController(EmpresaService empresas) {
        this.empresas = empresas;
    }

    @GetMapping
    public List<EmpresaResumen> listar() {
        return empresas.listar().stream().map(EmpresaResumen::from).toList();
    }

    @GetMapping("/{id}")
    public EmpresaDetalle detalle(@PathVariable Long id) {
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empresa invalida");
        }
        return EmpresaDetalle.from(empresas.buscar(id));
    }

    @PostMapping
    public ResponseEntity<EmpresaDetalle> crear(@Valid @RequestBody EmpresaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(EmpresaDetalle.from(empresas.crear(request)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> solicitudInvalida() {
        return ResponseEntity.badRequest().body(Map.of("error", "Datos de empresa invalidos"));
    }

    public record EmpresaResumen(Long idEmpresa, String rutEmpresa, String razonSocial) {
        static EmpresaResumen from(Empresa empresa) {
            return new EmpresaResumen(empresa.getIdEmpresa(), empresa.getRutEmpresa(), empresa.getRazonSocial());
        }
    }

    public record EmpresaDetalle(Long idEmpresa, String rutEmpresa, String razonSocial, String telefono, String email) {
        static EmpresaDetalle from(Empresa empresa) {
            return new EmpresaDetalle(empresa.getIdEmpresa(), empresa.getRutEmpresa(), empresa.getRazonSocial(),
                    empresa.getTelefono(), empresa.getEmail());
        }
    }
}
