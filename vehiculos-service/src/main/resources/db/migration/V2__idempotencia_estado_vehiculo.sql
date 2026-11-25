CREATE TABLE operaciones_estado_vehiculo (
    clave VARCHAR2(100 CHAR) PRIMARY KEY,
    actor_id NUMBER(19),
    vehiculo_id NUMBER(19),
    solicitud_hash VARCHAR2(64 CHAR),
    barrera_id VARCHAR2(36 CHAR),
    proposito VARCHAR2(20 CHAR),
    estado VARCHAR2(20 CHAR) NOT NULL,
    respuesta_codigo NUMBER(3),
    respuesta_json CLOB,
    expira_en TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_op_veh_estado CHECK (estado IN ('PENDIENTE', 'APLICADA', 'COMPLETADA', 'EXPIRADA')),
    CONSTRAINT chk_op_veh_proposito CHECK (proposito IN ('BAJA', 'CAMBIO_COMPATIBLE'))
);

CREATE INDEX idx_op_veh_expira ON operaciones_estado_vehiculo (estado, expira_en);
