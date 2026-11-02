package com.educore.academics;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalAcademicController {
  private final SchoolService s;

  public InternalAcademicController(SchoolService s) {
    this.s = s;
  }

  public record Roster(UUID schoolId, UUID termId, List<UUID> classIds) {}

  @PostMapping("/internal/class-roster")
  Object roster(@RequestBody Roster b) {
    Actor.internalOnly("finance", "communication");
    var t = s.one("select * from terms where school_id=? and id=?", b.schoolId(), b.termId());
    if (b.classIds() == null
        || b.classIds().isEmpty()
        || b.classIds().size() > 10
        || new HashSet<>(b.classIds()).size() != b.classIds().size())
      throw Api.fail(422, "INVALID_CLASS_SELECTION", "Choose one to ten distinct classes.");
    LocalDate start = ((java.sql.Date) t.get("starts_on")).toLocalDate(),
        end = ((java.sql.Date) t.get("ends_on")).toLocalDate(),
        on = LocalDate.now(ZoneId.of("Africa/Lagos"));
    if (on.isBefore(start)) on = start;
    if (on.isAfter(end)) on = end;
    List<Object> classes = new ArrayList<>(), students = new ArrayList<>();
    for (UUID id : new TreeSet<>(b.classIds())) {
      var c =
          s.one(
              "select id,level,arm,version from classes where school_id=? and id=? and session_id=?",
              b.schoolId(),
              id,
              t.get("session_id"));
      classes.add(c);
      students.addAll(
          s.db.queryForList(
              "select st.id as \"studentId\",st.full_name as \"fullName\",st.admission_number as \"admissionNumber\",st.version as \"studentVersion\",e.id as \"enrolmentId\",e.version as \"enrolmentVersion\",e.class_id as \"classId\",e.department from enrolments e join students st on st.id=e.student_id where e.school_id=? and e.class_id=? and st.status='ACTIVE' and e.starts_on<=? and (e.ends_on is null or e.ends_on>=?) order by st.id",
              b.schoolId(),
              id,
              on,
              on));
    }
    if (students.size() > 200)
      throw Api.fail(
          422,
          "BATCH_TOO_LARGE",
          "Select fewer classes; each reviewable batch supports at most 200 students.");
    return Api.map(
        "termId",
        b.termId(),
        "startsOn",
        start,
        "endsOn",
        end,
        "on",
        on,
        "classes",
        classes,
        "students",
        students);
  }
}
