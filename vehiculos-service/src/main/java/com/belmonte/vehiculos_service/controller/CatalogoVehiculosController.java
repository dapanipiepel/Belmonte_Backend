package com.belmonte.vehiculos_service.controller;

import java.util.List;

import com.belmonte.vehiculos_service.model.MarcaVehiculo;
import com.belmonte.vehiculos_service.model.ModeloVehiculo;
import com.belmonte.vehiculos_service.model.TipoVehiculo;
import com.belmonte.vehiculos_service.repository.MarcaVehiculoRepository;
import com.belmonte.vehiculos_service.repository.ModeloVehiculoRepository;
import com.belmonte.vehiculos_service.repository.TipoVehiculoRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/vehiculos/catalogos")
public class CatalogoVehiculosController {
    private final TipoVehiculoRepository tipos;
    private final MarcaVehiculoRepository marcas;
    private final ModeloVehiculoRepository modelos;

    public CatalogoVehiculosController(TipoVehiculoRepository tipos, MarcaVehiculoRepository marcas,
                                       ModeloVehiculoRepository modelos) {
        this.tipos = tipos;
        this.marcas = marcas;
        this.modelos = modelos;
    }

    public record TipoRequest(@NotBlank @Size(max = 20) String codigo,
                              @NotBlank @Size(max = 100) String descripcion) {}
    public record TipoResponse(Long id, String codigo, String descripcion) {
        static TipoResponse from(TipoVehiculo tipo) {
            return new TipoResponse(tipo.getId(), tipo.getCodigo(), tipo.getDescripcion());
        }
    }
    public record MarcaRequest(@NotBlank @Size(max = 50) String nombre) {}
    public record MarcaResponse(Long id, String nombre) {
        static MarcaResponse from(MarcaVehiculo marca) { return new MarcaResponse(marca.getId(), marca.getNombre()); }
    }
    public record ModeloRequest(@NotNull @Positive Long marcaId, @NotBlank @Size(max = 50) String nombre) {}
    public record ModeloResponse(Long id, Long marcaId, String nombre) {
        static ModeloResponse from(ModeloVehiculo modelo) {
            return new ModeloResponse(modelo.getId(), modelo.getMarca().getId(), modelo.getNombre());
        }
    }

    @GetMapping("/tipos")
    public List<TipoResponse> listarTipos() {
        return tipos.findAll().stream().map(TipoResponse::from).toList();
    }

    @PostMapping("/tipos")
    @ResponseStatus(HttpStatus.CREATED)
    public TipoResponse crearTipo(@Valid @RequestBody TipoRequest request) {
        return TipoResponse.from(tipos.saveAndFlush(new TipoVehiculo(request.codigo(), request.descripcion())));
    }

    @GetMapping("/marcas")
    public List<MarcaResponse> listarMarcas() {
        return marcas.findAll().stream().map(MarcaResponse::from).toList();
    }

    @PostMapping("/marcas")
    @ResponseStatus(HttpStatus.CREATED)
    public MarcaResponse crearMarca(@Valid @RequestBody MarcaRequest request) {
        return MarcaResponse.from(marcas.saveAndFlush(new MarcaVehiculo(request.nombre())));
    }

    @GetMapping("/modelos")
    public List<ModeloResponse> listarModelos() {
        return modelos.findAll().stream().map(ModeloResponse::from).toList();
    }

    @PostMapping("/modelos")
    @ResponseStatus(HttpStatus.CREATED)
    public ModeloResponse crearModelo(@Valid @RequestBody ModeloRequest request) {
        MarcaVehiculo marca = marcas.findById(request.marcaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Marca no encontrada"));
        return ModeloResponse.from(modelos.saveAndFlush(new ModeloVehiculo(marca, request.nombre())));
    }
}
