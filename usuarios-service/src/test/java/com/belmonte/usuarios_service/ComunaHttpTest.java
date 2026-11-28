package com.belmonte.usuarios_service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "jwt.secret=test-jwt-secret-not-for-deployment",
        "internal.api.secret=test-internal-secret",
        "spring.datasource.url=jdbc:h2:mem:comunas;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ComunaHttpTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void prepararCatalogo() {
        jdbc.update("DELETE FROM comunas");
        jdbc.update("DELETE FROM regiones");
    }

    @Test
    void listaComunasConRegionYPaisParaSesionAutenticada() throws Exception {
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES ('Pais catalogo')");
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = 'Pais catalogo'", Long.class);
        jdbc.update("INSERT INTO regiones (pais_id, nombre_region) VALUES (?, 'Region B'), (?, 'Region A')", pais, pais);
        Long regionA = jdbc.queryForObject("SELECT id_region FROM regiones WHERE nombre_region = 'Region A'", Long.class);
        Long regionB = jdbc.queryForObject("SELECT id_region FROM regiones WHERE nombre_region = 'Region B'", Long.class);
        jdbc.update("INSERT INTO comunas (region_id, nombre_comuna) VALUES (?, 'Comuna B'), (?, 'Comuna A')", regionB, regionA);
        Long comunaA = jdbc.queryForObject("SELECT id_comuna FROM comunas WHERE nombre_comuna = 'Comuna A'", Long.class);
        Long comunaB = jdbc.queryForObject("SELECT id_comuna FROM comunas WHERE nombre_comuna = 'Comuna B'", Long.class);
        String token = iniciarSesion();

        mvc.perform(get("/api/comunas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/comunas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].idComuna").value(comunaA))
                .andExpect(jsonPath("$[0].nombreComuna").value("Comuna A"))
                .andExpect(jsonPath("$[0].idRegion").value(regionA))
                .andExpect(jsonPath("$[0].nombreRegion").value("Region A"))
                .andExpect(jsonPath("$[0].idPais").value(pais))
                .andExpect(jsonPath("$[1].idComuna").value(comunaB));
        mvc.perform(get("/internal/comunas/{id}", comunaA)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/comunas/{id}", comunaA).header("X-Belmonte-Internal-Secret", "incorrecta"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/comunas/{id}", comunaA).header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idComuna").value(comunaA))
                .andExpect(jsonPath("$.idRegion").value(regionA));
        mvc.perform(get("/internal/comunas/999999").header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/comunas/0").header("X-Belmonte-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/comunas").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void catalogoVacioDevuelveListaVacia() throws Exception {
        String token = iniciarSesion();
        mvc.perform(get("/api/comunas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    private String iniciarSesion() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO paises (nombre_pais) VALUES (?)", "Pais " + suffix);
        jdbc.update("INSERT INTO tipos_documento (codigo_tipo) VALUES (?)", suffix);
        jdbc.update("INSERT INTO roles (nombre_rol) VALUES (?)", "ROL_" + suffix);
        Long pais = jdbc.queryForObject("SELECT id_pais FROM paises WHERE nombre_pais = ?", Long.class, "Pais " + suffix);
        Long tipo = jdbc.queryForObject("SELECT id_tipo_documento FROM tipos_documento WHERE codigo_tipo = ?", Long.class, suffix);
        Long rol = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre_rol = ?", Long.class, "ROL_" + suffix);
        String email = suffix + "@example.invalid";
        jdbc.update("INSERT INTO personas (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, fecha_creacion) "
                + "VALUES (?, ?, ?, 'Prueba', ?, CURRENT_TIMESTAMP)", pais, tipo, suffix, email);
        Long persona = jdbc.queryForObject("SELECT id_persona FROM personas WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO usuarios (persona_id, rol_id, password, activo) VALUES (?, ?, ?, 1)",
                persona, rol, new BCryptPasswordEncoder().encode("password"));
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asString();
    }
}
