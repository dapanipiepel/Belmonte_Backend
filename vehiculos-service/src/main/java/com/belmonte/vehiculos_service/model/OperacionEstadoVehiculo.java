package com.belmonte.vehiculos_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "operaciones_estado_vehiculo")
public class OperacionEstadoVehiculo {
    @Id
    @Column(length = 100)
    private String clave;
    @Column(name = "actor_id")
    private Long actorId;
    @Column(name = "vehiculo_id")
    private Long vehiculoId;
    @Column(name = "solicitud_hash", length = 64)
    private String solicitudHash;
    @Column(name = "barrera_id", length = 36)
    private String barreraId;
    @Column(length = 20)
    private String proposito;
    @Column(nullable = false, length = 20)
    private String estado;
    @Column(name = "respuesta_codigo")
    private Integer respuestaCodigo;
    @Lob
    @Column(name = "respuesta_json")
    private String respuestaJson;
    @Column(name = "expira_en")
    private OffsetDateTime expiraEn;
    @Column(name = "sesion_original", length = 36)
    private String sesionOriginal;
    @Column(name = "solicitud_activo")
    private Integer solicitudActivo;
    @Column(name = "jwt_original_expira_en")
    private OffsetDateTime jwtOriginalExpiraEn;

    protected OperacionEstadoVehiculo() {}

    public String getClave() { return clave; }
    public Long getActorId() { return actorId; }
    public Long getVehiculoId() { return vehiculoId; }
    public String getSolicitudHash() { return solicitudHash; }
    public String getBarreraId() { return barreraId; }
    public String getProposito() { return proposito; }
    public String getEstado() { return estado; }
    public Integer getRespuestaCodigo() { return respuestaCodigo; }
    public String getRespuestaJson() { return respuestaJson; }
    public OffsetDateTime getExpiraEn() { return expiraEn; }

    public void aplicar(int codigo, String json) {
        estado = "APLICADA";
        respuestaCodigo = codigo;
        respuestaJson = json;
        borrarIntencion();
    }

    public void completar(OffsetDateTime expira) {
        estado = "COMPLETADA";
        expiraEn = expira;
    }

    public void expirar() {
        estado = "EXPIRADA";
        actorId = null;
        vehiculoId = null;
        solicitudHash = null;
        barreraId = null;
        proposito = null;
        respuestaCodigo = null;
        respuestaJson = null;
        expiraEn = null;
        borrarIntencion();
    }

    private void borrarIntencion() {
        sesionOriginal = null;
        solicitudActivo = null;
        jwtOriginalExpiraEn = null;
    }
}
