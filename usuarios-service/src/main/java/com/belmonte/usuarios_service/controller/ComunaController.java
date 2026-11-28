package com.belmonte.usuarios_service.controller;

import com.belmonte.usuarios_service.service.ComunaService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/comunas")
public class ComunaController {
    private final ComunaService comunas;

    public ComunaController(ComunaService comunas) {
        this.comunas = comunas;
    }

    @GetMapping
    public List<ComunaService.Comuna> listar() {
        return comunas.listar();
    }
}
