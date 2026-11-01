package com.educore.academics;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AcademicController {
  private final TimetableService timetable;
  private final AttendanceService attendance;
  private final SchoolService school;

  public AcademicController(
      TimetableService timetable, AttendanceService attendance, SchoolService school) {
    this.timetable = timetable;
    this.attendance = attendance;
    this.school = school;
  }

  @PostMapping("/api/v1/schools/{s}/timetable-plans")
  Object plan(
      @PathVariable UUID s,
      @RequestBody TimetableService.PlanInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return timetable.create(Actor.current(), s, b, key);
  }

  @PostMapping("/api/v1/schools/{s}/timetable-plans/{p}/lessons")
  Object lesson(
      @PathVariable UUID s,
      @PathVariable UUID p,
      @RequestBody TimetableService.LessonInput b,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return timetable.addLesson(Actor.current(), s, p, b, Api.version(version), key);
  }

  @GetMapping("/api/v1/schools/{s}/timetable-plans/{p}/review")
  Object review(@PathVariable UUID s, @PathVariable UUID p) {
    return timetable.review(Actor.current(), s, p);
  }

  @PostMapping("/api/v1/schools/{s}/timetable-plans/{p}/publish")
  Object publish(
      @PathVariable UUID s,
      @PathVariable UUID p,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return timetable.publish(Actor.current(), s, p, Api.version(version), key);
  }

  @GetMapping("/api/v1/schools/{s}/timetable")
  Object timetable(
      @PathVariable UUID s,
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) LocalDate on) {
    return timetable.published(Actor.current(), s, classId, on);
  }

  @PostMapping("/api/v1/schools/{s}/attendance/registers")
  Object open(
      @PathVariable UUID s,
      @RequestBody AttendanceService.RegisterInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return attendance.open(Actor.current(), s, b, key);
  }

  @GetMapping("/api/v1/schools/{s}/attendance/registers/{r}")
  Object register(@PathVariable UUID s, @PathVariable UUID r) {
    return attendance.view(Actor.current(), s, r);
  }

  @PutMapping("/api/v1/schools/{s}/attendance/registers/{r}/marks")
  Object marks(
      @PathVariable UUID s,
      @PathVariable UUID r,
      @RequestBody AttendanceService.Marks b,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return attendance.marks(Actor.current(), s, r, b, Api.version(version), key);
  }

  @PostMapping("/api/v1/schools/{s}/attendance/registers/{r}/submit")
  Object submit(
      @PathVariable UUID s,
      @PathVariable UUID r,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return attendance.submit(Actor.current(), s, r, Api.version(version), key);
  }

  @PostMapping("/api/v1/schools/{s}/attendance/registers/{r}/corrections")
  Object correct(
      @PathVariable UUID s,
      @PathVariable UUID r,
      @RequestBody AttendanceService.Mark b,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return attendance.correct(Actor.current(), s, r, b, Api.version(version), key);
  }

  @GetMapping("/api/v1/schools/{s}/attendance/overview")
  Object overview(@PathVariable UUID s, @RequestParam(required = false) LocalDate on) {
    return attendance.overview(Actor.current(), s, on);
  }

  @GetMapping("/api/v1/schools/{s}/students/{student}/attendance")
  Object child(
      @PathVariable UUID s,
      @PathVariable UUID student,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to) {
    return attendance.student(Actor.current(), s, student, from, to);
  }

  @GetMapping("/api/v1/schools/{s}/terms")
  Object terms(@PathVariable UUID s, @RequestParam UUID sessionId) {
    Actor.current().school(s);
    return Api.map(
        "items",
        school.db.queryForList(
            "select id,session_id as \"sessionId\",number,starts_on as \"startsOn\",ends_on as \"endsOn\",version from terms where school_id=? and session_id=? order by number",
            s,
            sessionId),
        "hasMore",
        false);
  }

  @GetMapping("/api/v1/schools/{s}/teaching-assignments")
  Object assignments(@PathVariable UUID s, @RequestParam(required = false) UUID classId) {
    var a = Actor.current();
    var c = a.school(s, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    UUID teacher = "TEACHER".equals(c.role()) ? c.subjectId() : null;
    return Api.map(
        "items",
        school.db.queryForList(
            "select t.id,t.class_id as \"classId\",t.subject_id as \"subjectId\",su.name as \"subjectName\",t.teacher_id as \"teacherId\",st.full_name as \"teacherName\",t.department,t.weekly_periods as \"weeklyPeriods\",t.version from teaching_assignments t join subjects su on su.id=t.subject_id join staff st on st.id=t.teacher_id where t.school_id=? and (?::uuid is null or t.class_id=?::uuid) and (?::uuid is null or t.teacher_id=?::uuid) order by su.name,t.id",
            s,
            classId,
            classId,
            teacher,
            teacher),
        "hasMore",
        false);
  }

  @PostMapping("/api/v1/schools/{s}/attendance/registers/{r}/absence-alerts")
  Object alert(
      @PathVariable UUID s,
      @PathVariable UUID r,
      @RequestBody AttendanceTeacherAlert b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    var a = Actor.current();
    a.school(s, "PRINCIPAL", "SCHOOL_OFFICE");
    return school.commands.execute(
        a.accountId().toString(),
        s.toString(),
        "attendance/" + r + "/absence-alerts",
        key,
        b,
        () -> {
          var register =
              school.one("select state from attendance_registers where school_id=? and id=?", s, r);
          if (!"SUBMITTED".equals(register.get("state")))
            throw Api.fail(
                409,
                "REGISTER_NOT_SUBMITTED",
                "Review a submitted register before sending an absence alert.");
          school.one(
              "select student_id from attendance_rows where register_id=? and student_id=? and status='ABSENT'",
              r,
              b.studentId());
          String why = Api.text(b.reviewReason(), 5, 500, "Office review reason");
          var old =
              school.db.queryForList(
                  "select event_id from attendance_alerts where register_id=? and student_id=?",
                  r,
                  b.studentId());
          if (!old.isEmpty())
            return Api.map(
                "accepted", true, "eventId", old.get(0).get("event_id"), "alreadyRequested", true);
          UUID event =
              school.outbox.add(
                  "communication",
                  "CHILD_NOTIFICATION",
                  Api.map(
                      "schoolId",
                      s,
                      "childId",
                      b.studentId(),
                      "category",
                      "LEARNING",
                      "title",
                      "School attendance update",
                      "safePreview",
                      "Your school posted an attendance update. Open your child's record for details.",
                      "target",
                      Api.map(
                          "screenId",
                          "PA01",
                          "schoolId",
                          s,
                          "childId",
                          b.studentId(),
                          "resourceId",
                          r)));
          school.db.update(
              "insert into attendance_alerts(register_id,student_id,event_id) values (?,?,?)",
              r,
              b.studentId(),
              event);
          school.commands.audit(
              s,
              a.accountId(),
              "ABSENCE_ALERT_REVIEWED",
              "ATTENDANCE_REGISTER",
              r,
              why,
              Api.map("studentId", b.studentId()));
          return Api.map("accepted", true, "eventId", event, "deliveryStatus", "QUEUED");
        });
  }

  public record AttendanceTeacherAlert(UUID studentId, String reviewReason) {}

  public record CalendarInput(LocalDate onDate, String title, boolean teachingDay, String reason) {}

  @PostMapping("/api/v1/schools/{s}/calendar-exceptions")
  Object calendar(
      @PathVariable UUID s,
      @RequestBody CalendarInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    var a = Actor.current();
    a.school(s, "PRINCIPAL");
    if (b.onDate() == null) throw Api.fail(422, "DATE_REQUIRED", "Provide the exception date.");
    String title = Api.text(b.title(), 2, 120, "Calendar title"),
        reason = Api.text(b.reason(), 5, 500, "Calendar reason");
    return school.commands.execute(
        a.accountId().toString(),
        s.toString(),
        "calendar-exceptions",
        key,
        b,
        () -> {
          UUID id = UUID.randomUUID();
          school.db.update(
              "insert into calendar_exceptions(id,school_id,on_date,title,teaching_day,reason) values (?,?,?,?,?,?)",
              id,
              s,
              b.onDate(),
              title,
              b.teachingDay(),
              reason);
          school.commands.audit(
              s,
              a.accountId(),
              "CALENDAR_EXCEPTION_ADDED",
              "CALENDAR_EXCEPTION",
              id,
              reason,
              Map.of());
          return Api.map("id", id, "onDate", b.onDate(), "teachingDay", b.teachingDay());
        });
  }

  public record StudentContext(UUID schoolId, UUID studentId, UUID guardianId, UUID termId) {}

  @PostMapping("/internal/student-context")
  Object studentContext(@RequestBody StudentContext b) {
    Actor.internalOnly("finance", "communication", "academics");
    if (b.guardianId() != null
        && school.db.queryForObject(
                "select count(*) from guardian_links where school_id=? and student_id=? and guardian_id=? and active",
                Integer.class,
                b.schoolId(),
                b.studentId(),
                b.guardianId())
            == 0) throw Api.fail(404, "NOT_FOUND", "The child relationship is unavailable.");
    if (b.termId() != null)
      school.one("select id from terms where school_id=? and id=?", b.schoolId(), b.termId());
    var st =
        school.one(
            "select id,full_name,admission_number from students where school_id=? and id=?",
            b.schoolId(),
            b.studentId());
    return Api.map(
        "id",
        st.get("id"),
        "fullName",
        st.get("full_name"),
        "admissionNumber",
        st.get("admission_number"));
  }

  public record ResponseTarget(UUID schoolId) {}

  @PostMapping("/internal/response-target")
  Object responseTarget(@RequestBody ResponseTarget b) {
    Actor.internalOnly("communication");
    school.school(b.schoolId());
    LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
    var days =
        school.db.queryForList(
            "select d::date as day from generate_series(?::date+1,?::date+366,interval '1 day') d where exists(select 1 from terms t where t.school_id=? and d::date between t.starts_on and t.ends_on) and coalesce((select teaching_day from calendar_exceptions e where e.school_id=? and e.on_date=d::date),extract(isodow from d)<=5) order by d offset 1 limit 1",
            today,
            today,
            b.schoolId(),
            b.schoolId());
    return days.isEmpty()
        ? Api.map("calendarConfigured", false)
        : Api.map(
            "calendarConfigured",
            true,
            "firstResponseDueAt",
            ((java.sql.Date) days.get(0).get("day"))
                .toLocalDate()
                .atTime(13, 0)
                .atZone(ZoneId.of("Africa/Lagos"))
                .toOffsetDateTime());
  }
}
