ALTER TABLE provisioning_events ADD COLUMN superseded_by uuid REFERENCES outbox(id);
