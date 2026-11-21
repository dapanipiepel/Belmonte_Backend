package com.belmonte.vehiculos_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "modelos_vehiculo", uniqueConstraints = @UniqueConstraint(columnNames = {"marca_id", "nombre_modelo"}))
public class ModeloVehiculo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_modelo")
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "marca_id", nullable = false)
    private MarcaVehiculo marca;

    @Column(name = "nombre_modelo", nullable = false, length = 50)
    private String nombre;

    protected ModeloVehiculo() {}

    public ModeloVehiculo(MarcaVehiculo marca, String nombre) {
        this.marca = marca;
        this.nombre = nombre;
    }

    public Long getId() { return id; }
    public MarcaVehiculo getMarca() { return marca; }
    public String getNombre() { return nombre; }
}
