CREATE TABLE auditoria_busqueda_pasajeros (
    id_auditoria NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    actor_id NUMBER(19) NOT NULL,
    fecha_evento TIMESTAMP WITH TIME ZONE NOT NULL,
    resultado VARCHAR2(20) NOT NULL,
    cantidad NUMBER(10) NOT NULL,
    CONSTRAINT pk_aud_busqueda_pasajeros PRIMARY KEY (id_auditoria),
    CONSTRAINT chk_aud_busqueda_resultado CHECK (resultado IN ('RESULTADOS', 'SIN_RESULTADOS', 'LIMITADA')),
    CONSTRAINT chk_aud_busqueda_cantidad CHECK (cantidad BETWEEN 0 AND 50)
);

CREATE INDEX idx_aud_busqueda_actor_fecha ON auditoria_busqueda_pasajeros (actor_id, fecha_evento);
