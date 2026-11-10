CREATE TABLE assessment_cycles (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,term_id uuid NOT NULL,class_id uuid NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','OPEN','REVIEW','PUBLISHED')),
 policy jsonb NOT NULL,opens_at timestamptz NOT NULL,closes_at timestamptz NOT NULL,
 revision integer NOT NULL DEFAULT 0,version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz,
 FOREIGN KEY(school_id,term_id) REFERENCES terms(school_id,id),FOREIGN KEY(school_id,class_id) REFERENCES classes(school_id,id),
 CHECK(opens_at<closes_at),UNIQUE(term_id,class_id),UNIQUE(school_id,id)
);
CREATE TABLE assessment_roster (
 cycle_id uuid NOT NULL REFERENCES assessment_cycles(id),student_id uuid NOT NULL REFERENCES students(id),
 enrolment_id uuid NOT NULL REFERENCES enrolments(id),department varchar(20),PRIMARY KEY(cycle_id,student_id)
);
CREATE TABLE assessment_sheets (
 cycle_id uuid NOT NULL REFERENCES assessment_cycles(id),assignment_id uuid NOT NULL REFERENCES teaching_assignments(id),
 subject_id uuid NOT NULL REFERENCES subjects(id),teacher_id uuid NOT NULL REFERENCES staff(id),department varchar(20),
 state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','RETURNED','SUBMITTED')),
 version bigint NOT NULL DEFAULT 1,submitted_by uuid,submitted_at timestamptz,return_reason varchar(500),
 PRIMARY KEY(cycle_id,assignment_id)
);
CREATE TABLE assessment_marks (
 cycle_id uuid NOT NULL,assignment_id uuid NOT NULL,student_id uuid NOT NULL,
 ca1 numeric(4,1) CHECK(ca1 BETWEEN 0 AND 20),ca2 numeric(4,1) CHECK(ca2 BETWEEN 0 AND 20),exam numeric(4,1) CHECK(exam BETWEEN 0 AND 60),
 exam_state varchar(20) NOT NULL DEFAULT 'COMPLETE' CHECK(exam_state IN ('COMPLETE','MAKEUP_PENDING','ABSENT_ZERO')),
 note varchar(500),marked_by uuid,updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(cycle_id,assignment_id,student_id),
 FOREIGN KEY(cycle_id,assignment_id) REFERENCES assessment_sheets(cycle_id,assignment_id),
 FOREIGN KEY(cycle_id,student_id) REFERENCES assessment_roster(cycle_id,student_id)
);
CREATE TABLE class_remarks (
 cycle_id uuid NOT NULL,student_id uuid NOT NULL,remark varchar(1500) NOT NULL,
 conduct integer NOT NULL CHECK(conduct BETWEEN 1 AND 5),punctuality integer NOT NULL CHECK(punctuality BETWEEN 1 AND 5),
 cooperation integer NOT NULL CHECK(cooperation BETWEEN 1 AND 5),handwriting integer NOT NULL CHECK(handwriting BETWEEN 1 AND 5),
 practical_skills integer NOT NULL CHECK(practical_skills BETWEEN 1 AND 5),version bigint NOT NULL DEFAULT 1,author_id uuid NOT NULL,
 PRIMARY KEY(cycle_id,student_id),FOREIGN KEY(cycle_id,student_id) REFERENCES assessment_roster(cycle_id,student_id)
);
CREATE TABLE report_versions (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,cycle_id uuid NOT NULL,term_id uuid NOT NULL,student_id uuid NOT NULL,revision integer NOT NULL,
 snapshot jsonb NOT NULL,published_by uuid NOT NULL,published_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,cycle_id) REFERENCES assessment_cycles(school_id,id),UNIQUE(term_id,student_id,revision),UNIQUE(school_id,id)
);
CREATE TABLE exam_schedules (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,term_id uuid NOT NULL,state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED')),
 version bigint NOT NULL DEFAULT 1,published_at timestamptz,FOREIGN KEY(school_id,term_id) REFERENCES terms(school_id,id),UNIQUE(school_id,id)
);
CREATE TABLE exam_events (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,schedule_id uuid NOT NULL,class_id uuid NOT NULL,subject_id uuid NOT NULL,invigilator_id uuid NOT NULL,
 on_date date NOT NULL,start_min integer NOT NULL,end_min integer NOT NULL,room varchar(40) NOT NULL,exception_reason varchar(500),
 version bigint NOT NULL DEFAULT 1,CHECK(start_min>=0 AND end_min<=1440 AND start_min<end_min),
 FOREIGN KEY(school_id,schedule_id) REFERENCES exam_schedules(school_id,id),FOREIGN KEY(school_id,class_id) REFERENCES classes(school_id,id),
 FOREIGN KEY(school_id,subject_id) REFERENCES subjects(school_id,id),FOREIGN KEY(school_id,invigilator_id) REFERENCES staff(school_id,id),
 EXCLUDE USING gist(schedule_id WITH =,class_id WITH =,on_date WITH =,int4range(start_min,end_min,'[)') WITH &&),
 EXCLUDE USING gist(schedule_id WITH =,invigilator_id WITH =,on_date WITH =,int4range(start_min,end_min,'[)') WITH &&),
 EXCLUDE USING gist(schedule_id WITH =,room WITH =,on_date WITH =,int4range(start_min,end_min,'[)') WITH &&)
);
