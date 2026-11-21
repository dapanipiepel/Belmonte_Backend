package com.belmonte.vehiculos_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "vehiculos")
public class Vehiculo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_vehiculo")
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tipo_vehiculo_id", nullable = false)
    private TipoVehiculo tipo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "modelo_id", nullable = false)
    private ModeloVehiculo modelo;

    @Column(nullable = false, unique = true, length = 10)
    private String patente;

    @Column(nullable = false)
    private Integer anio;

    @Column(length = 30)
    private String color;

    @Column(name = "capacidad_pasajeros", nullable = false)
    private Integer capacidadPasajeros;

    @Column(nullable = false)
    private Boolean activo;

    protected Vehiculo() {}

    public Vehiculo(TipoVehiculo tipo, ModeloVehiculo modelo, String patente, Integer anio,
                    String color, Integer capacidadPasajeros, Boolean activo) {
        this.tipo = tipo;
        this.modelo = modelo;
        this.patente = patente;
        this.anio = anio;
        this.color = color;
        this.capacidadPasajeros = capacidadPasajeros;
        this.activo = activo;
    }

    public Long getId() { return id; }
    public TipoVehiculo getTipo() { return tipo; }
    public ModeloVehiculo getModelo() { return modelo; }
    public String getPatente() { return patente; }
    public Integer getAnio() { return anio; }
    public String getColor() { return color; }
    public Integer getCapacidadPasajeros() { return capacidadPasajeros; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
}
