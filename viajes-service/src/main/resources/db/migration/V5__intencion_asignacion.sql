ALTER TABLE operaciones_traslados ADD (
    sesion_original VARCHAR2(36 CHAR),
    solicitud_conductor_id NUMBER(19),
    solicitud_vehiculo_id NUMBER(19)
);

ALTER TABLE operaciones_traslados ADD CONSTRAINT chk_op_tras_intencion CHECK (
    (sesion_original IS NULL AND solicitud_conductor_id IS NULL AND solicitud_vehiculo_id IS NULL)
    OR
    (sesion_original IS NOT NULL AND solicitud_conductor_id IS NOT NULL
        AND solicitud_conductor_id > 0 AND solicitud_vehiculo_id IS NOT NULL AND solicitud_vehiculo_id > 0)
);
