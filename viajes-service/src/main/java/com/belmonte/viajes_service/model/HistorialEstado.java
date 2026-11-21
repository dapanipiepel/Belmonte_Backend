package com.belmonte.viajes_service.model;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "historial_estados")
public class HistorialEstado {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_historial")
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "traslado_id", nullable = false)
    private Traslado traslado;

    @ManyToOne(optional = false)
    @JoinColumn(name = "estado_id", nullable = false)
    private EstadoTraslado estado;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Generated
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "fecha_cambio", nullable = false)
    private OffsetDateTime fechaCambio;

    @Column(length = 500)
    private String motivo;

    protected HistorialEstado() {}

    public HistorialEstado(Traslado traslado, EstadoTraslado estado, Long usuarioId, String motivo) {
        this.traslado = traslado;
        this.estado = estado;
        this.usuarioId = usuarioId;
        this.motivo = motivo;
    }

    public Long getId() { return id; }
    public Traslado getTraslado() { return traslado; }
    public EstadoTraslado getEstado() { return estado; }
    public Long getUsuarioId() { return usuarioId; }
    public OffsetDateTime getFechaCambio() { return fechaCambio; }
    public String getMotivo() { return motivo; }
}
