ALTER TABLE provisioned_subjects ADD COLUMN source_version bigint NOT NULL DEFAULT 1;
CREATE TABLE guardian_access_versions (
 school_id uuid NOT NULL,child_id uuid NOT NULL,guardian_id uuid NOT NULL,source_version bigint NOT NULL,
 PRIMARY KEY(school_id,child_id,guardian_id)
);
INSERT INTO guardian_access_versions(school_id,child_id,guardian_id,source_version)
 SELECT school_id,child_id,subject_id,1 FROM memberships WHERE role='PARENT' AND child_id IS NOT NULL AND subject_id IS NOT NULL
 ON CONFLICT DO NOTHING;
