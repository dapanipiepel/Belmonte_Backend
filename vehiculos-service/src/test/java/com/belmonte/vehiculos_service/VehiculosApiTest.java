package com.belmonte.vehiculos_service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.belmonte.vehiculos_service.repository.MarcaVehiculoRepository;
import com.belmonte.vehiculos_service.repository.ModeloVehiculoRepository;
import com.belmonte.vehiculos_service.repository.TipoVehiculoRepository;
import com.belmonte.vehiculos_service.repository.VehiculoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class VehiculosApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private TipoVehiculoRepository tipos;
    @Autowired private MarcaVehiculoRepository marcas;
    @Autowired private ModeloVehiculoRepository modelos;
    @Autowired private VehiculoRepository vehiculos;

    @Test
    void createsCatalogsAndVehiclesAndValidatesRequests() throws Exception {
        mvc.perform(post("/api/vehiculos/catalogos/tipos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"TEST\",\"descripcion\":\"Tipo test\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/vehiculos/catalogos/marcas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Marca test\"}"))
                .andExpect(status().isCreated());
        Long tipoId = tipos.findAll().stream().filter(t -> t.getCodigo().equals("TEST")).findFirst().orElseThrow().getId();
        Long marcaId = marcas.findAll().stream().filter(m -> m.getNombre().equals("Marca test")).findFirst().orElseThrow().getId();
        mvc.perform(post("/api/vehiculos/catalogos/modelos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"marcaId\":%d,\"nombre\":\"Modelo test\"}".formatted(marcaId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.marcaId").value(marcaId));
        mvc.perform(get("/api/vehiculos/catalogos/tipos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.codigo == 'TEST')]").exists());
        mvc.perform(get("/api/vehiculos/catalogos/marcas"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.nombre == 'Marca test')]").exists());
        mvc.perform(get("/api/vehiculos/catalogos/modelos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.nombre == 'Modelo test')]").exists());
        mvc.perform(post("/api/vehiculos/catalogos/modelos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"marcaId\":999999,\"nombre\":\"Modelo invalido\"}"))
                .andExpect(status().isNotFound());
        Long modeloId = modelos.findAll().stream().filter(m -> m.getNombre().equals("Modelo test")).findFirst().orElseThrow().getId();
        String request = """
                {"tipoId":%d,"modeloId":%d,"patente":"ZZZZ-99","anio":2025,
                 "color":"Azul","capacidadPasajeros":4,"activo":false}
                """.formatted(tipoId, modeloId);
        mvc.perform(post("/api/vehiculos").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.marca").value("Marca test"))
                .andExpect(jsonPath("$.modeloId").value(modeloId))
                .andExpect(jsonPath("$.activo").value(false));
        mvc.perform(post("/api/vehiculos").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/vehiculos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipoId\":%d,\"modeloId\":%d,\"patente\":\"ZZZZ-98\",\"anio\":2025,\"capacidadPasajeros\":4}"
                        .formatted(tipoId, modeloId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.activo").value(true));
        mvc.perform(post("/api/vehiculos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipoId\":1,\"modeloId\":1,\"patente\":\"BAD\",\"anio\":2025,\"capacidadPasajeros\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/vehiculos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipoId\":999999,\"modeloId\":1,\"patente\":\"NONE\",\"anio\":2025,\"capacidadPasajeros\":4}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/vehiculos").param("soloActivos", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.patente == 'ZZZZ-99')]").exists());
        mvc.perform(get("/api/vehiculos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.patente == 'ZZZZ-99')]").doesNotExist());
        Long vehiculoId = vehiculoServiceId();
        mvc.perform(patch("/api/vehiculos/" + vehiculoId + "/estado")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true));
    }

    private Long vehiculoServiceId() {
        return vehiculos.findAll().stream()
                .filter(vehiculo -> vehiculo.getPatente().equals("ZZZZ-99"))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
