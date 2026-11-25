CREATE TABLE decisiones_autorizacion (
    decision_id VARCHAR2(121 CHAR) PRIMARY KEY,
    actor_id NUMBER(19) NOT NULL,
    sesion_id VARCHAR2(36 CHAR) NOT NULL,
    solicitud_hash VARCHAR2(64 CHAR) NOT NULL,
    jwt_expira_en TIMESTAMP WITH TIME ZONE NOT NULL,
    sesion_expira_en TIMESTAMP NOT NULL,
    estado VARCHAR2(20 CHAR) NOT NULL,
    CONSTRAINT chk_decision_autorizacion_estado CHECK (estado IN ('PREPARADA', 'AUTORIZADA'))
);
