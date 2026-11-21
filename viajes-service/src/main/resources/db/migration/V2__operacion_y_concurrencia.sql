CREATE TABLE operaciones_traslados (
    clave VARCHAR2(100 CHAR) PRIMARY KEY,
    actor_id NUMBER(19) NOT NULL,
    operacion VARCHAR2(50 CHAR) NOT NULL,
    traslado_id NUMBER(19),
    respuesta_codigo NUMBER(3) NOT NULL,
    creado_en TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE recursos_traslado (
    tipo_recurso VARCHAR2(20 CHAR) NOT NULL,
    recurso_id NUMBER(19) NOT NULL,
    estado VARCHAR2(20 CHAR) NOT NULL,
    actualizado_en TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_recursos_traslado PRIMARY KEY (tipo_recurso, recurso_id),
    CONSTRAINT chk_recursos_traslado_estado CHECK (estado IN ('LIBRE', 'BLOQUEADO'))
);
