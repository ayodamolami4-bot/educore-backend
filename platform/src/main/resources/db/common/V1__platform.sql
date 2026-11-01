CREATE TABLE command_results (
 command_key text PRIMARY KEY, request_hash char(64) NOT NULL, response_cipher text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE audit_entries (
 id uuid PRIMARY KEY, school_id uuid, actor_id uuid NOT NULL, action varchar(80) NOT NULL,
 resource_type varchar(80) NOT NULL, resource_id uuid NOT NULL, reason text, details jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_school_time ON audit_entries(school_id,created_at,id);
CREATE TABLE outbox (
 id uuid PRIMARY KEY, destination varchar(32) NOT NULL, kind varchar(80) NOT NULL, payload_cipher text NOT NULL,
 attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(),
 delivered_at timestamptz, lease_until timestamptz, created_at timestamptz NOT NULL DEFAULT now(), last_error_code varchar(80)
);
CREATE INDEX outbox_pending ON outbox(next_attempt_at) WHERE delivered_at IS NULL;
CREATE TABLE inbox (event_id uuid PRIMARY KEY, kind varchar(80) NOT NULL, received_at timestamptz NOT NULL DEFAULT now());
