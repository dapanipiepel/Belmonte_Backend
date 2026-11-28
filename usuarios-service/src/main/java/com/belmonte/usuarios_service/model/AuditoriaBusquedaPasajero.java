package com.belmonte.usuarios_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "auditoria_busqueda_pasajeros",
        indexes = @Index(name = "idx_aud_busqueda_actor_fecha", columnList = "actor_id,fecha_evento"))
public class AuditoriaBusquedaPasajero {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_auditoria")
    private Long id;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "fecha_evento", nullable = false)
    private OffsetDateTime fechaEvento;

    @Column(name = "resultado", nullable = false, length = 20)
    private String resultado;

    @Column(name = "cantidad", nullable = false)
    private Integer cantidad;

    protected AuditoriaBusquedaPasajero() {}
}
