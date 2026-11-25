package com.belmonte.usuarios_service.service;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RelojBaseDatos {
    private final JdbcTemplate jdbc;

    public RelojBaseDatos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OffsetDateTime ahora() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP FROM DUAL", OffsetDateTime.class));
    }
}
