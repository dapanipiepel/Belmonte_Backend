CREATE TABLE tipos_vehiculo (
    id_tipo_vehiculo NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    codigo_tipo VARCHAR2(20) NOT NULL,
    descripcion VARCHAR2(100) NOT NULL,
    CONSTRAINT pk_tipos_vehiculo PRIMARY KEY (id_tipo_vehiculo),
    CONSTRAINT uq_tipos_vehiculo_codigo UNIQUE (codigo_tipo)
);

CREATE TABLE marcas_vehiculo (
    id_marca NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    nombre_marca VARCHAR2(50) NOT NULL,
    CONSTRAINT pk_marcas_vehiculo PRIMARY KEY (id_marca),
    CONSTRAINT uq_marcas_vehiculo_nombre UNIQUE (nombre_marca)
);

CREATE TABLE modelos_vehiculo (
    id_modelo NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    marca_id NUMBER(19) NOT NULL,
    nombre_modelo VARCHAR2(50) NOT NULL,
    CONSTRAINT pk_modelos_vehiculo PRIMARY KEY (id_modelo),
    CONSTRAINT uq_modelos_marca_nombre UNIQUE (marca_id, nombre_modelo),
    CONSTRAINT fk_modelos_marca FOREIGN KEY (marca_id) REFERENCES marcas_vehiculo (id_marca)
);

CREATE TABLE vehiculos (
    id_vehiculo NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    tipo_vehiculo_id NUMBER(19) NOT NULL,
    modelo_id NUMBER(19) NOT NULL,
    patente VARCHAR2(10) NOT NULL,
    anio NUMBER(4) NOT NULL,
    color VARCHAR2(30),
    capacidad_pasajeros NUMBER(3) NOT NULL,
    activo NUMBER(1) DEFAULT 1 NOT NULL,
    CONSTRAINT pk_vehiculos PRIMARY KEY (id_vehiculo),
    CONSTRAINT uq_vehiculos_patente UNIQUE (patente),
    CONSTRAINT chk_vehiculos_capacidad CHECK (capacidad_pasajeros > 0),
    CONSTRAINT chk_vehiculos_activo CHECK (activo IN (0, 1)),
    CONSTRAINT fk_vehiculos_tipo FOREIGN KEY (tipo_vehiculo_id) REFERENCES tipos_vehiculo (id_tipo_vehiculo),
    CONSTRAINT fk_vehiculos_modelo FOREIGN KEY (modelo_id) REFERENCES modelos_vehiculo (id_modelo)
);

CREATE INDEX idx_vehiculos_modelo ON vehiculos (modelo_id);
