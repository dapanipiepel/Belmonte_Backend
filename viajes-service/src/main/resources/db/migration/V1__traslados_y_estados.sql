CREATE TABLE ubicaciones (
    id_ubicacion NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    comuna_id NUMBER(19) NOT NULL,
    direccion_texto VARCHAR2(255) NOT NULL,
    latitud NUMBER(10,8),
    longitud NUMBER(11,8),
    CONSTRAINT pk_ubicaciones PRIMARY KEY (id_ubicacion),
    CONSTRAINT chk_ubicaciones_coordenadas CHECK (
        (latitud IS NULL AND longitud IS NULL) OR
        (latitud IS NOT NULL AND longitud IS NOT NULL AND
         latitud BETWEEN -90 AND 90 AND longitud BETWEEN -180 AND 180)
    )
);

CREATE TABLE estados_traslado (
    id_estado NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    codigo_estado VARCHAR2(20) NOT NULL,
    descripcion VARCHAR2(255),
    CONSTRAINT pk_estados_traslado PRIMARY KEY (id_estado),
    CONSTRAINT uq_estados_traslado_codigo UNIQUE (codigo_estado)
);

CREATE TABLE traslados (
    id_traslado NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    solicitante_id NUMBER(19) NOT NULL,
    conductor_id NUMBER(19),
    vehiculo_id NUMBER(19),
    origen_ubicacion_id NUMBER(19) NOT NULL,
    destino_ubicacion_id NUMBER(19) NOT NULL,
    traslado_previo_id NUMBER(19),
    fecha_hora_viaje TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_hora_termino_estimado TIMESTAMP WITH TIME ZONE NOT NULL,
    numero_vuelo VARCHAR2(30),
    fecha_creacion TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    fecha_actualizacion TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_traslados PRIMARY KEY (id_traslado),
    CONSTRAINT chk_traslados_ruta CHECK (origen_ubicacion_id <> destino_ubicacion_id),
    CONSTRAINT chk_traslados_asignacion CHECK (
        (conductor_id IS NULL AND vehiculo_id IS NULL) OR
        (conductor_id IS NOT NULL AND vehiculo_id IS NOT NULL)
    ),
    CONSTRAINT chk_traslados_horario CHECK (fecha_hora_termino_estimado > fecha_hora_viaje),
    CONSTRAINT chk_traslados_previo CHECK (traslado_previo_id IS NULL OR traslado_previo_id <> id_traslado),
    CONSTRAINT fk_traslados_origen FOREIGN KEY (origen_ubicacion_id) REFERENCES ubicaciones (id_ubicacion),
    CONSTRAINT fk_traslados_destino FOREIGN KEY (destino_ubicacion_id) REFERENCES ubicaciones (id_ubicacion),
    CONSTRAINT fk_traslados_previo FOREIGN KEY (traslado_previo_id) REFERENCES traslados (id_traslado)
);

CREATE TABLE traslados_pasajeros (
    traslado_id NUMBER(19) NOT NULL,
    pasajero_id NUMBER(19) NOT NULL,
    CONSTRAINT pk_traslados_pasajeros PRIMARY KEY (traslado_id, pasajero_id),
    CONSTRAINT fk_tp_traslado FOREIGN KEY (traslado_id) REFERENCES traslados (id_traslado)
);

CREATE TABLE historial_estados (
    id_historial NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    traslado_id NUMBER(19) NOT NULL,
    estado_id NUMBER(19) NOT NULL,
    usuario_id NUMBER(19) NOT NULL,
    fecha_cambio TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    motivo VARCHAR2(500),
    CONSTRAINT pk_historial_estados PRIMARY KEY (id_historial),
    CONSTRAINT fk_historial_traslado FOREIGN KEY (traslado_id) REFERENCES traslados (id_traslado),
    CONSTRAINT fk_historial_estado FOREIGN KEY (estado_id) REFERENCES estados_traslado (id_estado)
);

INSERT INTO estados_traslado (codigo_estado) VALUES ('PENDIENTE');
INSERT INTO estados_traslado (codigo_estado) VALUES ('ASIGNADO');
INSERT INTO estados_traslado (codigo_estado) VALUES ('EN_CURSO');
INSERT INTO estados_traslado (codigo_estado) VALUES ('FINALIZADO');
INSERT INTO estados_traslado (codigo_estado) VALUES ('CANCELADO');

CREATE OR REPLACE TRIGGER trg_traslados_upd_time
    BEFORE UPDATE ON traslados
    FOR EACH ROW
BEGIN
    :NEW.fecha_actualizacion := SYSTIMESTAMP;
END;
/

CREATE OR REPLACE VIEW v_traslados_estado_actual AS
SELECT t.id_traslado, h.estado_id AS estado_actual_id,
       e.codigo_estado AS estado_actual, h.fecha_cambio AS fecha_estado
FROM traslados t
LEFT JOIN (
    SELECT traslado_id, estado_id, fecha_cambio,
           ROW_NUMBER() OVER (PARTITION BY traslado_id ORDER BY fecha_cambio DESC, id_historial DESC) AS rn
    FROM historial_estados
) h ON h.traslado_id = t.id_traslado AND h.rn = 1
LEFT JOIN estados_traslado e ON e.id_estado = h.estado_id;

CREATE INDEX idx_ubicaciones_comuna ON ubicaciones (comuna_id);
CREATE INDEX idx_traslados_solicitante ON traslados (solicitante_id);
CREATE INDEX idx_traslados_conductor ON traslados (conductor_id, fecha_hora_viaje);
CREATE INDEX idx_traslados_fecha ON traslados (fecha_hora_viaje);
CREATE INDEX idx_traslados_vehiculo ON traslados (vehiculo_id, fecha_hora_viaje);
CREATE INDEX idx_traslados_origen ON traslados (origen_ubicacion_id);
CREATE INDEX idx_traslados_destino ON traslados (destino_ubicacion_id);
CREATE INDEX idx_traslados_previo ON traslados (traslado_previo_id);
CREATE INDEX idx_tp_pasajero ON traslados_pasajeros (pasajero_id);
CREATE INDEX idx_historial_traslado ON historial_estados (traslado_id, fecha_cambio, id_historial);
CREATE INDEX idx_historial_estado ON historial_estados (estado_id);
CREATE INDEX idx_historial_usuario ON historial_estados (usuario_id);
