ALTER TABLE school_files ADD COLUMN staff_id uuid;
ALTER TABLE school_files ADD COLUMN owner_role varchar(24);
ALTER TABLE school_files ADD CONSTRAINT school_file_staff_fk FOREIGN KEY(school_id,staff_id) REFERENCES staff(school_id,id);
ALTER TABLE school_files DROP CONSTRAINT school_files_purpose_check;
ALTER TABLE school_files ADD CONSTRAINT school_files_purpose_check CHECK(purpose IN ('STUDENT_DOCUMENT','ASSIGNMENT','SUBMISSION','COMMUNICATION','FINANCE_EVIDENCE','SCHOOL_ASSET','MATERIAL','STAFF_DOCUMENT','PHYSICAL_EVIDENCE'));
ALTER TABLE file_links DROP CONSTRAINT file_links_resource_type_check;
ALTER TABLE file_links ADD CONSTRAINT file_links_resource_type_check CHECK(resource_type IN ('ASSIGNMENT','SUBMISSION','STUDENT_DOCUMENT','SCHOOL_ASSET','MATERIAL','SCHOOL_DOCUMENT'));
CREATE TABLE learning_materials (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,teaching_assignment_id uuid NOT NULL,term_id uuid NOT NULL,
 draft jsonb,state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED')),
 published_snapshot jsonb,published_revision integer NOT NULL DEFAULT 0,published_at timestamptz,
 required boolean NOT NULL,department varchar(20),version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,teaching_assignment_id) REFERENCES teaching_assignments(school_id,id),
 FOREIGN KEY(school_id,term_id) REFERENCES terms(school_id,id),UNIQUE(school_id,id)
);
CREATE TABLE material_revisions (
 material_id uuid NOT NULL REFERENCES learning_materials(id),revision integer NOT NULL,snapshot jsonb NOT NULL,
 published_by uuid NOT NULL,published_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(material_id,revision)
);
CREATE TABLE school_documents (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,student_id uuid,staff_id uuid,kind varchar(24) NOT NULL CHECK(kind IN ('ADMISSION','HISTORICAL_REPORT','STAFF_CREDENTIAL')),
 draft jsonb,state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED')),
 published_snapshot jsonb,published_revision integer NOT NULL DEFAULT 0,published_at timestamptz,version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),FOREIGN KEY(school_id,staff_id) REFERENCES staff(school_id,id),UNIQUE(school_id,id),
 CHECK((student_id IS NOT NULL AND staff_id IS NULL AND kind IN ('ADMISSION','HISTORICAL_REPORT')) OR (student_id IS NULL AND staff_id IS NOT NULL AND kind='STAFF_CREDENTIAL'))
);
CREATE TABLE school_document_revisions (
 document_id uuid NOT NULL REFERENCES school_documents(id),revision integer NOT NULL,snapshot jsonb NOT NULL,
 published_by uuid NOT NULL,published_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(document_id,revision)
);
ALTER TABLE learning_assignments ADD COLUMN submission_mode varchar(12) NOT NULL DEFAULT 'APP' CHECK(submission_mode IN ('APP','PHYSICAL'));
ALTER TABLE assignment_submissions ADD COLUMN channel varchar(12) NOT NULL DEFAULT 'APP' CHECK(channel IN ('APP','PHYSICAL'));
ALTER TABLE assignment_submissions ADD COLUMN received_on date;
CREATE TABLE submission_review_drafts (
 submission_id uuid PRIMARY KEY REFERENCES assignment_submissions(id),source_version bigint NOT NULL,source_revision integer NOT NULL,
 payload jsonb NOT NULL,state varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','RELEASED')),
 version bigint NOT NULL DEFAULT 1,author_id uuid NOT NULL,updated_at timestamptz NOT NULL DEFAULT now(),released_at timestamptz
);
ALTER TABLE schools ADD COLUMN logo_file_id uuid;
ALTER TABLE schools ADD CONSTRAINT school_logo_file_fk FOREIGN KEY(id,logo_file_id) REFERENCES school_files(school_id,id);
