-- =====================================================================
-- PROYECTO BELMONTE - SCRIPT DDL ORACLE  
-- Cambios respecto a v10:
--  traslados: se elimina estado_actual_id (triángulo traslados/estados_traslado/
--       historial_estados). El estado actual se obtiene de historial_estados
--       mediante la vista v_traslados_estado_actual.

--  facturas_corporativas: se elimina empresa_id (dependencia transitiva
--       id_factura -> plan_empresa_id -> empresa_id y triángulo con empresas).

--  solicitudes_aumento_cuota: se elimina empresa_id.

--  vehiculos: marca/modelo normalizados (modelo -> marca era dependencia
--       transitiva). Nuevas tablas marcas_vehiculo y modelos_vehiculo.

--  CHECK de dominio faltantes (activo, leido, coordenadas, montos, etc.).

--  UNIQUE naturales faltantes (región, comuna, ubicación, boleta).

--  Índices sobre FKs (Oracle no los crea automáticamente).

--  Un solo plan activo por empresa (índice único funcional).

-- Triángulos que permanecen y SON necesarios (ver informe):
--  * historial_estados / traslados / usuarios      (usuario que ejecuta el cambio)
--  * notificaciones   / traslados / usuarios      (destinatario independiente del traslado)
-- =====================================================================

-- -----------------------------------------------------
-- 1. TABLAS MAESTRAS Y GEOGRÁFICAS
-- -----------------------------------------------------
CREATE TABLE paises (
    id_pais     NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    nombre_pais VARCHAR2 (100) NOT NULL,
    CONSTRAINT pk_paises PRIMARY KEY (id_pais),
    CONSTRAINT uq_paises_nombre UNIQUE (nombre_pais)
);

CREATE TABLE regiones (
    id_region     NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    pais_id       NUMBER (19) NOT NULL,
    nombre_region VARCHAR2 (100) NOT NULL,
    CONSTRAINT pk_regiones PRIMARY KEY (id_region),
    CONSTRAINT uq_regiones_pais_nombre UNIQUE (pais_id, nombre_region),
    CONSTRAINT fk_regiones_pais FOREIGN KEY (pais_id) REFERENCES paises (id_pais)
);

CREATE TABLE comunas (
    id_comuna     NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    region_id     NUMBER (19) NOT NULL,
    nombre_comuna VARCHAR2 (100) NOT NULL,
    CONSTRAINT pk_comunas PRIMARY KEY (id_comuna),
    CONSTRAINT uq_comunas_region_nombre UNIQUE (region_id, nombre_comuna),
    CONSTRAINT fk_comunas_region FOREIGN KEY (region_id) REFERENCES regiones (id_region)
);

CREATE TABLE ubicaciones (
    id_ubicacion    NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    comuna_id       NUMBER (19) NOT NULL,
    direccion_texto VARCHAR2 (255) NOT NULL,
    latitud         NUMBER (10,8) NOT NULL,
    longitud        NUMBER (11,8) NOT NULL,
    CONSTRAINT pk_ubicaciones PRIMARY KEY (id_ubicacion),
    -- Soporta "registrar la ubicación si no existe" (CU-04)
    CONSTRAINT uq_ubicaciones_punto UNIQUE (comuna_id, direccion_texto, latitud, longitud),
    CONSTRAINT chk_ubicaciones_lat CHECK (latitud BETWEEN -90 AND 90),
    CONSTRAINT chk_ubicaciones_lon CHECK (longitud BETWEEN -180 AND 180),
    CONSTRAINT fk_ubicaciones_comuna FOREIGN KEY (comuna_id) REFERENCES comunas (id_comuna)
);

