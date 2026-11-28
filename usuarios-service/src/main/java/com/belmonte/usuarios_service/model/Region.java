package com.belmonte.usuarios_service.model;

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
@Table(name = "regiones", uniqueConstraints = @UniqueConstraint(columnNames = {"pais_id", "nombre_region"}))
public class Region {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_region")
    private Long idRegion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pais_id", nullable = false)
    private Pais pais;

    @Column(name = "nombre_region", nullable = false, length = 100)
    private String nombreRegion;

    protected Region() {}
}
