package com.belmonte.viajes_service.model;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "traslados")
public class Traslado {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_traslado")
    private Long id;

    @Column(name = "solicitante_id", nullable = false)
    private Long solicitanteId;

    @Column(name = "conductor_id")
    private Long conductorId;

    @Column(name = "vehiculo_id")
    private Long vehiculoId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "origen_ubicacion_id", nullable = false)
    private Ubicacion origen;

    @ManyToOne(optional = false)
    @JoinColumn(name = "destino_ubicacion_id", nullable = false)
    private Ubicacion destino;

    @Column(name = "traslado_previo_id")
    private Long trasladoPrevioId;

    @Column(name = "fecha_hora_viaje", nullable = false)
    private OffsetDateTime fechaHoraViaje;

    @Column(name = "fecha_hora_termino_estimado", nullable = false)
    private OffsetDateTime fechaHoraTerminoEstimado;

    @Column(name = "numero_vuelo", length = 30)
    private String numeroVuelo;

    @Generated
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "fecha_creacion", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "fecha_actualizacion", nullable = false)
    private OffsetDateTime fechaActualizacion;

    @ElementCollection
    @CollectionTable(name = "traslados_pasajeros", joinColumns = @JoinColumn(name = "traslado_id"))
    @Column(name = "pasajero_id", nullable = false)
    private Set<Long> pasajeros = new HashSet<>();

    protected Traslado() {}

    public Traslado(Long solicitanteId, Ubicacion origen, Ubicacion destino,
                    OffsetDateTime fechaHoraViaje, OffsetDateTime fechaHoraTerminoEstimado,
                    String numeroVuelo, Set<Long> pasajeros) {
        this.solicitanteId = solicitanteId;
        this.origen = origen;
        this.destino = destino;
        this.fechaHoraViaje = fechaHoraViaje;
        this.fechaHoraTerminoEstimado = fechaHoraTerminoEstimado;
        this.numeroVuelo = numeroVuelo;
        this.pasajeros = new HashSet<>(pasajeros);
    }

    public Long getId() { return id; }
    public Long getSolicitanteId() { return solicitanteId; }
    public Long getConductorId() { return conductorId; }
    public Long getVehiculoId() { return vehiculoId; }
    public Ubicacion getOrigen() { return origen; }
    public Ubicacion getDestino() { return destino; }
    public Long getTrasladoPrevioId() { return trasladoPrevioId; }
    public OffsetDateTime getFechaHoraViaje() { return fechaHoraViaje; }
    public OffsetDateTime getFechaHoraTerminoEstimado() { return fechaHoraTerminoEstimado; }
    public String getNumeroVuelo() { return numeroVuelo; }
    public OffsetDateTime getFechaCreacion() { return fechaCreacion; }
    public OffsetDateTime getFechaActualizacion() { return fechaActualizacion; }
    public Set<Long> getPasajeros() { return Set.copyOf(pasajeros); }
}