-- -----------------------------------------------------
-- 2. ENTIDADES Y PERSONAS (SUPERTIPO / SUBTIPO)
-- -----------------------------------------------------
CREATE TABLE empresas (
    id_empresa   NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    rut_empresa  VARCHAR2 (20) NOT NULL,
    razon_social VARCHAR2 (150) NOT NULL,
    telefono     VARCHAR2 (20),
    email        VARCHAR2 (120),
    CONSTRAINT pk_empresas PRIMARY KEY (id_empresa),
    CONSTRAINT uq_empresas_rut UNIQUE (rut_empresa)
);

CREATE TABLE roles (
    id_rol      NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    nombre_rol  VARCHAR2 (30) NOT NULL,
    descripcion VARCHAR2 (255),
    CONSTRAINT pk_roles PRIMARY KEY (id_rol),
    CONSTRAINT uq_roles_nombre UNIQUE (nombre_rol)
);

CREATE TABLE tipos_documento (
    id_tipo_documento NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    codigo_tipo       VARCHAR2 (20) NOT NULL,
    descripcion       VARCHAR2 (255),
    CONSTRAINT pk_tipos_documento PRIMARY KEY (id_tipo_documento),
    CONSTRAINT uq_tipos_documento_codigo UNIQUE (codigo_tipo)
);

-- Supertipo Persona
CREATE TABLE personas (
    id_persona        NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    pais_documento_id NUMBER (19) NOT NULL,
    tipo_documento_id NUMBER (19) NOT NULL,
    rut_dni           VARCHAR2 (30) NOT NULL,
    nombre_completo   VARCHAR2 (100) NOT NULL,
    telefono          VARCHAR2 (20),
    email             VARCHAR2 (120) NOT NULL,
    fecha_creacion    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_personas PRIMARY KEY (id_persona),
    CONSTRAINT uq_personas_documento UNIQUE (pais_documento_id, tipo_documento_id, rut_dni),
    CONSTRAINT uq_personas_email UNIQUE (email),
    CONSTRAINT fk_personas_pais FOREIGN KEY (pais_documento_id) REFERENCES paises (id_pais),
    CONSTRAINT fk_personas_tipo_doc FOREIGN KEY (tipo_documento_id) REFERENCES tipos_documento (id_tipo_documento)
);

-- Subtipo Usuario (acceso al sistema: administrador / conductor / solicitante)
CREATE TABLE usuarios (
    id_usuario NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    persona_id NUMBER (19) NOT NULL,
    rol_id     NUMBER (19) NOT NULL,
    password   VARCHAR2 (255) NOT NULL,
    activo     NUMBER (1) DEFAULT 1 NOT NULL,
    CONSTRAINT pk_usuarios PRIMARY KEY (id_usuario),
    CONSTRAINT uq_usuarios_persona UNIQUE (persona_id),
    CONSTRAINT chk_usuarios_activo CHECK (activo IN (0, 1)),
    CONSTRAINT fk_usuarios_persona FOREIGN KEY (persona_id) REFERENCES personas (id_persona),
    CONSTRAINT fk_usuarios_rol FOREIGN KEY (rol_id) REFERENCES roles (id_rol)
);

-- Subtipo Pasajero (beneficiario del viaje)
CREATE TABLE pasajeros (
    id_pasajero NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    persona_id  NUMBER (19) NOT NULL,
    empresa_id  NUMBER (19),
    CONSTRAINT pk_pasajeros PRIMARY KEY (id_pasajero),
    CONSTRAINT uq_pasajeros_persona UNIQUE (persona_id),
    CONSTRAINT fk_pasajeros_persona FOREIGN KEY (persona_id) REFERENCES personas (id_persona),
    CONSTRAINT fk_pasajeros_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (id_empresa)
);

-- -----------------------------------------------------
-- 3. VEHÍCULOS Y TARIFAS NORMALIZADAS
-- -----------------------------------------------------
CREATE TABLE tipos_vehiculo (
    id_tipo_vehiculo NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    codigo_tipo      VARCHAR2 (20) NOT NULL,
    descripcion      VARCHAR2 (100) NOT NULL,
    CONSTRAINT pk_tipos_vehiculo PRIMARY KEY (id_tipo_vehiculo),
    CONSTRAINT uq_tipos_vehiculo_codigo UNIQUE (codigo_tipo)
);

