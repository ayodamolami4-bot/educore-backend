ALTER TABLE staff ADD COLUMN staff_number varchar(40);
ALTER TABLE staff ADD COLUMN staff_role varchar(24) NOT NULL DEFAULT 'TEACHER';
ALTER TABLE staff ADD COLUMN joined_on date;
ALTER TABLE staff ADD COLUMN access_event_id uuid REFERENCES outbox(id);
ALTER TABLE staff ADD COLUMN existing_login_id varchar(80);
ALTER TABLE staff ADD COLUMN relationship_verified boolean NOT NULL DEFAULT false;
UPDATE staff SET staff_role='PRINCIPAL' WHERE NOT EXISTS(SELECT 1 FROM staff_qualifications q WHERE q.staff_id=staff.id);
UPDATE staff SET staff_number='ST-'||upper(substr(replace(id::text,'-',''),1,12));
ALTER TABLE staff ALTER COLUMN staff_number SET NOT NULL;
ALTER TABLE staff ALTER COLUMN staff_number SET DEFAULT 'ST-'||upper(replace(gen_random_uuid()::text,'-',''));
ALTER TABLE staff ADD CONSTRAINT staff_number_unique UNIQUE(school_id,staff_number);
ALTER TABLE staff ADD CONSTRAINT staff_role_allowed CHECK(staff_role IN ('PRINCIPAL','SCHOOL_OFFICE','ACCOUNTS','TEACHER'));
ALTER TABLE guardian_links ADD COLUMN learning_access boolean NOT NULL DEFAULT true;
ALTER TABLE guardian_links ADD COLUMN payer_access boolean NOT NULL DEFAULT true;
ALTER TABLE guardian_links ADD COLUMN primary_contact boolean NOT NULL DEFAULT false;
ALTER TABLE guardian_links ADD COLUMN version bigint NOT NULL DEFAULT 1;
ALTER TABLE classes ADD COLUMN capacity integer CHECK(capacity BETWEEN 1 AND 200);
ALTER TABLE classes ADD COLUMN room varchar(40);
ALTER TABLE schools ADD COLUMN contact_phone varchar(20);
ALTER TABLE schools ADD COLUMN contact_email varchar(254);
ALTER TABLE schools ADD COLUMN address varchar(240);
ALTER TABLE schools ADD COLUMN calendar_revision bigint NOT NULL DEFAULT 0;
CREATE TABLE student_number_sequences (
 school_id uuid NOT NULL,session_id uuid NOT NULL,next_number bigint NOT NULL,
 PRIMARY KEY(school_id,session_id),FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id)
);
CREATE TABLE student_subject_choices (
 school_id uuid NOT NULL,student_id uuid NOT NULL,session_id uuid NOT NULL,subject_id uuid NOT NULL,
 PRIMARY KEY(student_id,session_id,subject_id),
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),
 FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id),
 FOREIGN KEY(school_id,subject_id) REFERENCES subjects(school_id,id)
);
CREATE TABLE teacher_availability (
 school_id uuid NOT NULL,teacher_id uuid NOT NULL,weekday integer NOT NULL CHECK(weekday BETWEEN 1 AND 5),
 start_min integer NOT NULL,end_min integer NOT NULL,reason varchar(500) NOT NULL,
 PRIMARY KEY(teacher_id,weekday,start_min),CHECK(start_min>=480 AND end_min<=780 AND start_min<end_min),
 FOREIGN KEY(school_id,teacher_id) REFERENCES staff(school_id,id)
);
CREATE TABLE external_exam_records (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,student_id uuid NOT NULL,board varchar(24) NOT NULL,
 exam_year integer NOT NULL CHECK(exam_year BETWEEN 2000 AND 2100),candidate_reference varchar(100) NOT NULL,
 issued_result jsonb NOT NULL,recorded_by uuid NOT NULL,reason varchar(500) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),version bigint NOT NULL DEFAULT 1,
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),UNIQUE(school_id,student_id,board,exam_year)
);
