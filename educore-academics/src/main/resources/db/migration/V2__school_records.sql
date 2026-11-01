CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE schools (
 id uuid PRIMARY KEY,code varchar(30) NOT NULL UNIQUE,name varchar(160) NOT NULL,
 timezone varchar(50) NOT NULL DEFAULT 'Africa/Lagos',currency char(3) NOT NULL DEFAULT 'NGN',
 wednesday_pt boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE academic_sessions (
 id uuid PRIMARY KEY,school_id uuid NOT NULL REFERENCES schools(id),name varchar(40) NOT NULL,
 starts_on date NOT NULL,ends_on date NOT NULL,version bigint NOT NULL DEFAULT 1,
 CHECK(starts_on<ends_on),UNIQUE(school_id,name),UNIQUE(school_id,id),
 EXCLUDE USING gist(school_id WITH =,daterange(starts_on,ends_on,'[]') WITH &&)
);
CREATE TABLE terms (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,session_id uuid NOT NULL,number integer NOT NULL CHECK(number BETWEEN 1 AND 3),
 starts_on date NOT NULL,ends_on date NOT NULL,version bigint NOT NULL DEFAULT 1,
 CHECK(starts_on<ends_on),FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id),
 UNIQUE(session_id,number),UNIQUE(school_id,id),EXCLUDE USING gist(session_id WITH =,daterange(starts_on,ends_on,'[]') WITH &&)
);
CREATE TABLE calendar_exceptions (
 id uuid PRIMARY KEY,school_id uuid NOT NULL REFERENCES schools(id),on_date date NOT NULL,title varchar(120) NOT NULL,
 teaching_day boolean NOT NULL DEFAULT false,reason varchar(500) NOT NULL,UNIQUE(school_id,on_date)
);
CREATE TABLE staff (
 id uuid PRIMARY KEY,school_id uuid NOT NULL REFERENCES schools(id),full_name varchar(120) NOT NULL,phone varchar(20) NOT NULL,
 email varchar(254),active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 1,UNIQUE(school_id,id)
);
CREATE TABLE classes (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,session_id uuid NOT NULL,level varchar(4) NOT NULL CHECK(level IN ('JSS1','JSS2','JSS3','SSS1','SSS2','SSS3')),
 arm varchar(10) NOT NULL,attendance_teacher_id uuid,version bigint NOT NULL DEFAULT 1,
 FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id),FOREIGN KEY(school_id,attendance_teacher_id) REFERENCES staff(school_id,id),
 UNIQUE(school_id,id),UNIQUE(session_id,level,arm)
);
CREATE TABLE subjects (
 id uuid PRIMARY KEY,school_id uuid NOT NULL REFERENCES schools(id),code varchar(20) NOT NULL,name varchar(100) NOT NULL,
 core boolean NOT NULL DEFAULT false,UNIQUE(school_id,id),UNIQUE(school_id,code)
);
CREATE TABLE staff_qualifications (
 school_id uuid NOT NULL,staff_id uuid NOT NULL,subject_id uuid NOT NULL,level varchar(4) NOT NULL,
 FOREIGN KEY(school_id,staff_id) REFERENCES staff(school_id,id),FOREIGN KEY(school_id,subject_id) REFERENCES subjects(school_id,id),
 PRIMARY KEY(staff_id,subject_id,level),CHECK(level IN ('JSS1','JSS2','JSS3','SSS1','SSS2','SSS3'))
);
CREATE TABLE teaching_assignments (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,class_id uuid NOT NULL,subject_id uuid NOT NULL,teacher_id uuid NOT NULL,
 department varchar(20),weekly_periods integer NOT NULL CHECK(weekly_periods BETWEEN 1 AND 10),version bigint NOT NULL DEFAULT 1,
 FOREIGN KEY(school_id,class_id) REFERENCES classes(school_id,id),FOREIGN KEY(school_id,subject_id) REFERENCES subjects(school_id,id),
 FOREIGN KEY(school_id,teacher_id) REFERENCES staff(school_id,id),UNIQUE(school_id,id)
);
CREATE UNIQUE INDEX one_teaching_assignment ON teaching_assignments(class_id,subject_id,coalesce(department,''));
CREATE TABLE guardians (
 id uuid PRIMARY KEY,school_id uuid NOT NULL REFERENCES schools(id),full_name varchar(120) NOT NULL,phone varchar(20) NOT NULL,
 email varchar(254),existing_login_id varchar(80),relationship_verified boolean NOT NULL,verification_reference varchar(160) NOT NULL,
 version bigint NOT NULL DEFAULT 1,UNIQUE(school_id,id)
);
CREATE TABLE students (
 id uuid PRIMARY KEY,school_id uuid NOT NULL REFERENCES schools(id),admission_number varchar(40) NOT NULL,
 full_name varchar(120) NOT NULL,date_of_birth date NOT NULL,sex varchar(16) NOT NULL CHECK(sex IN ('MALE','FEMALE')),
 metadata jsonb NOT NULL DEFAULT '{}',status varchar(20) NOT NULL DEFAULT 'ACTIVE',version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(school_id,id),UNIQUE(school_id,admission_number)
);
CREATE TABLE enrolments (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,student_id uuid NOT NULL,session_id uuid NOT NULL,class_id uuid NOT NULL,
 department varchar(20),starts_on date NOT NULL,ends_on date,version bigint NOT NULL DEFAULT 1,
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),FOREIGN KEY(school_id,session_id) REFERENCES academic_sessions(school_id,id),
 FOREIGN KEY(school_id,class_id) REFERENCES classes(school_id,id),CHECK(ends_on IS NULL OR ends_on>=starts_on),
 EXCLUDE USING gist(student_id WITH =,session_id WITH =,daterange(starts_on,ends_on,'[]') WITH &&)
);
CREATE TABLE guardian_links (
 school_id uuid NOT NULL,student_id uuid NOT NULL,guardian_id uuid NOT NULL,relationship varchar(40) NOT NULL,active boolean NOT NULL DEFAULT true,
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),FOREIGN KEY(school_id,guardian_id) REFERENCES guardians(school_id,id),
 PRIMARY KEY(school_id,student_id,guardian_id)
);
CREATE TABLE enrolment_events(student_id uuid PRIMARY KEY REFERENCES students(id),event_id uuid NOT NULL REFERENCES outbox(id));
CREATE TABLE metadata_versions (
 school_id uuid NOT NULL REFERENCES schools(id),version bigint NOT NULL,published_at timestamptz NOT NULL DEFAULT now(),
 definition jsonb NOT NULL,PRIMARY KEY(school_id,version)
);
CREATE TABLE timetable_plans (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,term_id uuid NOT NULL,status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
 effective_from date NOT NULL,wednesday_pt boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 1,published_at timestamptz,
 FOREIGN KEY(school_id,term_id) REFERENCES terms(school_id,id),UNIQUE(school_id,id),UNIQUE(term_id,effective_from)
);
CREATE TABLE lessons (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,plan_id uuid NOT NULL,assignment_id uuid NOT NULL,
 class_id uuid NOT NULL,teacher_id uuid NOT NULL,subject_id uuid NOT NULL,department varchar(20),room varchar(40),
 weekday integer NOT NULL CHECK(weekday BETWEEN 1 AND 5),start_min integer NOT NULL,end_min integer NOT NULL,version bigint NOT NULL DEFAULT 1,
 CHECK(start_min>=480 AND end_min<=780 AND end_min-start_min IN (30,60) AND start_min%30=0),
 CHECK(end_min<=690 OR start_min>=720),CHECK(weekday<>5 OR end_min<=720),
 FOREIGN KEY(school_id,plan_id) REFERENCES timetable_plans(school_id,id),FOREIGN KEY(school_id,assignment_id) REFERENCES teaching_assignments(school_id,id),
 EXCLUDE USING gist(plan_id WITH =,teacher_id WITH =,weekday WITH =,int4range(start_min,end_min,'[)') WITH &&),
 EXCLUDE USING gist(plan_id WITH =,room WITH =,weekday WITH =,int4range(start_min,end_min,'[)') WITH &&)
);
CREATE TABLE attendance_registers (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,class_id uuid NOT NULL,on_date date NOT NULL,state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','SUBMITTED')),
 submitted_by uuid,submitted_at timestamptz,version bigint NOT NULL DEFAULT 1,
 FOREIGN KEY(school_id,class_id) REFERENCES classes(school_id,id),UNIQUE(class_id,on_date),UNIQUE(school_id,id)
);
CREATE TABLE attendance_rows (
 register_id uuid NOT NULL REFERENCES attendance_registers(id),student_id uuid NOT NULL REFERENCES students(id),
 status varchar(10) CHECK(status IN ('PRESENT','ABSENT','LATE','EXCUSED')),reason varchar(500),PRIMARY KEY(register_id,student_id)
);
CREATE TABLE attendance_alerts (
 register_id uuid NOT NULL REFERENCES attendance_registers(id),student_id uuid NOT NULL REFERENCES students(id),
 event_id uuid NOT NULL REFERENCES outbox(id),PRIMARY KEY(register_id,student_id)
);