-- marca -> modelo -> vehículo (elimina modelo -> marca dentro de vehiculos)
CREATE TABLE marcas_vehiculo (
    id_marca     NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    nombre_marca VARCHAR2 (50) NOT NULL,
    CONSTRAINT pk_marcas_vehiculo PRIMARY KEY (id_marca),
    CONSTRAINT uq_marcas_vehiculo_nombre UNIQUE (nombre_marca)
);

CREATE TABLE modelos_vehiculo (
    id_modelo     NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    marca_id      NUMBER (19) NOT NULL,
    nombre_modelo VARCHAR2 (50) NOT NULL,
    CONSTRAINT pk_modelos_vehiculo PRIMARY KEY (id_modelo),
    CONSTRAINT uq_modelos_marca_nombre UNIQUE (marca_id, nombre_modelo),
    CONSTRAINT fk_modelos_marca FOREIGN KEY (marca_id) REFERENCES marcas_vehiculo (id_marca)
);

CREATE TABLE vehiculos (
    id_vehiculo         NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    tipo_vehiculo_id    NUMBER (19) NOT NULL,
    modelo_id           NUMBER (19) NOT NULL,
    patente             VARCHAR2 (10) NOT NULL,
    anio                NUMBER (4) NOT NULL,
    color               VARCHAR2 (30),
    capacidad_pasajeros NUMBER (3) NOT NULL,
    activo              NUMBER (1) DEFAULT 1 NOT NULL,
    CONSTRAINT pk_vehiculos PRIMARY KEY (id_vehiculo),
    CONSTRAINT uq_vehiculos_patente UNIQUE (patente),
    CONSTRAINT chk_vehiculos_capacidad CHECK (capacidad_pasajeros > 0),
    CONSTRAINT chk_vehiculos_activo CHECK (activo IN (0, 1)),
    CONSTRAINT fk_vehiculos_tipo FOREIGN KEY (tipo_vehiculo_id) REFERENCES tipos_vehiculo (id_tipo_vehiculo),
    CONSTRAINT fk_vehiculos_modelo FOREIGN KEY (modelo_id) REFERENCES modelos_vehiculo (id_modelo)
);

CREATE TABLE tarifas (
    id_tarifa         NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    comuna_origen_id  NUMBER (19) NOT NULL,
    comuna_destino_id NUMBER (19) NOT NULL,
    tipo_vehiculo_id  NUMBER (19) NOT NULL,
    precio            NUMBER (12,2) NOT NULL,
    CONSTRAINT pk_tarifas PRIMARY KEY (id_tarifa),
    CONSTRAINT uq_tarifa_tramo_tipo UNIQUE (comuna_origen_id, comuna_destino_id, tipo_vehiculo_id),
    CONSTRAINT chk_tarifas_precio CHECK (precio > 0),
    CONSTRAINT fk_tarifa_origen FOREIGN KEY (comuna_origen_id) REFERENCES comunas (id_comuna),
    CONSTRAINT fk_tarifa_destino FOREIGN KEY (comuna_destino_id) REFERENCES comunas (id_comuna),
    CONSTRAINT fk_tarifa_tipo_vehiculo FOREIGN KEY (tipo_vehiculo_id) REFERENCES tipos_vehiculo (id_tipo_vehiculo)
);

-- -----------------------------------------------------
-- 4. OPERACIÓN DE TRASLADOS Y ESTADOS
-- -----------------------------------------------------
CREATE TABLE estados_traslado (
    id_estado     NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    codigo_estado VARCHAR2 (20) NOT NULL,
    descripcion   VARCHAR2 (255),
    CONSTRAINT pk_estados_traslado PRIMARY KEY (id_estado),
    CONSTRAINT uq_estados_traslado_codigo UNIQUE (codigo_estado)
);

