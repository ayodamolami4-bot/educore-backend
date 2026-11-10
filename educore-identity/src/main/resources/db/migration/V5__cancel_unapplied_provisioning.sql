CREATE TABLE cancelled_provisioning_events (
 event_id uuid PRIMARY KEY,school_id uuid NOT NULL,cancelled_at timestamptz NOT NULL DEFAULT now()
);
