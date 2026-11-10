ALTER TABLE notices ALTER COLUMN published_by DROP NOT NULL;
ALTER TABLE notices ALTER COLUMN published_at DROP NOT NULL;
ALTER TABLE notices ALTER COLUMN published_at DROP DEFAULT;
ALTER TABLE notices ADD COLUMN state varchar(16) NOT NULL DEFAULT 'PUBLISHED' CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED'));
ALTER TABLE notices ADD COLUMN scope varchar(16) NOT NULL DEFAULT 'SCHOOL' CHECK(scope IN ('SCHOOL','CLASS','DEPARTMENT'));
ALTER TABLE notices ADD COLUMN class_id uuid;
ALTER TABLE notices ADD COLUMN department varchar(20);
ALTER TABLE notices ADD COLUMN created_by uuid;
ALTER TABLE notices ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
UPDATE notices SET created_by=published_by;
ALTER TABLE notices ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE notices ADD CONSTRAINT notice_target_scope CHECK((scope='SCHOOL' AND class_id IS NULL AND department IS NULL) OR (scope='CLASS' AND class_id IS NOT NULL AND department IS NULL) OR (scope='DEPARTMENT' AND class_id IS NOT NULL AND department IN ('SCIENCE','ARTS','COMMERCIAL')));
ALTER TABLE complaints DROP CONSTRAINT complaints_state_check;
UPDATE complaints SET state=CASE WHEN state='OPEN' THEN 'NEW' WHEN state='IN_PROGRESS' THEN 'IN_REVIEW' ELSE state END;
ALTER TABLE complaints ALTER COLUMN state SET DEFAULT 'NEW';
ALTER TABLE complaints ADD CONSTRAINT complaint_state_allowed CHECK(state IN ('NEW','IN_REVIEW','WAITING_FOR_PARENT','RESOLVED','CLOSED'));
ALTER TABLE complaints ADD COLUMN guardian_id uuid;
ALTER TABLE complaints ADD COLUMN owner_id uuid;
ALTER TABLE complaints ADD COLUMN intake_channel varchar(12) NOT NULL DEFAULT 'APP' CHECK(intake_channel IN ('APP','WALK_IN','PHONE'));
ALTER TABLE complaints ADD COLUMN resolved_at timestamptz;
ALTER TABLE complaints ADD COLUMN resolution_text varchar(2000);
CREATE TABLE complaint_history (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES complaints(id),action varchar(40) NOT NULL,actor_id uuid NOT NULL,reason varchar(2000),
 before_state varchar(24),after_state varchar(24),owner_id uuid,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE parent_threads (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,student_id uuid NOT NULL,requester_id uuid NOT NULL,guardian_id uuid NOT NULL,
 target varchar(16) NOT NULL CHECK(target IN ('CLASS_TEACHER','OFFICE')),teacher_id uuid,subject varchar(160) NOT NULL,
 state varchar(12) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','CLOSED')),version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(school_id,id)
);
CREATE TABLE thread_messages (
 id uuid PRIMARY KEY,thread_id uuid NOT NULL REFERENCES parent_threads(id),author_id uuid NOT NULL,body varchar(8000) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE thread_reads (
 thread_id uuid NOT NULL REFERENCES parent_threads(id),account_id uuid NOT NULL,last_read_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(thread_id,account_id)
);
CREATE TABLE communication_attachments (
 school_id uuid NOT NULL,file_id uuid NOT NULL,resource_type varchar(16) NOT NULL CHECK(resource_type IN ('NOTICE','THREAD_MESSAGE','CASE_ENTRY')),
 resource_id uuid NOT NULL,PRIMARY KEY(file_id,resource_type,resource_id)
);
ALTER TABLE private_mail ADD COLUMN lease_token uuid;
ALTER TABLE private_mail ADD COLUMN lease_until timestamptz;
CREATE TABLE notification_dispatch (
 notification_id uuid PRIMARY KEY REFERENCES notifications(id),state varchar(24) NOT NULL DEFAULT 'PENDING',attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),lease_token uuid,lease_until timestamptz
);
CREATE TABLE push_deliveries (
 id uuid PRIMARY KEY,notification_id uuid NOT NULL REFERENCES notifications(id),device_id uuid NOT NULL,token_cipher text NOT NULL,
 state varchar(24) NOT NULL DEFAULT 'QUEUED',attempts integer NOT NULL DEFAULT 0,next_attempt_at timestamptz NOT NULL DEFAULT now(),
 lease_token uuid,lease_until timestamptz,ticket_id varchar(160),error_code varchar(80),created_at timestamptz NOT NULL DEFAULT now(),sent_at timestamptz,
 UNIQUE(notification_id,device_id)
);
