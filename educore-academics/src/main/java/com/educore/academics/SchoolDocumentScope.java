package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SchoolDocumentScope {
  private final SchoolService s;

  public SchoolDocumentScope(SchoolService s) {
    this.s = s;
  }

  void read(Actor a, UUID school, Map<String, Object> d, JsonNode snapshot) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "STUDENT", "PARENT");
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(c.role())) return;
    if (!"PUBLISHED".equals(d.get("state")) || snapshot == null)
      throw fail(404, "NOT_FOUND", "This school document is unavailable.");
    if (d.get("staff_id") != null) {
      if ("TEACHER".equals(c.role())
          && c.subjectId().equals(d.get("staff_id"))
          && snapshot.path("staffVisible").asBoolean()) return;
      throw fail(404, "NOT_FOUND", "This staff document is private.");
    }
    UUID child = (UUID) d.get("student_id");
    if (!snapshot.path("familyVisible").asBoolean())
      throw fail(404, "NOT_FOUND", "This school document is private.");
    s.learning(a, school, child);
    if ("TEACHER".equals(c.role())
        && (!"HISTORICAL_REPORT".equals(d.get("kind"))
            || s.db.queryForObject(
                    "select count(*) from enrolments e join classes cl on cl.id=e.class_id where e.school_id=? and e.student_id=? and e.starts_on<=current_date and (e.ends_on is null or e.ends_on>=current_date) and cl.attendance_teacher_id=?",
                    Integer.class,
                    school,
                    child,
                    c.subjectId())
                == 0))
      throw fail(
          404,
          "NOT_FOUND",
          "Only the current class lead can read this pupil's verified historical reports.");
  }
}
