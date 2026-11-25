ALTER TABLE sesiones_usuario ADD fecha_expiracion_instante TIMESTAMP WITH TIME ZONE;
ALTER TABLE decisiones_autorizacion ADD sesion_expira_instante TIMESTAMP WITH TIME ZONE;
