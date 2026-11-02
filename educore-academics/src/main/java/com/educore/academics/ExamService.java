package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ExamService {
  private final SchoolService s;

  public ExamService(SchoolService s) {
    this.s = s;
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object b,
      java.util.function.Supplier<Object> action) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return s.commands.execute(a.accountId().toString(), school.toString(), path, key, b, action);
  }

  private Map<String, Object> schedule(UUID school, UUID id, boolean lock) {
    return s.one(
        "select x.*,t.starts_on,t.ends_on,t.session_id from exam_schedules x join terms t on t.id=x.term_id where x.school_id=? and x.id=?"
            + (lock ? " for update of x" : ""),
        school,
        id);
  }

  public record Schedule(UUID termId) {}

  public Object create(Actor a, UUID school, Schedule b, String key) {
    return command(
        a,
        school,
        "exam-schedules",
        key,
        b,
        () -> {
          s.one("select id from terms where school_id=? and id=? for update", school, b.termId());
          long rev =
              s.db.queryForObject(
                  "select coalesce(max(revision),0)+1 from exam_schedules where term_id=?",
                  Long.class,
                  b.termId());
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into exam_schedules(id,school_id,term_id,revision) values (?,?,?,?)",
              id,
              school,
              b.termId(),
              rev);
          return map("id", id, "state", "DRAFT", "version", 1, "revision", rev);
        });
  }

  public Object schedules(Actor a, UUID school, UUID term) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return map(
        "items",
        s.db.queryForList(
            "select id,term_id as \"termId\",revision,state,version,published_at as \"publishedAt\" from exam_schedules where school_id=? and (?::uuid is null or term_id=?::uuid) order by revision desc",
            school,
            term,
            term),
        "hasMore",
        false);
  }

  public record Event(
      UUID classId,
      UUID subjectId,
      String department,
      UUID invigilatorId,
      LocalDate onDate,
      String start,
      int durationMinutes,
      String room,
      String exceptionReason) {}

  private void validate(Actor a, UUID school, Map<String, Object> x, Event b) {
    var c = s.one("select * from classes where school_id=? and id=?", school, b.classId());
    if (!x.get("session_id").equals(c.get("session_id"))
        || b.onDate() == null
        || b.onDate().isBefore(((java.sql.Date) x.get("starts_on")).toLocalDate())
        || b.onDate().isAfter(((java.sql.Date) x.get("ends_on")).toLocalDate()))
      throw fail(
          422,
          "INVALID_EXAM_DATE",
          "Use this class session and a date within the examination term.");
    if (b.department() != null) SchoolRules.department((String) c.get("level"), b.department());
    if (s.db.queryForObject(
            "select count(*) from teaching_assignments where school_id=? and class_id=? and subject_id=? and active and (department is null or department is not distinct from ?::text)",
            Integer.class,
            school,
            b.classId(),
            b.subjectId(),
            b.department())
        == 0)
      throw fail(
          422,
          "EXAM_SUBJECT_NOT_OFFERED",
          "Choose a subject offered to this class and department.");
    s.one(
        "select id from staff where school_id=? and id=? and active and staff_role='TEACHER'",
        school,
        b.invigilatorId());
    var exceptions =
        s.db.queryForList(
            "select teaching_day from calendar_exceptions where school_id=? and on_date=?",
            school,
            b.onDate());
    boolean teaching =
        exceptions.isEmpty()
            ? b.onDate().getDayOfWeek().getValue() <= 5
            : Boolean.TRUE.equals(exceptions.get(0).get("teaching_day"));
    if (!teaching)
      throw fail(
          422,
          "EXAM_ON_CLOSED_DAY",
          "Publish the correct teaching calendar before scheduling an examination on this day.");
    int start = minute(b.start());
    int end = start + b.durationMinutes();
    if (b.durationMinutes() < 15 || b.durationMinutes() > 240 || end > 1440)
      throw fail(
          422,
          "INVALID_EXAM_DURATION",
          "Use an examination duration from 15 to 240 minutes within one day.");
    int day = b.onDate().getDayOfWeek().getValue();
    boolean reserved =
        start < 480
            || end > 780
            || (start < 720 && end > 690)
            || (day == 5 && start < 780 && end > 720)
            || (day == 3
                && Boolean.TRUE.equals(s.school(school).get("wednesday_pt"))
                && start < 510
                && end > 480);
    if (reserved) {
      a.school(school, "PRINCIPAL");
      text(b.exceptionReason(), 10, 500, "Principal examination exception");
    }
    if (s.db.queryForObject(
            "select count(*) from teacher_availability where teacher_id=? and weekday=? and start_min<? and end_min>?",
            Integer.class,
            b.invigilatorId(),
            day,
            end,
            start)
        > 0)
      throw fail(
          409, "INVIGILATOR_UNAVAILABLE", "Choose an available invigilator for this period.");
    text(b.room(), 1, 40, "Exam room");
  }

  private int minute(String value) {
    try {
      var t = LocalTime.parse(value);
      if (t.getSecond() != 0 || t.getNano() != 0) throw new IllegalArgumentException();
      return t.getHour() * 60 + t.getMinute();
    } catch (RuntimeException ex) {
      throw fail(422, "INVALID_TIME", "Use a whole-minute time such as 09:00.");
    }
  }

  public Object event(
      Actor a, UUID school, UUID schedule, UUID event, Event b, long version, String key) {
    return command(
        a,
        school,
        "exam-schedules/" + schedule + "/events/" + Objects.toString(event, "new"),
        key,
        map("input", b, "version", version),
        () -> {
          var x = schedule(school, schedule, true);
          expectVersion(version, ((Number) x.get("version")).longValue());
          if (!"DRAFT".equals(x.get("state")))
            throw fail(
                409,
                "EXAM_SCHEDULE_PUBLISHED",
                "Create a new schedule revision to amend published examinations.");
          validate(a, school, x, b);
          UUID id = event == null ? UUID.randomUUID() : event;
          long ev = 1;
          if (event != null) {
            ev =
                ((Number)
                            s.one(
                                    "select version from exam_events where school_id=? and schedule_id=? and id=?",
                                    school,
                                    schedule,
                                    event)
                                .get("version"))
                        .longValue()
                    + 1;
            s.db.update("delete from exam_events where id=?", event);
          }
          s.db.update(
              "insert into exam_events(id,school_id,schedule_id,class_id,subject_id,department,invigilator_id,on_date,start_min,end_min,room,exception_reason,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              school,
              schedule,
              b.classId(),
              b.subjectId(),
              b.department(),
              b.invigilatorId(),
              b.onDate(),
              minute(b.start()),
              minute(b.start()) + b.durationMinutes(),
              text(b.room(), 1, 40, "Exam room"),
              b.exceptionReason(),
              ev);
          s.db.update("update exam_schedules set version=version+1 where id=?", schedule);
          s.commands.audit(
              school,
              a.accountId(),
              "EXAM_DRAFT_UPDATED",
              "EXAM_SCHEDULE",
              schedule,
              b.exceptionReason(),
              map("eventId", id));
          return map("id", id, "version", ev, "scheduleVersion", version + 1);
        });
  }

  public Object remove(Actor a, UUID school, UUID id, UUID event, long version, String key) {
    return command(
        a,
        school,
        "exam-schedules/" + id + "/events/" + event + "/remove",
        key,
        map("version", version),
        () -> {
          var x = schedule(school, id, true);
          expectVersion(version, ((Number) x.get("version")).longValue());
          if (!"DRAFT".equals(x.get("state")))
            throw fail(
                409, "EXAM_SCHEDULE_PUBLISHED", "Published examination events are preserved.");
          s.one(
              "select id from exam_events where school_id=? and schedule_id=? and id=?",
              school,
              id,
              event);
          s.db.update("delete from exam_events where id=?", event);
          s.db.update("update exam_schedules set version=version+1 where id=?", id);
          return map("id", id, "version", version + 1);
        });
  }

  private List<Map<String, Object>> events(UUID school, UUID schedule) {
    return s.db.queryForList(
        "select e.id,e.class_id as \"classId\",c.level,c.arm,e.subject_id as \"subjectId\",su.name as \"subjectName\",e.department,e.invigilator_id as \"invigilatorId\",st.full_name as \"invigilatorName\",e.on_date as \"onDate\",e.start_min as \"startMinute\",e.end_min as \"endMinute\",e.room,e.exception_reason as \"exceptionReason\",e.version from exam_events e join classes c on c.id=e.class_id join subjects su on su.id=e.subject_id join staff st on st.id=e.invigilator_id where e.school_id=? and e.schedule_id=? order by e.on_date,e.start_min,c.level,c.arm",
        school,
        schedule);
  }

  public Object review(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var x = schedule(school, id, false);
    var rows = events(school, id);
    var issues = new ArrayList<Object>();
    for (var e : rows) {
      try {
        validate(
            a,
            school,
            x,
            new Event(
                (UUID) e.get("classId"),
                (UUID) e.get("subjectId"),
                (String) e.get("department"),
                (UUID) e.get("invigilatorId"),
                ((java.sql.Date) e.get("onDate")).toLocalDate(),
                LocalTime.of(
                        ((Number) e.get("startMinute")).intValue() / 60,
                        ((Number) e.get("startMinute")).intValue() % 60)
                    .toString(),
                ((Number) e.get("endMinute")).intValue()
                    - ((Number) e.get("startMinute")).intValue(),
                (String) e.get("room"),
                (String) e.get("exceptionReason")));
      } catch (Api.Failure ex) {
        issues.add(map("eventId", e.get("id"), "detail", ex.getMessage()));
      }
    }
    return map(
        "id",
        id,
        "version",
        x.get("version"),
        "state",
        x.get("state"),
        "revision",
        x.get("revision"),
        "events",
        rows,
        "issues",
        issues,
        "readyToPublish",
        !rows.isEmpty() && issues.isEmpty());
  }

  public Object publish(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "exam-schedules/" + id + "/publish",
        key,
        map("version", version),
        () -> {
          var x = schedule(school, id, true);
          s.one("select id from terms where id=? for update", x.get("term_id"));
          expectVersion(version, ((Number) x.get("version")).longValue());
          if (!"DRAFT".equals(x.get("state")))
            throw fail(409, "ALREADY_PUBLISHED", "This examination schedule is already published.");
          @SuppressWarnings("unchecked")
          var review = (Map<String, Object>) review(a, school, id);
          if (!Boolean.TRUE.equals(review.get("readyToPublish")))
            throw fail(
                422,
                "EXAM_SCHEDULE_NOT_READY",
                "Resolve every exam validation issue before publishing.");
          s.db.update(
              "update exam_schedules set state='PUBLISHED',published_at=now(),version=version+1 where id=?",
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "EXAM_SCHEDULE_PUBLISHED",
              "EXAM_SCHEDULE",
              id,
              null,
              map("revision", x.get("revision")));
          s.outbox.add(
              "communication",
              "SCHOOL_NOTIFICATION",
              map(
                  "schoolId",
                  school,
                  "title",
                  "Examination timetable published",
                  "safePreview",
                  "Your school published an examination timetable. Open the app to view it."));
          return map("id", id, "version", version + 1, "state", "PUBLISHED");
        });
  }

  public Object published(Actor a, UUID school, UUID term) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "PARENT", "STUDENT");
    s.one("select id from terms where school_id=? and id=?", school, term);
    var rows =
        s.db.queryForList(
            "select id,revision,version from exam_schedules where school_id=? and term_id=? and state='PUBLISHED' order by revision desc limit 1",
            school,
            term);
    if (rows.isEmpty()) return map("published", false, "items", List.of());
    var items = events(school, (UUID) rows.get(0).get("id"));
    UUID student =
        Set.of("PARENT", "STUDENT").contains(c.role())
            ? ("PARENT".equals(c.role()) ? c.childId() : c.subjectId())
            : null;
    if (student != null) s.learning(a, school, student);
    items.removeIf(
        e -> {
          if ("TEACHER".equals(c.role()))
            return !c.subjectId().equals(e.get("invigilatorId"))
                && s.db.queryForObject(
                        "select count(*) from classes cl where cl.id=? and (cl.attendance_teacher_id=? or exists(select 1 from teaching_assignments t where t.class_id=cl.id and t.teacher_id=? and t.active))",
                        Integer.class,
                        e.get("classId"),
                        c.subjectId(),
                        c.subjectId())
                    == 0;
          if (student == null) return false;
          return s.db.queryForObject(
                  "select count(*) from enrolments e where e.school_id=? and e.student_id=? and e.class_id=? and e.starts_on<=? and (e.ends_on is null or e.ends_on>=?) and (?::text is null or e.department=?::text)",
                  Integer.class,
                  school,
                  student,
                  e.get("classId"),
                  e.get("onDate"),
                  e.get("onDate"),
                  e.get("department"),
                  e.get("department"))
              == 0;
        });
    return map(
        "published",
        true,
        "id",
        rows.get(0).get("id"),
        "revision",
        rows.get(0).get("revision"),
        "items",
        items,
        "timezone",
        "Africa/Lagos");
  }

  public record External(
      String board,
      int examYear,
      String candidateReference,
      Map<String, Object> issuedResult,
      String reason) {}

  public Object external(Actor a, UUID school, UUID student, External b, String key) {
    return command(
        a,
        school,
        "students/" + student + "/external-results",
        key,
        b,
        () -> {
          s.one("select id from students where school_id=? and id=?", school, student);
          if (b.board() == null
              || !Set.of("BECE", "WAEC", "NECO").contains(b.board())
              || b.examYear() < 2000
              || b.examYear() > LocalDate.now().getYear() + 1
              || b.issuedResult() == null
              || s.json.write(b.issuedResult()).length() > 20000)
            throw fail(
                422,
                "INVALID_EXTERNAL_RESULT",
                "Record an issued BECE, WAEC or NECO result and its examination year.");
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into external_exam_records(id,school_id,student_id,board,exam_year,candidate_reference,issued_result,recorded_by,reason) values (?,?,?,?,?,?,?::jsonb,?,?)",
              id,
              school,
              student,
              b.board(),
              b.examYear(),
              text(b.candidateReference(), 3, 100, "Candidate reference"),
              s.json.write(b.issuedResult()),
              a.accountId(),
              text(b.reason(), 5, 500, "Recording reason"));
          s.db.update(
              "insert into external_exam_revisions(record_id,revision,snapshot,recorded_by) values (?,1,?::jsonb,?)",
              id,
              s.json.write(b),
              a.accountId());
          s.commands.audit(
              school,
              a.accountId(),
              "EXTERNAL_ISSUED_RESULT_RECORDED",
              "STUDENT",
              student,
              b.reason(),
              map("recordId", id, "board", b.board(), "year", b.examYear()));
          return map("id", id, "version", 1, "verification", "SCHOOL_RECORDED_AS_ISSUED");
        });
  }

  public Object external(Actor a, UUID school, UUID student) {
    s.learning(a, school, student);
    var rows =
        s.db.queryForList(
            "select id,board,exam_year as \"examYear\",candidate_reference as \"candidateReference\",issued_result as \"issuedResult\",created_at as \"recordedAt\",version from external_exam_records where school_id=? and student_id=? order by exam_year desc,board",
            school,
            student);
    for (var r : rows) r.put("issuedResult", s.json.value(r.get("issuedResult").toString()));
    return map("items", rows, "hasMore", false);
  }
}
