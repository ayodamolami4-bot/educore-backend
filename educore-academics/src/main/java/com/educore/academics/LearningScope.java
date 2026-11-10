package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Shared class/subject access used by published materials and their private files. */
@Component
public class LearningScope {
  private final SchoolService s;

  public LearningScope(SchoolService s) {
    this.s = s;
  }

  Map<String, Object> assignment(UUID school, UUID assignment) {
    return s.one(
        "select t.*,cl.session_id,cl.attendance_teacher_id,(st.active and st.staff_role='TEACHER') as teacher_active from teaching_assignments t join classes cl on cl.id=t.class_id join staff st on st.id=t.teacher_id where t.school_id=? and t.id=?",
        school,
        assignment);
  }

  void owner(Actor a, UUID school, Map<String, Object> row) {
    var c = a.school(school, "TEACHER");
    if (!c.subjectId().equals(row.get("teacher_id"))
        || !Boolean.TRUE.equals(row.get("active"))
        || !Boolean.TRUE.equals(row.get("teacher_active")))
      throw fail(404, "NOT_FOUND", "This teaching responsibility is unavailable.");
  }

  void pupil(UUID school, Map<String, Object> row, UUID pupil, LocalDate on) {
    if (s.db.queryForObject(
            "select count(*) from enrolments e where e.school_id=? and e.student_id=? and e.class_id=? and e.starts_on<=? and (e.ends_on is null or e.ends_on>=?) and (?::text is null or e.department=?::text) and (? or exists(select 1 from student_subject_choices ch where ch.student_id=e.student_id and ch.session_id=e.session_id and ch.subject_id=?))",
            Integer.class,
            school,
            pupil,
            row.get("class_id"),
            on,
            on,
            row.get("department"),
            row.get("department"),
            Boolean.TRUE.equals(row.get("required")),
            row.get("subject_id"))
        == 0) throw fail(404, "NOT_FOUND", "This dated class and subject record is unavailable.");
  }

  void read(Actor a, UUID school, Map<String, Object> row, boolean published) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "STUDENT", "PARENT");
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(c.role())) return;
    if ("TEACHER".equals(c.role())) {
      if (Boolean.TRUE.equals(row.get("teacher_active"))
          && c.subjectId().equals(row.get("teacher_id"))
          && Boolean.TRUE.equals(row.get("active"))) return;
      if (published && c.subjectId().equals(row.get("attendance_teacher_id"))) return;
      throw fail(404, "NOT_FOUND", "This learning resource is unavailable.");
    }
    if (!published) throw fail(404, "NOT_FOUND", "This learning resource is not published.");
    UUID child = "PARENT".equals(c.role()) ? c.childId() : c.subjectId();
    s.learning(a, school, child);
    LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
    LocalDate issued =
        ((java.sql.Timestamp) row.get("published_at"))
            .toInstant()
            .atZone(ZoneId.of("Africa/Lagos"))
            .toLocalDate();
    if (s.db.queryForObject(
            "select count(*) from enrolments e where e.school_id=? and e.student_id=? and e.class_id=? and e.starts_on<=? and (e.ends_on is null or e.ends_on>=?) and (?::text is null or e.department=?::text) and (? or exists(select 1 from student_subject_choices ch where ch.student_id=e.student_id and ch.session_id=e.session_id and ch.subject_id=?))",
            Integer.class,
            school,
            child,
            row.get("class_id"),
            today,
            issued,
            row.get("department"),
            row.get("department"),
            Boolean.TRUE.equals(row.get("required")),
            row.get("subject_id"))
        == 0)
      throw fail(404, "NOT_FOUND", "This class or subject is unavailable to the selected pupil.");
  }

  void notifyClass(UUID school, Map<String, Object> row, UUID resource) {
    for (var pupil :
        s.db.queryForList(
            "select distinct e.student_id from enrolments e join students st on st.id=e.student_id where e.school_id=? and e.class_id=? and st.status='ACTIVE' and e.starts_on<=current_date and (e.ends_on is null or e.ends_on>=current_date) and (?::text is null or e.department=?::text) and (? or exists(select 1 from student_subject_choices ch where ch.student_id=e.student_id and ch.session_id=e.session_id and ch.subject_id=?))",
            school,
            row.get("class_id"),
            row.get("department"),
            row.get("department"),
            Boolean.TRUE.equals(row.get("required")),
            row.get("subject_id")))
      s.outbox.add(
          "communication",
          "CHILD_NOTIFICATION",
          map(
              "schoolId",
              school,
              "childId",
              pupil.get("student_id"),
              "category",
              "LEARNING",
              "title",
              "Class material published",
              "safePreview",
              "Your school published learning material. Open the app to view it.",
              "target",
              map(
                  "screenId",
                  "PA05",
                  "schoolId",
                  school,
                  "childId",
                  pupil.get("student_id"),
                  "resourceId",
                  resource)));
  }
}
