package com.belmonte.usuarios_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "empresas", uniqueConstraints = @UniqueConstraint(name = "uq_empresas_rut", columnNames = "rut_empresa"))
public class Empresa {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_empresa")
    private Long idEmpresa;

    @Column(name = "rut_empresa", nullable = false, length = 20)
    private String rutEmpresa;

    @Column(name = "razon_social", nullable = false, length = 150)
    private String razonSocial;

    @Column(name = "telefono", length = 20)
    private String telefono;

    @Column(name = "email", length = 120)
    private String email;

    protected Empresa() {}

    public Long getIdEmpresa() { return idEmpresa; }
    public String getRutEmpresa() { return rutEmpresa; }
    public String getRazonSocial() { return razonSocial; }
    public String getTelefono() { return telefono; }
    public String getEmail() { return email; }
}