-- Sin estado_actual_id: el estado vigente es la última fila de historial_estados
CREATE TABLE traslados (
    id_traslado          NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    solicitante_id       NUMBER (19) NOT NULL,
    conductor_id         NUMBER (19),
    vehiculo_id          NUMBER (19),
    origen_ubicacion_id  NUMBER (19) NOT NULL,
    destino_ubicacion_id NUMBER (19) NOT NULL,
    fecha_hora_viaje     TIMESTAMP NOT NULL,
    numero_vuelo         VARCHAR2 (30),
    fecha_creacion       TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    fecha_actualizacion  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_traslados PRIMARY KEY (id_traslado),
    CONSTRAINT chk_traslados_ruta CHECK (origen_ubicacion_id <> destino_ubicacion_id),
    -- CU-07: conductor y vehículo se asignan siempre juntos
    CONSTRAINT chk_traslados_asignacion CHECK (
        (conductor_id IS NULL AND vehiculo_id IS NULL) OR
        (conductor_id IS NOT NULL AND vehiculo_id IS NOT NULL)
    ),
    CONSTRAINT fk_traslados_solicitante FOREIGN KEY (solicitante_id) REFERENCES usuarios (id_usuario),
    CONSTRAINT fk_traslados_conductor FOREIGN KEY (conductor_id) REFERENCES usuarios (id_usuario),
    CONSTRAINT fk_traslados_vehiculo FOREIGN KEY (vehiculo_id) REFERENCES vehiculos (id_vehiculo),
    CONSTRAINT fk_traslados_origen FOREIGN KEY (origen_ubicacion_id) REFERENCES ubicaciones (id_ubicacion),
    CONSTRAINT fk_traslados_destino FOREIGN KEY (destino_ubicacion_id) REFERENCES ubicaciones (id_ubicacion)
);

CREATE TABLE traslados_pasajeros (
    traslado_id NUMBER (19) NOT NULL,
    pasajero_id NUMBER (19) NOT NULL,
    CONSTRAINT pk_traslados_pasajeros PRIMARY KEY (traslado_id, pasajero_id),
    CONSTRAINT fk_tp_traslado FOREIGN KEY (traslado_id) REFERENCES traslados (id_traslado),
    CONSTRAINT fk_tp_pasajero FOREIGN KEY (pasajero_id) REFERENCES pasajeros (id_pasajero)
);

-- Fuente única de verdad del estado del traslado.
-- usuario_id = quien ejecuta el cambio (puede ser admin, conductor o solicitante):
-- dato independiente, por eso este triángulo con traslados/usuarios es necesario.
CREATE TABLE historial_estados (
    id_historial NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    traslado_id  NUMBER (19) NOT NULL,
    estado_id    NUMBER (19) NOT NULL,
    usuario_id   NUMBER (19) NOT NULL,
    fecha_cambio TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_historial_estados PRIMARY KEY (id_historial),
    CONSTRAINT fk_historial_traslado FOREIGN KEY (traslado_id) REFERENCES traslados (id_traslado),
    CONSTRAINT fk_historial_estado FOREIGN KEY (estado_id) REFERENCES estados_traslado (id_estado),
    CONSTRAINT fk_historial_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios (id_usuario)
);

CREATE TABLE evaluaciones (
    id_evaluacion  NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    traslado_id    NUMBER (19) NOT NULL,
    calificacion   NUMBER (1) NOT NULL,
    comentario     VARCHAR2 (255),
    fecha_creacion TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_evaluaciones PRIMARY KEY (id_evaluacion),
    CONSTRAINT uq_evaluacion_traslado UNIQUE (traslado_id),
    CONSTRAINT chk_eval_puntuacion CHECK (calificacion BETWEEN 1 AND 5),
    CONSTRAINT fk_eval_traslado FOREIGN KEY (traslado_id) REFERENCES traslados (id_traslado)
);

