CREATE TABLE paises (
    id_pais NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    nombre_pais VARCHAR2(100) NOT NULL,
    CONSTRAINT pk_paises PRIMARY KEY (id_pais),
    CONSTRAINT uq_paises_nombre UNIQUE (nombre_pais)
);

CREATE TABLE regiones (
    id_region NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    pais_id NUMBER(19) NOT NULL,
    nombre_region VARCHAR2(100) NOT NULL,
    CONSTRAINT pk_regiones PRIMARY KEY (id_region),
    CONSTRAINT uq_regiones_pais_nombre UNIQUE (pais_id, nombre_region),
    CONSTRAINT fk_regiones_pais FOREIGN KEY (pais_id) REFERENCES paises (id_pais)
);

CREATE TABLE comunas (
    id_comuna NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    region_id NUMBER(19) NOT NULL,
    nombre_comuna VARCHAR2(100) NOT NULL,
    CONSTRAINT pk_comunas PRIMARY KEY (id_comuna),
    CONSTRAINT uq_comunas_region_nombre UNIQUE (region_id, nombre_comuna),
    CONSTRAINT fk_comunas_region FOREIGN KEY (region_id) REFERENCES regiones (id_region)
);

CREATE TABLE empresas (
    id_empresa NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    rut_empresa VARCHAR2(20) NOT NULL,
    razon_social VARCHAR2(150) NOT NULL,
    telefono VARCHAR2(20),
    email VARCHAR2(120),
    CONSTRAINT pk_empresas PRIMARY KEY (id_empresa),
    CONSTRAINT uq_empresas_rut UNIQUE (rut_empresa)
);

CREATE TABLE roles (
    id_rol NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    nombre_rol VARCHAR2(30) NOT NULL,
    descripcion VARCHAR2(255),
    CONSTRAINT pk_roles PRIMARY KEY (id_rol),
    CONSTRAINT uq_roles_nombre UNIQUE (nombre_rol)
);

CREATE TABLE tipos_documento (
    id_tipo_documento NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    codigo_tipo VARCHAR2(20) NOT NULL,
    descripcion VARCHAR2(255),
    CONSTRAINT pk_tipos_documento PRIMARY KEY (id_tipo_documento),
    CONSTRAINT uq_tipos_documento_codigo UNIQUE (codigo_tipo)
);

CREATE TABLE personas (
    id_persona NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    pais_documento_id NUMBER(19) NOT NULL,
    tipo_documento_id NUMBER(19) NOT NULL,
    rut_dni VARCHAR2(30) NOT NULL,
    nombre_completo VARCHAR2(100) NOT NULL,
    telefono VARCHAR2(20),
    email VARCHAR2(120) NOT NULL,
    fecha_creacion TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_personas PRIMARY KEY (id_persona),
    CONSTRAINT uq_personas_documento UNIQUE (pais_documento_id, tipo_documento_id, rut_dni),
    CONSTRAINT uq_personas_email UNIQUE (email),
    CONSTRAINT fk_personas_pais FOREIGN KEY (pais_documento_id) REFERENCES paises (id_pais),
    CONSTRAINT fk_personas_tipo_doc FOREIGN KEY (tipo_documento_id) REFERENCES tipos_documento (id_tipo_documento)
);

CREATE TABLE usuarios (
    id_usuario NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    persona_id NUMBER(19) NOT NULL,
    rol_id NUMBER(19) NOT NULL,
    password VARCHAR2(255) NOT NULL,
    activo NUMBER(1) DEFAULT 1 NOT NULL,
    CONSTRAINT pk_usuarios PRIMARY KEY (id_usuario),
    CONSTRAINT uq_usuarios_persona UNIQUE (persona_id),
    CONSTRAINT chk_usuarios_activo CHECK (activo IN (0, 1)),
    CONSTRAINT fk_usuarios_persona FOREIGN KEY (persona_id) REFERENCES personas (id_persona),
    CONSTRAINT fk_usuarios_rol FOREIGN KEY (rol_id) REFERENCES roles (id_rol)
);

CREATE TABLE pasajeros (
    id_pasajero NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    persona_id NUMBER(19) NOT NULL,
    empresa_id NUMBER(19) NOT NULL,
    CONSTRAINT pk_pasajeros PRIMARY KEY (id_pasajero),
    CONSTRAINT uq_pasajeros_persona UNIQUE (persona_id),
    CONSTRAINT fk_pasajeros_persona FOREIGN KEY (persona_id) REFERENCES personas (id_persona),
    CONSTRAINT fk_pasajeros_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (id_empresa)
);

CREATE INDEX idx_pasajeros_empresa ON pasajeros (empresa_id);
