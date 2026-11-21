package com.belmonte.viajes_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "operaciones_traslados")
public class OperacionTraslado {

    @Id
    @Column(name = "clave", nullable = false, length = 100)
    private String clave;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "operacion", nullable = false, length = 50)
    private String operacion;

    @Column(name = "traslado_id")
    private Long trasladoId;

    @Column(name = "respuesta_codigo", nullable = false)
    private Integer respuestaCodigo;

    @Column(name = "creado_en", nullable = false)
    private OffsetDateTime creadoEn;

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
}
