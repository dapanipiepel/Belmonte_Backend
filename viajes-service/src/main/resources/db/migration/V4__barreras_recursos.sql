CREATE TABLE barreras_recurso (
    operacion_id VARCHAR2(36 CHAR) PRIMARY KEY,
    tipo_recurso VARCHAR2(20 CHAR) NOT NULL,
    recurso_id NUMBER(19) NOT NULL,
    estado VARCHAR2(20 CHAR) NOT NULL,
    proposito VARCHAR2(20 CHAR) NOT NULL,
    CONSTRAINT chk_barrera_tipo CHECK (tipo_recurso IN ('CONDUCTOR', 'VEHICULO')),
    CONSTRAINT chk_barrera_estado CHECK (estado IN ('ABIERTA', 'CERRADA', 'RECHAZADA')),
    CONSTRAINT chk_barrera_proposito CHECK (proposito IN ('BAJA', 'CAMBIO_COMPATIBLE', 'ASIGNACION'))
);

CREATE INDEX idx_barrera_recurso ON barreras_recurso (tipo_recurso, recurso_id, estado);
