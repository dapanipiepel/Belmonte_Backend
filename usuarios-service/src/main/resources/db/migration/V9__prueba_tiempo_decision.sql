ALTER TABLE decisiones_autorizacion ADD tiempo_limite_superior TIMESTAMP WITH TIME ZONE;

ALTER TABLE decisiones_autorizacion ADD CONSTRAINT chk_decision_prueba_tiempo
    CHECK (tiempo_limite_superior IS NULL OR
        (estado = 'AUTORIZADA' AND sesion_expira_instante IS NOT NULL
         AND tiempo_limite_superior < jwt_expira_en
         AND tiempo_limite_superior < sesion_expira_instante));
