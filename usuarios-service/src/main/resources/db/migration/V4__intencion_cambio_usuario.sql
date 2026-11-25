ALTER TABLE operaciones_cambio_usuario ADD (
    sesion_original VARCHAR2(36 CHAR),
    tipo_solicitud VARCHAR2(10 CHAR),
    solicitud_activo NUMBER(1),
    solicitud_rol_id NUMBER(19)
);

ALTER TABLE operaciones_cambio_usuario ADD CONSTRAINT chk_op_usu_intencion CHECK (
    (sesion_original IS NULL AND tipo_solicitud IS NULL AND solicitud_activo IS NULL AND solicitud_rol_id IS NULL)
    OR
    (sesion_original IS NOT NULL AND tipo_solicitud IS NOT NULL AND (
        (tipo_solicitud = 'ESTADO' AND solicitud_activo IS NOT NULL
            AND solicitud_activo IN (0, 1) AND solicitud_rol_id IS NULL)
        OR
        (tipo_solicitud = 'ROL' AND solicitud_activo IS NULL
            AND solicitud_rol_id IS NOT NULL AND solicitud_rol_id > 0)
    ))
);
