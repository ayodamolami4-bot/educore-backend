package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AttendanceService {
  private final SchoolService s;

  public AttendanceService(SchoolService s) {
    this.s = s;
  }

  public record RegisterInput(UUID classId, LocalDate onDate) {}

  public record Mark(UUID studentId, String status, String reason) {}

  public record Marks(List<Mark> rows) {}

  private void teacher(Actor a, UUID school, UUID classId) {
    var context = a.school(school, "TEACHER");
    var c = s.one("select * from classes where school_id=? and id=?", school, classId);
    if (!context.subjectId().equals(c.get("attendance_teacher_id")))
      throw fail(
          403,
          "ATTENDANCE_DUTY_REQUIRED",
          "Only the assigned class attendance teacher can submit this register.");
  }

  private boolean teachingDay(UUID school, UUID classId, LocalDate on) {
    if (on == null) return false;
    var c = s.one("select * from classes where school_id=? and id=?", school, classId);
    if (s.db.queryForObject(
            "select count(*) from terms where school_id=? and session_id=? and starts_on<=? and ends_on>=?",
            Integer.class,
            school,
            c.get("session_id"),
            on,
            on)
        == 0) return false;
    var exception =
        s.db.queryForList(
            "select teaching_day from calendar_exceptions where school_id=? and on_date=?",
            school,
            on);
    return exception.isEmpty()
        ? on.getDayOfWeek().getValue() <= 5
        : Boolean.TRUE.equals(exception.get(0).get("teaching_day"));
  }

  public Object open(Actor a, UUID school, RegisterInput b, String key) {
    teacher(a, school, b.classId());
    if (!teachingDay(school, b.classId(), b.onDate())
        || b.onDate().isAfter(LocalDate.now(ZoneId.of("Africa/Lagos"))))
      throw fail(422, "NOT_ATTENDANCE_DAY", "Choose a teaching day that is not in the future.");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "attendance/registers",
        key,
        b,
        () -> {
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into attendance_registers(id,school_id,class_id,on_date) values (?,?,?,?) on conflict(class_id,on_date) do nothing",
              id,
              school,
              b.classId(),
              b.onDate());
          var register =
              s.one(
                  "select * from attendance_registers where class_id=? and on_date=? for update",
                  b.classId(),
                  b.onDate());
          UUID actual = (UUID) register.get("id");
          if ("DRAFT".equals(register.get("state")))
            s.db.update(
                "insert into attendance_rows(register_id,student_id) select ?,student_id from enrolments where school_id=? and class_id=? and starts_on<=? and (ends_on is null or ends_on>=?) on conflict do nothing",
                actual,
                school,
                b.classId(),
                b.onDate(),
                b.onDate());
          return view(a, school, actual);
        });
  }

  public Object view(Actor a, UUID school, UUID id) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    var r = s.one("select * from attendance_registers where school_id=? and id=?", school, id);
    if ("TEACHER".equals(c.role())) teacher(a, school, (UUID) r.get("class_id"));
    return map(
        "id",
        id,
        "version",
        r.get("version"),
        "classId",
        r.get("class_id"),
        "onDate",
        r.get("on_date"),
        "state",
        r.get("state"),
        "submittedAt",
        r.get("submitted_at"),
        "rows",
        s.db.queryForList(
            "select ar.student_id as \"studentId\",st.full_name as \"fullName\",st.admission_number as \"admissionNumber\",ar.status,ar.reason from attendance_rows ar join students st on st.id=ar.student_id where ar.register_id=? order by st.full_name,st.id",
            id));
  }

  private Map<String, Object> draft(Actor a, UUID school, UUID id, long version) {
    var r =
        s.one(
            "select * from attendance_registers where school_id=? and id=? for update", school, id);
    teacher(a, school, (UUID) r.get("class_id"));
    expectVersion(version, ((Number) r.get("version")).longValue());
    if (!"DRAFT".equals(r.get("state")))
      throw fail(
          409,
          "REGISTER_SUBMITTED",
          "Submitted attendance requires an authorised correction and reason.");
    return r;
  }

  public Object marks(Actor a, UUID school, UUID id, Marks b, long version, String key) {
    a.school(school, "TEACHER");
    teacher(
        a,
        school,
        (UUID)
            s.one(
                    "select class_id from attendance_registers where school_id=? and id=?",
                    school,
                    id)
                .get("class_id"));
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "attendance/" + id + "/marks",
        key,
        map("marks", b, "version", version),
        () -> {
          draft(a, school, id, version);
          if (b.rows() == null || b.rows().size() > 200)
            throw fail(422, "INVALID_ROWS", "Send up to 200 attendance marks.");
          Set<UUID> seen = new HashSet<>();
          for (var mark : b.rows()) {
            if (!seen.add(mark.studentId())
                || mark.status() == null
                || !Set.of("PRESENT", "ABSENT", "LATE", "EXCUSED").contains(mark.status()))
              throw fail(
                  422,
                  "INVALID_ATTENDANCE_MARK",
                  "Use a distinct student and a supported attendance status.");
            if (mark.reason() != null && mark.reason().length() > 500)
              throw fail(422, "REASON_TOO_LONG", "Use at most 500 reason characters.");
            if (s.db.update(
                    "update attendance_rows set status=?,reason=? where register_id=? and student_id=?",
                    mark.status(),
                    mark.reason(),
                    id,
                    mark.studentId())
                != 1)
              throw fail(
                  422, "STUDENT_NOT_IN_REGISTER", "The student is not in this dated class roster.");
          }
          s.db.update("update attendance_registers set version=version+1 where id=?", id);
          return view(a, school, id);
        });
  }

  public Object submit(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "TEACHER");
    teacher(
        a,
        school,
        (UUID)
            s.one(
                    "select class_id from attendance_registers where school_id=? and id=?",
                    school,
                    id)
                .get("class_id"));
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "attendance/" + id + "/submit",
        key,
        map("version", version),
        () -> {
          var r = draft(a, school, id, version);
          int
              total =
                  s.db.queryForObject(
                      "select count(*) from attendance_rows where register_id=?",
                      Integer.class,
                      id),
              missing =
                  s.db.queryForObject(
                      "select count(*) from attendance_rows where register_id=? and status is null",
                      Integer.class,
                      id);
          if (total == 0 || missing > 0)
            throw fail(
                422, "REGISTER_INCOMPLETE", "Mark every student before submitting the register.");
          LocalDate date = ((java.sql.Date) r.get("on_date")).toLocalDate();
          var changed =
              s.db.queryForList(
                  "select student_id from enrolments where school_id=? and class_id=? and starts_on<=? and (ends_on is null or ends_on>=?) except select student_id from attendance_rows where register_id=?",
                  school,
                  r.get("class_id"),
                  date,
                  date,
                  id);
          if (!changed.isEmpty())
            throw fail(
                409,
                "ROSTER_CHANGED",
                "Reopen the draft to include newly effective enrolments before submission.");
          s.db.update(
              "update attendance_registers set state='SUBMITTED',submitted_by=?,submitted_at=now(),version=version+1 where id=?",
              a.accountId(),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "ATTENDANCE_SUBMITTED",
              "ATTENDANCE_REGISTER",
              id,
              null,
              map("classId", r.get("class_id"), "onDate", date, "studentCount", total));
          return view(a, school, id);
        });
  }

  public Object overview(Actor a, UUID school, LocalDate date) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    if (date == null) date = LocalDate.now(ZoneId.of("Africa/Lagos"));
    var classes =
        s.db.queryForList(
            "select c.id,c.level,c.arm from classes c join academic_sessions y on y.id=c.session_id where c.school_id=? and y.starts_on<=? and y.ends_on>=?",
            school,
            date,
            date);
    int expected = 0;
    for (var c : classes) if (teachingDay(school, (UUID) c.get("id"), date)) expected++;
    var submitted =
        s.db.queryForList(
            "select r.id,r.class_id as \"classId\",r.version,count(*) filter(where ar.status='PRESENT') as present,count(*) filter(where ar.status='LATE') as late,count(*) filter(where ar.status='ABSENT') as absent,count(*) filter(where ar.status='EXCUSED') as excused from attendance_registers r join attendance_rows ar on ar.register_id=r.id where r.school_id=? and r.on_date=? and r.state='SUBMITTED' group by r.id",
            school,
            date);
    var absences =
        s.db.queryForList(
            "select ar.student_id as \"studentId\",st.full_name as \"fullName\",r.class_id as \"classId\",r.id as \"registerId\",ar.reason from attendance_rows ar join attendance_registers r on r.id=ar.register_id join students st on st.id=ar.student_id where r.school_id=? and r.on_date=? and r.state='SUBMITTED' and ar.status='ABSENT' order by st.full_name",
            school,
            date);
    return map(
        "onDate",
        date,
        "expectedRegisters",
        expected,
        "submittedRegisters",
        submitted.size(),
        "missingRegisters",
        Math.max(0, expected - submitted.size()),
        "coverageComplete",
        expected == submitted.size(),
        "registers",
        submitted,
        "absences",
        absences);
  }

  public Object student(Actor a, UUID school, UUID student, LocalDate from, LocalDate to) {
    s.learning(a, school, student);
    if (from == null
        || to == null
        || from.isAfter(to)
        || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366)
      throw fail(422, "INVALID_RANGE", "Choose a date range of at most 366 days.");
    return map(
        "items",
        s.db.queryForList(
            "select r.id as \"registerId\",r.on_date as \"onDate\",ar.status,ar.reason,r.version from attendance_rows ar join attendance_registers r on r.id=ar.register_id where r.school_id=? and ar.student_id=? and r.state='SUBMITTED' and r.on_date between ? and ? order by r.on_date",
            school,
            student,
            from,
            to),
        "includesDrafts",
        false);
  }

  public Object correct(Actor a, UUID school, UUID register, Mark mark, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    String why = text(mark.reason(), 5, 500, "Correction reason");
    if (mark.status() == null
        || !Set.of("PRESENT", "ABSENT", "LATE", "EXCUSED").contains(mark.status()))
      throw fail(422, "INVALID_STATUS", "Select a supported attendance status.");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "attendance/" + register + "/corrections",
        key,
        map("mark", mark, "version", version),
        () -> {
          var r =
              s.one(
                  "select * from attendance_registers where school_id=? and id=? for update",
                  school,
                  register);
          expectVersion(version, ((Number) r.get("version")).longValue());
          if (!"SUBMITTED".equals(r.get("state")))
            throw fail(409, "NOT_SUBMITTED", "Edit the draft register before submission.");
          var old =
              s.one(
                  "select status from attendance_rows where register_id=? and student_id=?",
                  register,
                  mark.studentId());
          s.db.update(
              "update attendance_rows set status=?,reason=? where register_id=? and student_id=?",
              mark.status(),
              why,
              register,
              mark.studentId());
          s.db.update("update attendance_registers set version=version+1 where id=?", register);
          s.db.update(
              "insert into attendance_correction_events(id,school_id,register_id,student_id,register_version,before_status,after_status,reason,actor_id) values (?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              school,
              register,
              mark.studentId(),
              version + 1,
              old.get("status"),
              mark.status(),
              why,
              a.accountId());
          s.commands.audit(
              school,
              a.accountId(),
              "ATTENDANCE_CORRECTED",
              "ATTENDANCE_REGISTER",
              register,
              why,
              map(
                  "studentId",
                  mark.studentId(),
                  "before",
                  old.get("status"),
                  "after",
                  mark.status()));
          return view(a, school, register);
        });
  }
}
