UPDATE teaching_assignments ta SET choice_group=rule->>'choiceGroup'
 FROM class_curricula cc JOIN curriculum_templates ct ON ct.id=cc.curriculum_id,
 LATERAL jsonb_array_elements(ct.definition) rule
 WHERE ta.class_id=cc.class_id AND coalesce(ta.department,'')=cc.department AND ta.subject_id=(rule->>'subjectId')::uuid;
ALTER TABLE lessons ADD COLUMN required boolean NOT NULL DEFAULT true;
ALTER TABLE lessons ADD COLUMN choice_group varchar(30);
UPDATE lessons l SET required=t.required,choice_group=t.choice_group FROM teaching_assignments t WHERE t.id=l.assignment_id;
ALTER TABLE exam_events ADD COLUMN required boolean NOT NULL DEFAULT true;
ALTER TABLE exam_events ADD COLUMN choice_group varchar(30);
UPDATE exam_events e SET required=t.required,choice_group=t.choice_group FROM teaching_assignments t
 WHERE t.school_id=e.school_id AND t.class_id=e.class_id AND t.subject_id=e.subject_id
 AND t.department IS NOT DISTINCT FROM e.department;
ALTER TABLE exam_events DROP CONSTRAINT exam_events_schedule_id_class_id_on_date_int4range_excl;
CREATE UNIQUE INDEX staff_number_case_insensitive ON staff(school_id,upper(staff_number));
