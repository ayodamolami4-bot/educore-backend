ALTER TABLE assessment_cycles ADD COLUMN policy_approved_by uuid;
ALTER TABLE assessment_cycles ADD COLUMN policy_approved_at timestamptz;
-- Previously opened cycles retain their frozen policy; do not invent a historical approval actor.
