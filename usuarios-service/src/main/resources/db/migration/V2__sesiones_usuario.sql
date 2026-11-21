CREATE TABLE sesiones_usuario (
    sesion_id VARCHAR2(36 CHAR) PRIMARY KEY,
    usuario_id NUMBER(19) NOT NULL,
    fecha_inicio TIMESTAMP NOT NULL,
    fecha_expiracion TIMESTAMP NOT NULL,
    activa NUMBER(1) DEFAULT 1 NOT NULL,
    CONSTRAINT fk_sesion_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuarios(id_usuario)
);

CREATE INDEX idx_sesiones_usuario_usuario ON sesiones_usuario(usuario_id);
