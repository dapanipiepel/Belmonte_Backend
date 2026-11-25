ALTER TABLE operaciones_traslados ADD (
    jwt_original_expira_en TIMESTAMP WITH TIME ZONE
);

ALTER TABLE operaciones_traslados ADD CONSTRAINT chk_op_tras_jwt_original CHECK (
    jwt_original_expira_en IS NULL OR sesion_original IS NOT NULL
);
