CREATE TABLE provisioning_events (
 event_id uuid PRIMARY KEY REFERENCES outbox(id),school_id uuid NOT NULL REFERENCES schools(id),
 version bigint NOT NULL DEFAULT 1
);
CREATE TABLE provisioning_targets (
 event_id uuid NOT NULL REFERENCES provisioning_events(event_id),subject_type varchar(16) NOT NULL,
 subject_id uuid NOT NULL,PRIMARY KEY(event_id,subject_type,subject_id),
 CHECK(subject_type IN ('STAFF','STUDENT','GUARDIAN'))
);
CREATE INDEX provisioning_target_lookup ON provisioning_targets(subject_type,subject_id,event_id);
