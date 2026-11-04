CREATE TABLE attendance_correction_requests (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,register_id uuid NOT NULL,student_id uuid NOT NULL,source_register_version bigint NOT NULL,
 source_status varchar(10) NOT NULL,requested_status varchar(10) NOT NULL CHECK(requested_status IN ('PRESENT','ABSENT','LATE','EXCUSED')),
 reason varchar(500) NOT NULL,state varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPROVED','DECLINED','CANCELLED')),
 requested_by uuid NOT NULL,teacher_id uuid NOT NULL,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),
 decided_by uuid,decision_reason varchar(500),decided_at timestamptz,
 FOREIGN KEY(school_id,register_id) REFERENCES attendance_registers(school_id,id),FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),UNIQUE(school_id,id)
);
CREATE UNIQUE INDEX one_pending_attendance_request ON attendance_correction_requests(register_id,student_id) WHERE state='PENDING';
CREATE TABLE attendance_request_events (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES attendance_correction_requests(id),action varchar(24) NOT NULL,
 details jsonb NOT NULL,actor_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE attendance_correction_events (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,register_id uuid NOT NULL,student_id uuid NOT NULL,register_version bigint NOT NULL,
 before_status varchar(10),after_status varchar(10) NOT NULL,reason varchar(500) NOT NULL,actor_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,register_id) REFERENCES attendance_registers(school_id,id),FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),UNIQUE(register_id,student_id,register_version)
);
CREATE TABLE attendance_correction_notices (
 correction_id uuid PRIMARY KEY REFERENCES attendance_correction_events(id),event_id uuid NOT NULL REFERENCES outbox(id),reviewed_by uuid NOT NULL,reason varchar(500) NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE attendance_followup_cases (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,student_id uuid NOT NULL,on_date date NOT NULL,evidence jsonb NOT NULL,
 state varchar(12) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','CONTACTED','MONITORING','RESOLVED')),
 owner_id uuid NOT NULL,version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),resolved_at timestamptz,
 FOREIGN KEY(school_id,student_id) REFERENCES students(school_id,id),FOREIGN KEY(school_id,owner_id) REFERENCES staff(school_id,id),UNIQUE(student_id,on_date),UNIQUE(school_id,id)
);
CREATE TABLE attendance_followup_entries (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES attendance_followup_cases(id),state varchar(12) NOT NULL,
 note varchar(1500) NOT NULL,reason varchar(500) NOT NULL,owner_id uuid NOT NULL,actor_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_active_absence_followup ON attendance_followup_cases(student_id) WHERE state<>'RESOLVED';
