ALTER TABLE schools ADD COLUMN active_session_id uuid;
ALTER TABLE schools ADD CONSTRAINT active_session_school_fk FOREIGN KEY(id,active_session_id) REFERENCES academic_sessions(school_id,id);
ALTER TABLE subjects ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE subjects ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TABLE school_rooms (
 id uuid PRIMARY KEY,school_id uuid NOT NULL REFERENCES schools(id),code varchar(40) NOT NULL,
 name varchar(100) NOT NULL,capacity integer CHECK(capacity BETWEEN 1 AND 5000),active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1,UNIQUE(school_id,id),UNIQUE(school_id,code),CHECK(code=upper(btrim(code)))
);
INSERT INTO school_rooms(id,school_id,code,name)
 SELECT gen_random_uuid(),school_id,upper(btrim(room)),upper(btrim(room)) FROM (
 SELECT school_id,room FROM classes UNION SELECT school_id,room FROM lessons UNION SELECT school_id,room FROM exam_events
 ) r WHERE room IS NOT NULL AND btrim(room)<>'' GROUP BY school_id,upper(btrim(room));
