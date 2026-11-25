ALTER TABLE operaciones_traslados MODIFY (actor_id NULL);

ALTER TABLE operaciones_traslados ADD (
    estado_operacion VARCHAR2(20 CHAR),
    solicitud_hash VARCHAR2(64 CHAR),
    respuesta_json CLOB,
    expira_en TIMESTAMP WITH TIME ZONE
);
