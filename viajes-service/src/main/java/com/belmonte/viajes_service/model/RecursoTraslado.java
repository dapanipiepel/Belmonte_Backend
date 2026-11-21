package com.belmonte.viajes_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "recursos_traslado")
@IdClass(RecursoTraslado.RecursoId.class)
public class RecursoTraslado {

    @Id
    @Column(name = "tipo_recurso", nullable = false, length = 20)
    private String tipoRecurso;

    @Id
    @Column(name = "recurso_id", nullable = false)
    private Long recursoId;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado;

    @Column(name = "actualizado_en", nullable = false)
    private OffsetDateTime actualizadoEn;

    protected RecursoTraslado() {}

    public RecursoTraslado(String tipoRecurso, Long recursoId, String estado) {
        this.tipoRecurso = tipoRecurso;
        this.recursoId = recursoId;
        this.estado = estado;
        this.actualizadoEn = OffsetDateTime.now();
    }

    public void setEstado(String estado) {
        this.estado = estado;
        this.actualizadoEn = OffsetDateTime.now();
    }

    public String getEstado() { return estado; }

    @Embeddable
    public static class RecursoId implements Serializable {
        private String tipoRecurso;
        private Long recursoId;

        public RecursoId() {}

        public RecursoId(String tipoRecurso, Long recursoId) {
            this.tipoRecurso = tipoRecurso;
            this.recursoId = recursoId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RecursoId that)) return false;
            return Objects.equals(tipoRecurso, that.tipoRecurso)
                    && Objects.equals(recursoId, that.recursoId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tipoRecurso, recursoId);
        }
    }
}
