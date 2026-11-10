ALTER TABLE teaching_assignments ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE teaching_assignments ADD COLUMN required boolean NOT NULL DEFAULT true;
CREATE TABLE school_files (
 id uuid PRIMARY KEY,school_id uuid NOT NULL REFERENCES schools(id),owner_id uuid NOT NULL,student_id uuid,class_id uuid,
 purpose varchar(24) NOT NULL CHECK(purpose IN ('STUDENT_DOCUMENT','ASSIGNMENT','SUBMISSION','COMMUNICATION','FINANCE_EVIDENCE','SCHOOL_ASSET')),
 filename varchar(120) NOT NULL,media_type varchar(60) NOT NULL,expected_size integer NOT NULL CHECK(expected_size BETWEEN 1 AND 10485760),
 sha256 char(64) NOT NULL,state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','READY','ARCHIVED','EXPIRED')),
 family_visible boolean NOT NULL DEFAULT false,payload_cipher bytea,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),FOREIGN KEY(school_id,class_id) REFERENCES classes(school_id,id),UNIQUE(school_id,id)
);
CREATE TABLE learning_assignments (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,teaching_assignment_id uuid NOT NULL,term_id uuid NOT NULL,
 title varchar(160) NOT NULL,instructions varchar(8000) NOT NULL,due_at timestamptz NOT NULL,
 maximum_score numeric(6,1) NOT NULL CHECK(maximum_score>0 AND maximum_score<=1000),
 state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED','CLOSED','ARCHIVED')),
 created_by uuid NOT NULL,published_at timestamptz,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,teaching_assignment_id) REFERENCES teaching_assignments(school_id,id),
 FOREIGN KEY(school_id,term_id) REFERENCES terms(school_id,id),UNIQUE(school_id,id)
);
CREATE TABLE assignment_submissions (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,assignment_id uuid NOT NULL,student_id uuid NOT NULL,
 response_text varchar(8000) NOT NULL DEFAULT '',state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','SUBMITTED','RETURNED','GRADED')),
 version bigint NOT NULL DEFAULT 1,submitted_at timestamptz,extra_deadline timestamptz,
 score numeric(6,1),feedback varchar(3000),feedback_by uuid,feedback_at timestamptz,
 FOREIGN KEY(school_id,assignment_id) REFERENCES learning_assignments(school_id,id),
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),UNIQUE(assignment_id,student_id),UNIQUE(school_id,id)
);
CREATE TABLE file_links (
 file_id uuid NOT NULL REFERENCES school_files(id),resource_type varchar(24) NOT NULL CHECK(resource_type IN ('ASSIGNMENT','SUBMISSION','STUDENT_DOCUMENT','SCHOOL_ASSET')),
 resource_id uuid NOT NULL,created_by uuid NOT NULL,active boolean NOT NULL DEFAULT true,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(file_id,resource_type,resource_id)
);
CREATE TABLE submission_revisions (
 id uuid PRIMARY KEY,submission_id uuid NOT NULL REFERENCES assignment_submissions(id),revision integer NOT NULL,
 snapshot jsonb NOT NULL,submitted_by uuid NOT NULL,submitted_at timestamptz NOT NULL DEFAULT now(),UNIQUE(submission_id,revision)
);
CREATE TABLE submission_reviews (
 id uuid PRIMARY KEY,submission_id uuid NOT NULL REFERENCES assignment_submissions(id),submission_revision integer NOT NULL,
 action varchar(16) NOT NULL,score numeric(6,1),feedback varchar(3000) NOT NULL,reason varchar(500),
 author_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
