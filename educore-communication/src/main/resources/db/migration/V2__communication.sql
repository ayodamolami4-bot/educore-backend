CREATE TABLE notifications (
 id uuid PRIMARY KEY,account_id uuid NOT NULL,school_id uuid NOT NULL,child_id uuid,
 category varchar(12) NOT NULL CHECK(category IN ('SCHOOL','LEARNING','FEES','MESSAGES','CASES')),
 title varchar(160) NOT NULL,safe_preview varchar(240) NOT NULL,target jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),read_at timestamptz,version bigint NOT NULL DEFAULT 1,event_id uuid NOT NULL,
 UNIQUE(event_id,account_id)
);
CREATE INDEX notification_owner ON notifications(account_id,school_id,id);
CREATE TABLE private_mail (
 id uuid PRIMARY KEY,event_id uuid NOT NULL UNIQUE,payload_cipher text NOT NULL,
 state varchar(20) NOT NULL DEFAULT 'QUEUED',attempts integer NOT NULL DEFAULT 0,next_attempt_at timestamptz NOT NULL DEFAULT now(),
 last_error_code varchar(80),created_at timestamptz NOT NULL DEFAULT now(),sent_at timestamptz
);
CREATE TABLE notices (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,title varchar(160) NOT NULL,body varchar(8000) NOT NULL,
 published_by uuid NOT NULL,published_at timestamptz NOT NULL DEFAULT now(),version bigint NOT NULL DEFAULT 1
);
CREATE TABLE complaints (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,student_id uuid NOT NULL,requester_id uuid NOT NULL,category varchar(24) NOT NULL,
 subject varchar(160) NOT NULL,state varchar(16) NOT NULL DEFAULT 'OPEN',first_response_due_at timestamptz,
 first_response_at timestamptz,closed_at timestamptz,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(category IN ('FEES','ATTENDANCE','ACADEMIC_RECORDS','STAFF_CONDUCT','GENERAL')),CHECK(state IN ('OPEN','IN_PROGRESS','CLOSED'))
);
CREATE TABLE case_entries (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES complaints(id),author_id uuid NOT NULL,
 visibility varchar(12) NOT NULL CHECK(visibility IN ('PUBLIC','INTERNAL')),body varchar(8000) NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
