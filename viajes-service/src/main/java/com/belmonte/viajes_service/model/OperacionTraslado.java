package com.belmonte.viajes_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "operaciones_traslados")
public class OperacionTraslado {

    @Id
    @Column(name = "clave", nullable = false, length = 100)
    private String clave;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "operacion", nullable = false, length = 50)
    private String operacion;

    @Column(name = "traslado_id")
    private Long trasladoId;

    @Column(name = "respuesta_codigo", nullable = false)
    private Integer respuestaCodigo;

    @Column(name = "creado_en", nullable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "estado_operacion", length = 20)
    private String estadoOperacion;

    @Column(name = "solicitud_hash", length = 64)
    private String solicitudHash;

    @Lob
    @Column(name = "respuesta_json")
    private String respuestaJson;

    @Column(name = "expira_en")
    private OffsetDateTime expiraEn;

    @Column(name = "sesion_original", length = 36)
    private String sesionOriginal;
    @Column(name = "solicitud_conductor_id")
    private Long solicitudConductorId;
    @Column(name = "solicitud_vehiculo_id")
    private Long solicitudVehiculoId;
    @Column(name = "jwt_original_expira_en")
    private OffsetDateTime jwtOriginalExpiraEn;

    protected OperacionTraslado() {}

    public OperacionTraslado(String clave, Long actorId, String operacion,
                             Long trasladoId, Integer respuestaCodigo) {
        this.clave = clave;
        this.actorId = actorId;
        this.operacion = operacion;
        this.trasladoId = trasladoId;
        this.respuestaCodigo = respuestaCodigo;
        this.creadoEn = OffsetDateTime.now();
    }

    public String getClave() { return clave; }
    public Long getActorId() { return actorId; }
    public String getOperacion() { return operacion; }
    public Long getTrasladoId() { return trasladoId; }
    public Integer getRespuestaCodigo() { return respuestaCodigo; }
    public String getEstadoOperacion() { return estadoOperacion; }
    public String getSolicitudHash() { return solicitudHash; }
    public String getRespuestaJson() { return respuestaJson; }
    public OffsetDateTime getExpiraEn() { return expiraEn; }

    public void completar(int codigo, String respuesta, OffsetDateTime expiraEn) {
        this.respuestaCodigo = codigo;
        this.respuestaJson = respuesta;
        this.expiraEn = expiraEn;
        this.estadoOperacion = "COMPLETADA";
        borrarIntencion();
    }

    public void expirar() {
        this.actorId = null;
        this.trasladoId = null;
        this.solicitudHash = null;
        this.respuestaJson = null;
        this.estadoOperacion = "EXPIRADA";
        borrarIntencion();
    }

    private void borrarIntencion() {
        this.sesionOriginal = null;
        this.solicitudConductorId = null;
        this.solicitudVehiculoId = null;
        this.jwtOriginalExpiraEn = null;
    }
}
