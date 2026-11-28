package com.belmonte.usuarios_service.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ComunaService {
    private final JdbcTemplate jdbc;

    public ComunaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Comuna> listar() {
        return jdbc.query("""
                SELECT c.id_comuna, c.nombre_comuna, r.id_region, r.nombre_region, r.pais_id
                FROM comunas c JOIN regiones r ON r.id_region = c.region_id
                ORDER BY r.nombre_region, c.nombre_comuna, c.id_comuna
                """, (rs, row) -> mapear(rs));
    }

    public Optional<Comuna> buscar(Long id) {
        return jdbc.query("""
                SELECT c.id_comuna, c.nombre_comuna, r.id_region, r.nombre_region, r.pais_id
                FROM comunas c JOIN regiones r ON r.id_region = c.region_id
                WHERE c.id_comuna = ?
                """, (rs, row) -> mapear(rs), id).stream().findFirst();
    }

    private Comuna mapear(ResultSet rs) throws SQLException {
        return new Comuna(rs.getLong("id_comuna"), rs.getString("nombre_comuna"),
                rs.getLong("id_region"), rs.getString("nombre_region"), rs.getLong("pais_id"));
    }

    public record Comuna(Long idComuna, String nombreComuna, Long idRegion, String nombreRegion, Long idPais) {}
}
