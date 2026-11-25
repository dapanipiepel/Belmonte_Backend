ALTER TABLE operaciones_estado_vehiculo ADD (
    jwt_original_expira_en TIMESTAMP WITH TIME ZONE
);

ALTER TABLE operaciones_estado_vehiculo ADD CONSTRAINT chk_op_veh_jwt_original CHECK (
    jwt_original_expira_en IS NULL OR sesion_original IS NOT NULL
);
