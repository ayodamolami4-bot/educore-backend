ALTER TABLE notifications ADD COLUMN delivery_version bigint NOT NULL DEFAULT 1;
CREATE TABLE delivery_retry_history (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,notification_id uuid NOT NULL REFERENCES notifications(id),
 channel varchar(16) NOT NULL,delivery_id uuid,actor_id uuid NOT NULL,reason varchar(500) NOT NULL,
 previous_state varchar(24) NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX delivery_retry_notification ON delivery_retry_history(notification_id,created_at,id);
