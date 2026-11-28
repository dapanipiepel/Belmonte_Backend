package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.dto.EmpresaRequest;
import com.belmonte.usuarios_service.model.Empresa;
import com.belmonte.usuarios_service.repository.EmpresaRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmpresaService {
    private final EmpresaRepository empresas;
    private final JdbcTemplate jdbc;

    public EmpresaService(EmpresaRepository empresas, JdbcTemplate jdbc) {
        this.empresas = empresas;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Empresa> listar() {
        return empresas.findAllByOrderByRazonSocialAscIdEmpresaAsc();
    }

    @Transactional(readOnly = true)
    public Empresa buscar(Long id) {
        return empresas.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada"));
    }

    @Transactional
    public Empresa crear(EmpresaRequest request) {
        try {
            var clave = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO empresas (rut_empresa, razon_social, telefono, email)
                        VALUES (?, ?, ?, ?)
                        """, new String[] {"ID_EMPRESA"});
                statement.setString(1, request.rutEmpresa().trim());
                statement.setString(2, request.razonSocial().trim());
                statement.setString(3, opcional(request.telefono()));
                statement.setString(4, opcional(request.email()));
                return statement;
            }, clave);
            Number id = clave.getKey();
            if (id == null) {
                throw new IllegalStateException("No se obtuvo el identificador de la empresa creada");
            }
            return empresas.findById(id.longValue()).orElseThrow(() ->
                    new IllegalStateException("No se encontro la empresa creada"));
        } catch (DataIntegrityViolationException | UncategorizedSQLException exception) {
            if (esRutDuplicado(exception)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "RUT de empresa ya registrado");
            }
            if (excedeLongitud(exception)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo de empresa demasiado largo para la base de datos");
            }
            throw exception;
        }
    }

    private String opcional(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    private boolean esRutDuplicado(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql
                    && (sql.getErrorCode() == 1 || "23505".equals(sql.getSQLState()))
                    && sql.getMessage() != null
                    && sql.getMessage().toUpperCase(Locale.ROOT).contains("UQ_EMPRESAS_RUT")) {
                return true;
            }
        }
        return false;
    }

    private boolean excedeLongitud(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql
                    && (sql.getErrorCode() == 12899 || "22001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
