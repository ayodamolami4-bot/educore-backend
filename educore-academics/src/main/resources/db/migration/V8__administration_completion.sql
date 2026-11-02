ALTER TABLE teaching_assignments ADD COLUMN choice_group varchar(30);
CREATE TABLE academic_imports (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,session_id uuid NOT NULL,kind varchar(16) NOT NULL CHECK(kind IN ('STUDENTS','TEACHERS')),
 state varchar(16) NOT NULL DEFAULT 'PREVIEW' CHECK(state IN ('PREVIEW','QUEUED','PROCESSING','COMPLETE','PARTIAL','PAUSED')),
 metadata_version bigint NOT NULL,actor_cipher text NOT NULL,review_reason varchar(500),version bigint NOT NULL DEFAULT 1,
 lease_token uuid,lease_until timestamptz,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id),UNIQUE(school_id,id)
);
CREATE TABLE academic_import_rows (
 id uuid PRIMARY KEY,job_id uuid NOT NULL REFERENCES academic_imports(id),row_number integer NOT NULL,row_reference varchar(80) NOT NULL,
 source_hash char(64) NOT NULL,input_cipher text NOT NULL,result_cipher text,state varchar(16) NOT NULL CHECK(state IN ('READY','INVALID','SUCCEEDED','FAILED')),
 error_code varchar(80),error_message varchar(500),warnings jsonb NOT NULL DEFAULT '[]',version bigint NOT NULL DEFAULT 1,
 UNIQUE(job_id,row_reference),UNIQUE(job_id,row_number)
);
CREATE TABLE academic_import_outcomes (
 school_id uuid NOT NULL,kind varchar(16) NOT NULL,row_reference varchar(80) NOT NULL,source_hash char(64) NOT NULL,result_cipher text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(school_id,kind,row_reference)
);
CREATE TABLE import_guardian_groups (
 job_id uuid NOT NULL REFERENCES academic_imports(id),guardian_reference varchar(80) NOT NULL,guardian_id uuid NOT NULL REFERENCES guardians(id),
 source_hash char(64) NOT NULL,PRIMARY KEY(job_id,guardian_reference)
);
CREATE INDEX academic_import_due ON academic_imports(created_at) WHERE state IN ('QUEUED','PROCESSING');