-- -----------------------------------------------------
-- 5. PAGOS Y NOTIFICACIONES
-- -----------------------------------------------------
CREATE TABLE metodos_pago (
    id_metodo_pago NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    codigo_metodo  VARCHAR2 (30) NOT NULL,
    descripcion    VARCHAR2 (255),
    CONSTRAINT pk_metodos_pago PRIMARY KEY (id_metodo_pago),
    CONSTRAINT uq_metodos_pago_codigo UNIQUE (codigo_metodo)
);

CREATE TABLE estados_pago (
    id_estado_pago NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    codigo_estado  VARCHAR2 (20) NOT NULL,
    descripcion    VARCHAR2 (255),
    CONSTRAINT pk_estados_pago PRIMARY KEY (id_estado_pago),
    CONSTRAINT uq_estados_pago_codigo UNIQUE (codigo_estado)
);

-- monto = importe efectivamente cobrado (instantánea histórica; tarifas no tiene vigencia)
CREATE TABLE pagos (
    id_pago        NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    traslado_id    NUMBER (19) NOT NULL,
    metodo_pago_id NUMBER (19) NOT NULL,
    estado_pago_id NUMBER (19) NOT NULL,
    monto          NUMBER (12,2) NOT NULL,
    numero_boleta  VARCHAR2 (50),
    fecha_pago     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_pagos PRIMARY KEY (id_pago),
    CONSTRAINT uq_pagos_boleta UNIQUE (numero_boleta),
    CONSTRAINT chk_pagos_monto CHECK (monto > 0),
    CONSTRAINT fk_pagos_traslado FOREIGN KEY (traslado_id) REFERENCES traslados (id_traslado),
    CONSTRAINT fk_pagos_metodo FOREIGN KEY (metodo_pago_id) REFERENCES metodos_pago (id_metodo_pago),
    CONSTRAINT fk_pagos_estado FOREIGN KEY (estado_pago_id) REFERENCES estados_pago (id_estado_pago)
);

-- traslado_id = contexto opcional. El destinatario (usuario_id) es independiente
-- del traslado (p. ej. un administrador), por lo que este triángulo es necesario.
CREATE TABLE notificaciones (
    id_notificacion NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    usuario_id      NUMBER (19) NOT NULL,
    traslado_id     NUMBER (19),
    titulo          VARCHAR2 (100) NOT NULL,
    mensaje         VARCHAR2 (255) NOT NULL,
    leido           NUMBER (1) DEFAULT 0 NOT NULL,
    fecha_envio     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_notificaciones PRIMARY KEY (id_notificacion),
    CONSTRAINT chk_notif_leido CHECK (leido IN (0, 1)),
    CONSTRAINT fk_notif_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios (id_usuario),
    CONSTRAINT fk_notif_traslado FOREIGN KEY (traslado_id) REFERENCES traslados (id_traslado)
);

-- -----------------------------------------------------
-- 6. TRIGGERS DE AUTOMATIZACIÓN
-- -----------------------------------------------------
CREATE OR REPLACE TRIGGER trg_traslados_upd_time
    BEFORE UPDATE ON traslados
    FOR EACH ROW
BEGIN
   :NEW.fecha_actualizacion := SYSTIMESTAMP;
END;
/

-- -----------------------------------------------------
-- 7. FACTURACIÓN CORPORATIVA Y CUOTA MENSUAL
-- -----------------------------------------------------
-- Funcionalidad opcional para empresas con plan mensual.
-- Permite mostrar el cupo utilizado por la empresa sin integrar Transbank.
-- Ejemplo desde Spring Boot: "Has utilizado el 75% de tu cuota mensual".
-- La empresa de una factura / solicitud se obtiene SIEMPRE vía planes_empresas.

