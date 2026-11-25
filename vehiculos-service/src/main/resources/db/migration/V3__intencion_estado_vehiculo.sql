ALTER TABLE operaciones_estado_vehiculo ADD (
    sesion_original VARCHAR2(36 CHAR),
    solicitud_activo NUMBER(1)
);

ALTER TABLE operaciones_estado_vehiculo ADD CONSTRAINT chk_op_veh_intencion CHECK (
    (sesion_original IS NULL AND solicitud_activo IS NULL)
    OR
    (sesion_original IS NOT NULL AND solicitud_activo IS NOT NULL AND solicitud_activo IN (0, 1))
);
