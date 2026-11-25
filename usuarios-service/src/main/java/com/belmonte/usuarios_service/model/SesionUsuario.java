package com.belmonte.usuarios_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sesiones_usuario")
public class SesionUsuario {

    @Id
    @Column(name = "sesion_id", nullable = false, length = 36)
    private String sesionId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDateTime fechaInicio;

    @Column(name = "fecha_expiracion", nullable = false)
    private LocalDateTime fechaExpiracion;

    @Column(name = "fecha_expiracion_instante")
    private OffsetDateTime fechaExpiracionInstante;

    @Column(name = "activa", nullable = false)
    private Integer activa = 1;

    public SesionUsuario() {
    }

    public String getSesionId() {
        return sesionId;
    }

    public void setSesionId(String sesionId) {
        this.sesionId = sesionId;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    public LocalDateTime getFechaInicio() {
        return fechaInicio;
    }

    public void setFechaInicio(LocalDateTime fechaInicio) {
        this.fechaInicio = fechaInicio;
    }

    public LocalDateTime getFechaExpiracion() {
        return fechaExpiracion;
    }

    public void setFechaExpiracion(LocalDateTime fechaExpiracion) {
        this.fechaExpiracion = fechaExpiracion;
    }

    public OffsetDateTime getFechaExpiracionInstante() {
        return fechaExpiracionInstante;
    }

    public void setFechaExpiracionInstante(OffsetDateTime fechaExpiracionInstante) {
        this.fechaExpiracionInstante = fechaExpiracionInstante;
    }

    public boolean vigenteEn(Instant ahora) {
        return fechaExpiracionInstante != null && fechaExpiracionInstante.toInstant().isAfter(ahora);
    }

    public Integer getActiva() {
        return activa;
    }

    public void setActiva(Integer activa) {
        this.activa = activa;
    }
}
