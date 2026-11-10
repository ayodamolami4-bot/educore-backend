ALTER TABLE assessment_roster ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE assessment_roster ADD COLUMN scope_reason varchar(500);
ALTER TABLE assessment_marks ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE assessment_marks ADD COLUMN scope_reason varchar(500);
ALTER TABLE assessment_sheets ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE assessment_sheets ADD COLUMN required boolean NOT NULL DEFAULT true;
ALTER TABLE assessment_sheets ADD COLUMN choice_group varchar(30);
UPDATE assessment_sheets sh SET required=t.required,choice_group=t.choice_group
 FROM teaching_assignments t WHERE t.id=sh.assignment_id;
UPDATE assessment_sheets sh SET active=false WHERE NOT EXISTS (
 SELECT 1 FROM assessment_marks m WHERE m.cycle_id=sh.cycle_id AND m.assignment_id=sh.assignment_id
);
