ALTER TABLE operaciones_cambio_usuario ADD (
    jwt_original_expira_en TIMESTAMP WITH TIME ZONE
);

ALTER TABLE operaciones_cambio_usuario ADD CONSTRAINT chk_op_usu_jwt_original CHECK (
    jwt_original_expira_en IS NULL OR sesion_original IS NOT NULL
);
