ALTER TABLE operaciones_traslados DROP CONSTRAINT chk_op_tras_intencion;

ALTER TABLE operaciones_traslados ADD CONSTRAINT chk_op_tras_intencion CHECK (
    (sesion_original IS NULL AND solicitud_conductor_id IS NULL AND solicitud_vehiculo_id IS NULL)
    OR
    (sesion_original IS NOT NULL AND solicitud_conductor_id IS NOT NULL
        AND solicitud_conductor_id > 0 AND solicitud_vehiculo_id IS NOT NULL AND solicitud_vehiculo_id > 0)
    OR
    (operacion = 'crear' AND estado_operacion IS NOT NULL AND estado_operacion = 'PENDIENTE' AND sesion_original IS NOT NULL
        AND solicitud_conductor_id IS NULL AND solicitud_vehiculo_id IS NULL)
);
