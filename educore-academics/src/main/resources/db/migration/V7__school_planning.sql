ALTER TABLE timetable_plans DROP CONSTRAINT timetable_plans_term_id_effective_from_key;
ALTER TABLE timetable_plans ADD COLUMN revision bigint NOT NULL DEFAULT 1;
ALTER TABLE timetable_plans ADD COLUMN source_plan_id uuid REFERENCES timetable_plans(id);
ALTER TABLE timetable_plans ADD CONSTRAINT timetable_revision_unique UNIQUE(term_id,effective_from,revision);
CREATE TABLE curriculum_templates (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,session_id uuid NOT NULL,level varchar(4) NOT NULL,
 department varchar(20),name varchar(120) NOT NULL,revision integer NOT NULL,state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED')),
 definition jsonb NOT NULL,version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL,published_at timestamptz,
 FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id),UNIQUE(school_id,id),CHECK(level IN ('JSS1','JSS2','JSS3','SSS1','SSS2','SSS3'))
);
CREATE UNIQUE INDEX curriculum_revision_unique ON curriculum_templates(session_id,level,coalesce(department,''),revision);
ALTER TABLE classes ADD COLUMN curriculum_id uuid;
ALTER TABLE classes ADD CONSTRAINT class_curriculum_school_fk FOREIGN KEY(school_id,curriculum_id) REFERENCES curriculum_templates(school_id,id);
CREATE TABLE class_curricula (
 school_id uuid NOT NULL,class_id uuid NOT NULL,curriculum_id uuid NOT NULL,department varchar(20) NOT NULL DEFAULT '',
 PRIMARY KEY(class_id,department),FOREIGN KEY(school_id,class_id) REFERENCES classes(school_id,id),
 FOREIGN KEY(school_id,curriculum_id) REFERENCES curriculum_templates(school_id,id)
);
CREATE TABLE calendar_plans (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,session_id uuid NOT NULL,state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED')),
 changes jsonb NOT NULL,version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL,published_at timestamptz,
 FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id),UNIQUE(school_id,id)
);
CREATE TABLE activity_supervisors (
 school_id uuid NOT NULL,term_id uuid NOT NULL,activity varchar(16) NOT NULL CHECK(activity IN ('WEDNESDAY_PT','FRIDAY_SPORTS')),
 teacher_id uuid NOT NULL,version bigint NOT NULL DEFAULT 1,
 PRIMARY KEY(term_id,activity,teacher_id),FOREIGN KEY(school_id,term_id) REFERENCES terms(school_id,id),
 FOREIGN KEY(school_id,teacher_id) REFERENCES staff(school_id,id)
);
CREATE TABLE student_choice_versions (
 school_id uuid NOT NULL,student_id uuid NOT NULL,session_id uuid NOT NULL,version bigint NOT NULL DEFAULT 1,
 PRIMARY KEY(student_id,session_id),FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),
 FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id)
);
ALTER TABLE exam_events ADD COLUMN department varchar(20);
ALTER TABLE exam_schedules ADD COLUMN revision bigint NOT NULL DEFAULT 1;
CREATE UNIQUE INDEX exam_schedule_revision ON exam_schedules(term_id,revision);
CREATE TABLE external_exam_revisions (
 record_id uuid NOT NULL REFERENCES external_exam_records(id),revision bigint NOT NULL,snapshot jsonb NOT NULL,
 recorded_by uuid NOT NULL,recorded_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(record_id,revision)
);
ALTER TABLE students ADD COLUMN admission_type varchar(24) NOT NULL DEFAULT 'LEGACY_MIGRATION' CHECK(admission_type IN ('FRESH','TRANSFER_IN','LEGACY_MIGRATION'));
ALTER TABLE students ADD COLUMN legacy_reference varchar(160);
CREATE INDEX class_enrolment_dates ON enrolments(class_id,starts_on,ends_on);
CREATE INDEX published_school_reports ON report_versions(school_id,student_id,term_id,revision DESC);
