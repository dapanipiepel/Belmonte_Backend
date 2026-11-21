package com.belmonte.viajes_service.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ubicaciones")
public class Ubicacion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_ubicacion")
    private Long id;

    @Column(name = "comuna_id", nullable = false)
    private Long comunaId;

    @Column(name = "direccion_texto", nullable = false, length = 255)
    private String direccion;

    @Column(precision = 10, scale = 8)
    private BigDecimal latitud;

    @Column(precision = 11, scale = 8)
    private BigDecimal longitud;

    protected Ubicacion() {}

    public Ubicacion(Long comunaId, String direccion, BigDecimal latitud, BigDecimal longitud) {
        this.comunaId = comunaId;
        this.direccion = direccion;
        this.latitud = latitud;
        this.longitud = longitud;
    }

    public Long getId() { return id; }
    public Long getComunaId() { return comunaId; }
    public String getDireccion() { return direccion; }
    public BigDecimal getLatitud() { return latitud; }
    public BigDecimal getLongitud() { return longitud; }
}
