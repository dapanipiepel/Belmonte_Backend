package com.belmonte.usuarios_service.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "invitaciones")
public class Invitacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_invitacion")
    private Long idInvitacion;

    @Column(name = "email_destinatario", nullable = false, length = 120)
    private String emailDestinatario;

    @ManyToOne
    @JoinColumn(name = "persona_id")
    private Persona persona;

    @ManyToOne
    @JoinColumn(name = "rol_id", nullable = false)
    private Rol rol;

    @ManyToOne
    @JoinColumn(name = "creado_por_usuario_id", nullable = false)
    private Usuario creadoPorUsuario;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "fecha_creacion", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "fecha_expiracion", nullable = false)
    private OffsetDateTime fechaExpiracion;

    @Convert(converter = org.hibernate.type.NumericBooleanConverter.class)
    @Column(name = "usada", nullable = false)
    private boolean usada;

    @Column(name = "fecha_uso", nullable = true)
    private OffsetDateTime fechaUso;

    // Getters and Setters
    public Long getIdInvitacion() {
        return idInvitacion;
    }

    public void setIdInvitacion(Long idInvitacion) {
        this.idInvitacion = idInvitacion;
    }

    public String getEmailDestinatario() {
        return emailDestinatario;
    }

    public Persona getPersona() {
        return persona;
    }

    public void setPersona(Persona persona) {
        this.persona = persona;
    }

    public void setEmailDestinatario(String emailDestinatario) {
        this.emailDestinatario = emailDestinatario;
    }

    public Rol getRol() {
        return rol;
    }

    public void setRol(Rol rol) {
        this.rol = rol;
    }

    public Usuario getCreadoPorUsuario() {
        return creadoPorUsuario;
    }

    public void setCreadoPorUsuario(Usuario creadoPorUsuario) {
        this.creadoPorUsuario = creadoPorUsuario;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public OffsetDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(OffsetDateTime fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    public OffsetDateTime getFechaExpiracion() {
        return fechaExpiracion;
    }

    public void setFechaExpiracion(OffsetDateTime fechaExpiracion) {
        this.fechaExpiracion = fechaExpiracion;
    }

    public boolean isUsada() {
        return usada;
    }

    public void setUsada(boolean usada) {
        this.usada = usada;
    }

    public OffsetDateTime getFechaUso() {
        return fechaUso;
    }

    public void setFechaUso(OffsetDateTime fechaUso) {
        this.fechaUso = fechaUso;
    }
}