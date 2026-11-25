package com.belmonte.usuarios_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "decisiones_autorizacion")
public class DecisionAutorizacion {
    @Id
    @Column(name = "decision_id", length = 121)
    private String decisionId;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "sesion_id", nullable = false, length = 36)
    private String sesionId;
    @Column(name = "solicitud_hash", nullable = false, length = 64)
    private String solicitudHash;
    @Column(name = "jwt_expira_en", nullable = false)
    private OffsetDateTime jwtExpiraEn;
    @Column(name = "sesion_expira_en", nullable = false)
    private LocalDateTime sesionExpiraEn;
    @Column(name = "sesion_expira_instante")
    private OffsetDateTime sesionExpiraInstante;
    @Column(name = "tiempo_limite_superior")
    private OffsetDateTime tiempoLimiteSuperior;
    @Column(nullable = false, length = 20)
    private String estado;

    protected DecisionAutorizacion() {}
}
