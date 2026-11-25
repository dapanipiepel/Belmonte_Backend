CREATE TABLE operaciones_cambio_usuario (
    clave VARCHAR2(100 CHAR) PRIMARY KEY,
    actor_id NUMBER(19),
    usuario_id NUMBER(19),
    solicitud_hash VARCHAR2(64 CHAR),
    barrera_id VARCHAR2(36 CHAR),
    proposito VARCHAR2(20 CHAR),
    estado VARCHAR2(20 CHAR) NOT NULL,
    respuesta_codigo NUMBER(3),
    respuesta_json CLOB,
    expira_en TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_op_usu_estado CHECK (estado IN ('PENDIENTE', 'APLICADA', 'COMPLETADA', 'EXPIRADA')),
    CONSTRAINT chk_op_usu_proposito CHECK (proposito IN ('BAJA', 'CAMBIO_COMPATIBLE'))
);

CREATE INDEX idx_op_usu_expira ON operaciones_cambio_usuario (estado, expira_en);
