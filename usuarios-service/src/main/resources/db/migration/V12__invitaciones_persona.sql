ALTER TABLE invitaciones ADD persona_id NUMBER(19);
ALTER TABLE invitaciones ADD CONSTRAINT fk_invitaciones_persona FOREIGN KEY (persona_id) REFERENCES personas(id_persona);
CREATE INDEX idx_invitaciones_persona_id ON invitaciones (persona_id);
