CREATE TABLE invitaciones (
    id_invitacion NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    email_destinatario VARCHAR2(120) NOT NULL,
    rol_id NUMBER(19) NOT NULL,
    creado_por_usuario_id NUMBER(19) NOT NULL,
    token_hash VARCHAR2(64) UNIQUE NOT NULL,
    fecha_creacion TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_expiracion TIMESTAMP WITH TIME ZONE NOT NULL,
    usada NUMBER(1) DEFAULT 0 NOT NULL,
    fecha_uso TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_invitaciones PRIMARY KEY (id_invitacion),
    CONSTRAINT chk_inv_fechas CHECK (fecha_expiracion > fecha_creacion),
    CONSTRAINT chk_inv_uso CHECK ((usada = 0 AND fecha_uso IS NULL) OR
                                  (usada = 1 AND fecha_uso IS NOT NULL AND fecha_uso >= fecha_creacion AND fecha_uso < fecha_expiracion)),
    CONSTRAINT fk_invitaciones_rol FOREIGN KEY (rol_id) REFERENCES roles(id_rol),
    CONSTRAINT fk_invitaciones_usuario_creador FOREIGN KEY (creado_por_usuario_id) REFERENCES usuarios(id_usuario)
);

CREATE TABLE recuperaciones_password (
    id_recuperacion NUMBER(19) GENERATED ALWAYS AS IDENTITY (START WITH 1 NOCACHE) NOT NULL,
    usuario_id NUMBER(19) NOT NULL,
    token_hash VARCHAR2(64) UNIQUE NOT NULL,
    fecha_creacion TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_expiracion TIMESTAMP WITH TIME ZONE NOT NULL,
    usada NUMBER(1) DEFAULT 0 NOT NULL,
    fecha_uso TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_recuperaciones_password PRIMARY KEY (id_recuperacion),
    CONSTRAINT chk_rec_fechas CHECK (fecha_expiracion > fecha_creacion),
    CONSTRAINT chk_rec_uso CHECK ((usada = 0 AND fecha_uso IS NULL) OR
                                  (usada = 1 AND fecha_uso IS NOT NULL AND fecha_uso >= fecha_creacion AND fecha_uso < fecha_expiracion)),
    CONSTRAINT fk_recuperaciones_password_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id_usuario)
);

CREATE INDEX idx_invitaciones_email_destinatario ON invitaciones (email_destinatario);
CREATE INDEX idx_invitaciones_rol_id ON invitaciones (rol_id);
CREATE INDEX idx_invitaciones_creado_por_usuario_id ON invitaciones (creado_por_usuario_id);
CREATE INDEX idx_recuperaciones_password_usuario_id ON recuperaciones_password (usuario_id);