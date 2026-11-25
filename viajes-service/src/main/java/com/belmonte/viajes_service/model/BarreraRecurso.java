package com.belmonte.viajes_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "barreras_recurso")
public class BarreraRecurso {
    @Id
    @Column(name = "operacion_id", nullable = false, length = 36)
    private String operacionId;

    @Column(name = "tipo_recurso", nullable = false, length = 20)
    private String tipoRecurso;

    @Column(name = "recurso_id", nullable = false)
    private Long recursoId;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado;

    @Column(name = "proposito", nullable = false, length = 20)
    private String proposito;

    protected BarreraRecurso() {}

    public BarreraRecurso(String operacionId, String tipoRecurso, Long recursoId, String proposito) {
        this.operacionId = operacionId;
        this.tipoRecurso = tipoRecurso;
        this.recursoId = recursoId;
        this.estado = "ABIERTA";
        this.proposito = proposito;
    }

    public String getTipoRecurso() { return tipoRecurso; }
    public Long getRecursoId() { return recursoId; }
    public String getEstado() { return estado; }
    public String getProposito() { return proposito; }
    public void cerrar() { estado = "CERRADA"; }
    public void rechazar() { estado = "RECHAZADA"; }
}
