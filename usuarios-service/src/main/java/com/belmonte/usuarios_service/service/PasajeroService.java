package com.belmonte.usuarios_service.service;

import com.belmonte.usuarios_service.dto.BusquedaPasajeroRequest;
import com.belmonte.usuarios_service.dto.PasajeroRequest;
import com.belmonte.usuarios_service.security.IdentidadSesion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasajeroService {
    private final JdbcTemplate jdbc;
    private final RelojBaseDatos reloj;

    public PasajeroService(JdbcTemplate jdbc, RelojBaseDatos reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    @Transactional
    public Pasajero crear(IdentidadSesion actor, PasajeroRequest request) {
        bloquearYValidar(actor);
        String nombre = request.nombreCompleto().trim();
        String documento = request.numeroDocumento().trim();
        String email = request.email().trim();
        String telefono = request.telefono().trim();
        if (!existe("paises", "id_pais", request.paisDocumentoId())
                || !existe("tipos_documento", "id_tipo_documento", request.tipoDocumentoId())
                || !existe("empresas", "id_empresa", request.empresaId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Referencia de pasajero no encontrada");
        }
        try {
            List<PersonaExistente> personas = jdbc.query("""
                    SELECT id_persona, pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, telefono
                    FROM personas WHERE (pais_documento_id = ? AND tipo_documento_id = ? AND rut_dni = ?) OR email = ?
                    """, (rs, row) -> new PersonaExistente(rs.getLong("id_persona"), rs.getLong("pais_documento_id"),
                    rs.getLong("tipo_documento_id"), rs.getString("rut_dni"), rs.getString("nombre_completo"),
                    rs.getString("email"), rs.getString("telefono")),
                    request.paisDocumentoId(), request.tipoDocumentoId(), documento, email);
            long personaId;
            if (personas.isEmpty()) {
                var clave = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO personas
                            (pais_documento_id, tipo_documento_id, rut_dni, nombre_completo, email, telefono, fecha_creacion)
                            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """, new String[]{"ID_PERSONA"});
                    statement.setLong(1, request.paisDocumentoId());
                    statement.setLong(2, request.tipoDocumentoId());
                    statement.setString(3, documento);
                    statement.setString(4, nombre);
                    statement.setString(5, email);
                    statement.setString(6, telefono);
                    return statement;
                }, clave);
                personaId = claveGenerada(clave);
            } else {
                if (personas.size() != 1) {
                    throw conflicto();
                }
                PersonaExistente persona = personas.get(0);
                if (persona.pais() != request.paisDocumentoId()
                        || persona.tipo() != request.tipoDocumentoId()
                        || !persona.documento().equals(documento) || !persona.nombre().equals(nombre)
                        || !persona.email().equals(email) || !Objects.equals(persona.telefono(), telefono)) {
                    throw conflicto();
                }
                personaId = persona.id();
            }
            var clave = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "INSERT INTO pasajeros (persona_id, empresa_id) VALUES (?, ?)", new String[]{"ID_PASAJERO"});
                statement.setLong(1, personaId);
                statement.setLong(2, request.empresaId());
                return statement;
            }, clave);
            long id = claveGenerada(clave);
            return consultarId(id);
        } catch (DataIntegrityViolationException | UncategorizedSQLException exception) {
            if (esErrorSql(exception, 1, "23505")) {
                throw conflicto();
            }
            if (esErrorSql(exception, 12899, "22001")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo de pasajero demasiado largo para la base de datos");
            }
            throw exception;
        }
    }

    @Transactional
    public Busqueda buscar(IdentidadSesion identidad, BusquedaPasajeroRequest request) {
        OffsetDateTime ahora = bloquearYValidar(identidad);
        Long actor = identidad.usuarioId();
        Integer consultadas = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auditoria_busqueda_pasajeros
                WHERE actor_id = ? AND fecha_evento > ? AND resultado <> 'LIMITADA'
                """, Integer.class, actor, ahora.minusMinutes(1));
        if (consultadas == null) {
            throw new IllegalStateException("No se pudo contar las busquedas");
        }
        if (consultadas >= 30) {
            auditar(actor, ahora, "LIMITADA", 0);
            return new Busqueda(true, List.of(), request.pagina(), false);
        }
        String campo = switch (request.campo()) {
            case NOMBRE -> "LOWER(p.nombre_completo) LIKE LOWER(?) ESCAPE '!'";
            case DOCUMENTO -> "LOWER(p.rut_dni) LIKE LOWER(?) ESCAPE '!'";
            case CORREO -> "LOWER(p.email) LIKE LOWER(?) ESCAPE '!'";
            case EMPRESA -> "(LOWER(e.razon_social) LIKE LOWER(?) ESCAPE '!' OR LOWER(e.rut_empresa) LIKE LOWER(?) ESCAPE '!')";
        };
        String texto = "%" + request.texto().trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long offset = (long) request.pagina() * request.tamano();
        String sql = CONSULTA + " WHERE " + campo
                + " ORDER BY p.nombre_completo, pa.id_pasajero OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        Object[] parametros = request.campo() == BusquedaPasajeroRequest.Campo.EMPRESA
                ? new Object[]{texto, texto, offset, request.tamano() + 1}
                : new Object[]{texto, offset, request.tamano() + 1};
        List<Pasajero> encontrados = jdbc.query(sql, (rs, row) -> mapear(rs), parametros);
        boolean hayMas = encontrados.size() > request.tamano();
        List<Pasajero> pagina = hayMas ? List.copyOf(encontrados.subList(0, request.tamano())) : encontrados;
        auditar(actor, ahora, pagina.isEmpty() ? "SIN_RESULTADOS" : "RESULTADOS", pagina.size());
        return new Busqueda(false, pagina, request.pagina(), hayMas);
    }

    private static final String CONSULTA = """
            SELECT pa.id_pasajero, p.nombre_completo, p.pais_documento_id, p.tipo_documento_id,
                   p.rut_dni, p.email, p.telefono, e.id_empresa, e.razon_social
            FROM pasajeros pa JOIN personas p ON p.id_persona = pa.persona_id
            JOIN empresas e ON e.id_empresa = pa.empresa_id
            """;

    private Pasajero consultarId(long id) {
        return jdbc.query(CONSULTA + " WHERE pa.id_pasajero = ?", (rs, row) -> mapear(rs), id)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("No se encontro el pasajero creado"));
    }

    private Pasajero mapear(ResultSet rs) throws SQLException {
        return new Pasajero(rs.getLong("id_pasajero"), rs.getString("nombre_completo"),
                rs.getLong("pais_documento_id"), rs.getLong("tipo_documento_id"), rs.getString("rut_dni"),
                rs.getString("email"), rs.getString("telefono"), rs.getLong("id_empresa"), rs.getString("razon_social"));
    }

    private void auditar(Long actor, OffsetDateTime fecha, String resultado, int cantidad) {
        jdbc.update("INSERT INTO auditoria_busqueda_pasajeros (actor_id, fecha_evento, resultado, cantidad) VALUES (?, ?, ?, ?)",
                actor, fecha, resultado, cantidad);
    }

    private OffsetDateTime bloquearYValidar(IdentidadSesion identidad) {
        if (identidad == null || identidad.usuarioId() == null || identidad.usuarioId() <= 0
                || identidad.sesionId() == null || identidad.sesionId().isBlank()
                || identidad.jwtExpiraEn() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        List<Long> bloqueados = jdbc.queryForList(
                "SELECT id_usuario FROM usuarios WHERE id_usuario = ? FOR UPDATE", Long.class, identidad.usuarioId());
        if (bloqueados.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        OffsetDateTime ahora = reloj.ahora();
        Integer validas = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sesiones_usuario s
                JOIN usuarios u ON u.id_usuario = s.usuario_id
                JOIN roles r ON r.id_rol = u.rol_id
                WHERE s.sesion_id = ? AND s.usuario_id = ? AND s.activa = 1
                  AND s.fecha_expiracion_instante > ? AND u.activo = 1
                  AND r.nombre_rol IN ('ADMINISTRADOR', 'SOLICITANTE')
                """, Integer.class, identidad.sesionId(), identidad.usuarioId(), ahora);
        if (!identidad.jwtExpiraEn().isAfter(ahora) || validas == null || validas != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        return ahora;
    }

    private boolean existe(String tabla, String columna, Long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabla + " WHERE " + columna + " = ?",
                Integer.class, id);
        return count != null && count == 1;
    }

    private long claveGenerada(GeneratedKeyHolder holder) {
        Number id = holder.getKey();
        if (id == null) {
            throw new IllegalStateException("No se obtuvo el identificador generado");
        }
        return id.longValue();
    }

    private boolean esErrorSql(Throwable error, int codigo, String estado) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql
                    && (sql.getErrorCode() == codigo || estado.equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private ResponseStatusException conflicto() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Persona o pasajero ya registrado o con datos diferentes");
    }

    private record PersonaExistente(long id, long pais, long tipo, String documento, String nombre,
                                   String email, String telefono) {}
    public record Pasajero(long idPasajero, String nombreCompleto, long paisDocumentoId, long tipoDocumentoId,
                           String numeroDocumento, String email, String telefono, long empresaId, String empresa) {}
    public record Busqueda(boolean limitada, List<Pasajero> pasajeros, int pagina, boolean hayMas) {}
}
