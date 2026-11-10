CREATE TABLE promotion_batches (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,source_session_id uuid NOT NULL,target_session_id uuid,source_class_id uuid NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PREVIEW' CHECK(state IN ('PREVIEW','COMPLETED')),version bigint NOT NULL DEFAULT 1,
 prepared_by uuid NOT NULL,confirmed_by uuid,created_at timestamptz NOT NULL DEFAULT now(),confirmed_at timestamptz,
 FOREIGN KEY(school_id,source_session_id) REFERENCES academic_sessions(school_id,id),
 FOREIGN KEY(school_id,target_session_id) REFERENCES academic_sessions(school_id,id),
 FOREIGN KEY(school_id,source_class_id) REFERENCES classes(school_id,id),UNIQUE(school_id,id)
);
CREATE TABLE promotion_rows (
 batch_id uuid NOT NULL REFERENCES promotion_batches(id),student_id uuid NOT NULL REFERENCES students(id),
 recommendation jsonb NOT NULL,source_signature char(64) NOT NULL,source_report_ids jsonb NOT NULL,
 decision varchar(20) NOT NULL DEFAULT 'REVIEW' CHECK(decision IN ('REVIEW','PROMOTE','REPEAT','JSS_COMPLETE','GRADUATE')),
 target_class_id uuid REFERENCES classes(id),department varchar(20),reason varchar(1000),evidence_reference varchar(240),
 approved_exception boolean NOT NULL DEFAULT false,version bigint NOT NULL DEFAULT 1,result_id uuid,
 PRIMARY KEY(batch_id,student_id)
);
CREATE TABLE promotion_outcomes (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,source_session_id uuid NOT NULL,student_id uuid NOT NULL,
 batch_id uuid NOT NULL REFERENCES promotion_batches(id),decision varchar(20) NOT NULL,target_enrolment_id uuid REFERENCES enrolments(id),
 reason varchar(1000),evidence_reference varchar(240),requires_review boolean NOT NULL DEFAULT false,confirmed_by uuid NOT NULL,confirmed_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),UNIQUE(source_session_id,student_id)
);
