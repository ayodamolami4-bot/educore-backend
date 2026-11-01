CREATE TABLE accounts (
 id uuid PRIMARY KEY, login_id varchar(80) NOT NULL UNIQUE, display_name varchar(120) NOT NULL,
 phone varchar(20) NOT NULL, password_hash text, recovery_hash char(64), verified_email varchar(254),
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','ACTIVE','DISABLED')), version bigint NOT NULL DEFAULT 1,
 push_enabled boolean NOT NULL DEFAULT true, email_optional_enabled boolean NOT NULL DEFAULT true,
 categories jsonb NOT NULL DEFAULT '["SCHOOL","LEARNING","FEES","MESSAGES","CASES"]', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX account_verified_email ON accounts(lower(verified_email)) WHERE verified_email IS NOT NULL;
CREATE TABLE memberships (
 id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES accounts(id), school_id uuid NOT NULL,school_name varchar(160) NOT NULL,
 role varchar(24) NOT NULL CHECK(role IN ('PRINCIPAL','SCHOOL_OFFICE','ACCOUNTS','TEACHER','STUDENT','PARENT')),
 child_id uuid, child_name varchar(120), subject_id uuid, learning_access boolean NOT NULL DEFAULT true,payer_access boolean NOT NULL DEFAULT false,
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX membership_identity ON memberships(account_id,school_id,role,coalesce(child_id,'00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX membership_child ON memberships(school_id,child_id) WHERE active;
CREATE TABLE sessions (
 id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES accounts(id),installation_id varchar(100) NOT NULL,device_name varchar(100) NOT NULL,
 access_hash char(64) NOT NULL UNIQUE,access_expires_at timestamptz NOT NULL,refresh_hash char(64) NOT NULL UNIQUE,
 refresh_expires_at timestamptz NOT NULL,revoked_at timestamptz,context_id uuid REFERENCES memberships(id),
 created_at timestamptz NOT NULL DEFAULT now(),last_used_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE spent_refresh_tokens(token_hash char(64) PRIMARY KEY,session_id uuid NOT NULL REFERENCES sessions(id),used_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE proofs (
 id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES accounts(id),session_id uuid REFERENCES sessions(id),kind varchar(32) NOT NULL,
 token_hash char(64) NOT NULL UNIQUE,payload_cipher text,expires_at timestamptz NOT NULL,consumed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE provisioned_subjects (
 school_id uuid NOT NULL,subject_type varchar(16) NOT NULL,subject_id uuid NOT NULL,account_id uuid NOT NULL REFERENCES accounts(id),
 PRIMARY KEY(school_id,subject_type,subject_id)
);
CREATE TABLE push_devices (
 id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES accounts(id),session_id uuid NOT NULL REFERENCES sessions(id),
 installation_id varchar(100) NOT NULL,platform varchar(10) NOT NULL CHECK(platform IN ('ANDROID','IOS')),token_cipher text NOT NULL,
 enabled boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 1,UNIQUE(account_id,installation_id)
);
CREATE TABLE rate_buckets(bucket_key char(64) PRIMARY KEY,window_start timestamptz NOT NULL,attempts integer NOT NULL);