CREATE TABLE planes_empresas (
    id_plan_empresa     NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    empresa_id          NUMBER (19) NOT NULL,
    nombre_plan         VARCHAR2 (100) NOT NULL,
    monto_cuota_mensual NUMBER (12,2) NOT NULL,
    porcentaje_alerta   NUMBER (5,2) DEFAULT 75 NOT NULL,
    activo              NUMBER (1) DEFAULT 1 NOT NULL,
    fecha_inicio        DATE NOT NULL,
    fecha_fin           DATE,
    CONSTRAINT pk_planes_empresas PRIMARY KEY (id_plan_empresa),
    CONSTRAINT uq_planes_empresa_nombre UNIQUE (empresa_id, nombre_plan),
    CONSTRAINT fk_planes_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (id_empresa),
    CONSTRAINT chk_plan_monto CHECK (monto_cuota_mensual > 0),
    CONSTRAINT chk_plan_alerta CHECK (porcentaje_alerta BETWEEN 1 AND 100),
    CONSTRAINT chk_plan_activo CHECK (activo IN (0, 1)),
    CONSTRAINT chk_plan_fechas CHECK (fecha_fin IS NULL OR fecha_fin >= fecha_inicio)
);

-- A lo más un plan activo por empresa
CREATE UNIQUE INDEX uq_plan_activo_por_empresa
    ON planes_empresas (CASE WHEN activo = 1 THEN empresa_id END);

-- Sin empresa_id: id_factura -> plan_empresa_id -> empresa_id
CREATE TABLE facturas_corporativas (
    id_factura        NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    plan_empresa_id   NUMBER (19) NOT NULL,
    periodo_anio      NUMBER (4) NOT NULL,
    periodo_mes       NUMBER (2) NOT NULL,
    estado_factura    VARCHAR2 (20) DEFAULT 'ABIERTA' NOT NULL,
    fecha_emision     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    fecha_vencimiento DATE,
    fecha_cierre      TIMESTAMP,
    CONSTRAINT pk_facturas_corporativas PRIMARY KEY (id_factura),
    CONSTRAINT uq_factura_periodo UNIQUE (plan_empresa_id, periodo_anio, periodo_mes),
    CONSTRAINT fk_factura_plan FOREIGN KEY (plan_empresa_id) REFERENCES planes_empresas (id_plan_empresa),
    CONSTRAINT chk_factura_mes CHECK (periodo_mes BETWEEN 1 AND 12),
    CONSTRAINT chk_factura_estado CHECK (estado_factura IN ('ABIERTA', 'EMITIDA', 'PAGADA', 'VENCIDA', 'ANULADA'))
);

CREATE TABLE facturas_traslados (
    id_factura_traslado NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    factura_id          NUMBER (19) NOT NULL,
    traslado_id         NUMBER (19) NOT NULL,
    monto_aplicado      NUMBER (12,2) NOT NULL,
    fecha_registro      TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_facturas_traslados PRIMARY KEY (id_factura_traslado),
    CONSTRAINT uq_factura_traslado UNIQUE (traslado_id),
    CONSTRAINT fk_ft_factura FOREIGN KEY (factura_id) REFERENCES facturas_corporativas (id_factura),
    CONSTRAINT fk_ft_traslado FOREIGN KEY (traslado_id) REFERENCES traslados (id_traslado),
    CONSTRAINT chk_ft_monto CHECK (monto_aplicado >= 0)
);

