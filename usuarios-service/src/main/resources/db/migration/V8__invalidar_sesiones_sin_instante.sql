UPDATE sesiones_usuario SET activa = 0
WHERE fecha_expiracion_instante IS NULL AND activa <> 0;

ALTER TABLE sesiones_usuario ADD CONSTRAINT chk_sesion_activa_instante
    CHECK (activa = 0 OR fecha_expiracion_instante IS NOT NULL);