-- Sin empresa_id (se obtiene vía plan_empresa_id).
-- monto_actual = instantánea de la cuota vigente al solicitar; el plan cambia al aprobarse.
CREATE TABLE solicitudes_aumento_cuota (
    id_solicitud_aumento   NUMBER (19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    plan_empresa_id        NUMBER (19) NOT NULL,
    usuario_solicitante_id NUMBER (19) NOT NULL,
    monto_actual           NUMBER (12,2) NOT NULL,
    monto_solicitado       NUMBER (12,2) NOT NULL,
    motivo                 VARCHAR2 (255),
    estado_solicitud       VARCHAR2 (20) DEFAULT 'PENDIENTE' NOT NULL,
    fecha_solicitud        TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    fecha_respuesta        TIMESTAMP,
    CONSTRAINT pk_solicitudes_aumento_cuota PRIMARY KEY (id_solicitud_aumento),
    CONSTRAINT fk_sac_plan FOREIGN KEY (plan_empresa_id) REFERENCES planes_empresas (id_plan_empresa),
    CONSTRAINT fk_sac_usuario FOREIGN KEY (usuario_solicitante_id) REFERENCES usuarios (id_usuario),
    CONSTRAINT chk_sac_montos CHECK (monto_actual > 0 AND monto_solicitado > monto_actual),
    CONSTRAINT chk_sac_estado CHECK (estado_solicitud IN ('PENDIENTE', 'APROBADA', 'RECHAZADA', 'CANCELADA'))
);

-- -----------------------------------------------------
-- 8. VISTA: ESTADO ACTUAL DEL TRASLADO  
-- -----------------------------------------------------
CREATE OR REPLACE VIEW v_traslados_estado_actual AS
SELECT t.id_traslado,
       h.estado_id     AS estado_actual_id,
       e.codigo_estado AS estado_actual,
       h.fecha_cambio  AS fecha_estado
FROM traslados t
LEFT JOIN (
    SELECT traslado_id, estado_id, fecha_cambio,
           ROW_NUMBER() OVER (PARTITION BY traslado_id
                              ORDER BY fecha_cambio DESC, id_historial DESC) AS rn
    FROM historial_estados
) h ON h.traslado_id = t.id_traslado AND h.rn = 1
LEFT JOIN estados_traslado e ON e.id_estado = h.estado_id;

-- -----------------------------------------------------
-- 9. ÍNDICES SOBRE FKs Y FILTROS FRECUENTES  
-- (Oracle no indexa FKs automáticamente; las que ya encabezan un UNIQUE/PK se omiten)
-- -----------------------------------------------------
CREATE INDEX idx_pasajeros_empresa      ON pasajeros (empresa_id);
CREATE INDEX idx_tarifas_destino        ON tarifas (comuna_destino_id);
CREATE INDEX idx_vehiculos_modelo       ON vehiculos (modelo_id);
CREATE INDEX idx_traslados_solicitante  ON traslados (solicitante_id);
CREATE INDEX idx_traslados_conductor    ON traslados (conductor_id, fecha_hora_viaje);  
CREATE INDEX idx_traslados_fecha        ON traslados (fecha_hora_viaje);                
CREATE INDEX idx_traslados_vehiculo     ON traslados (vehiculo_id);
CREATE INDEX idx_traslados_origen       ON traslados (origen_ubicacion_id);
CREATE INDEX idx_traslados_destino      ON traslados (destino_ubicacion_id);
CREATE INDEX idx_tp_pasajero            ON traslados_pasajeros (pasajero_id);
CREATE INDEX idx_historial_traslado     ON historial_estados (traslado_id, id_historial);
CREATE INDEX idx_historial_estado       ON historial_estados (estado_id);
CREATE INDEX idx_historial_usuario      ON historial_estados (usuario_id);
CREATE INDEX idx_pagos_traslado         ON pagos (traslado_id);
CREATE INDEX idx_notif_usuario_leido    ON notificaciones (usuario_id, leido);
CREATE INDEX idx_notif_traslado         ON notificaciones (traslado_id);
CREATE INDEX idx_ft_factura             ON facturas_traslados (factura_id);
CREATE INDEX idx_sac_plan               ON solicitudes_aumento_cuota (plan_empresa_id);
CREATE INDEX idx_sac_usuario            ON solicitudes_aumento_cuota (usuario_solicitante_id);