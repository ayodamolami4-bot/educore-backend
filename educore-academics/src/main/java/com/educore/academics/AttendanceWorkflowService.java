package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AttendanceWorkflowService {
  private final SchoolService s;
  private final AttendanceService attendance;

  public AttendanceWorkflowService(SchoolService s, AttendanceService attendance) {
    this.s = s;
    this.attendance = attendance;
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object b,
      java.util.function.Supplier<Object> action) {
    return s.commands.execute(a.accountId().toString(), school.toString(), path, key, b, action);
  }

  private Map<String, Object> register(UUID school, UUID id, boolean lock) {
    return s.one(
        "select * from attendance_registers where school_id=? and id=?"
            + (lock ? " for update" : ""),
        school,
        id);
  }

  private void status(String status) {
    if (status == null || !Set.of("PRESENT", "ABSENT", "LATE", "EXCUSED").contains(status))
      throw fail(422, "INVALID_ATTENDANCE_STATUS", "Choose PRESENT, ABSENT, LATE or EXCUSED.");
  }

  private Map<String, Object> correction(UUID school, UUID id, boolean lock) {
    return s.one(
        "select q.*,r.class_id,r.on_date,r.version as current_register_version,c.attendance_teacher_id,st.full_name as student_name,ar.status as current_status from attendance_correction_requests q join attendance_registers r on r.id=q.register_id join classes c on c.id=r.class_id join students st on st.id=q.student_id join attendance_rows ar on ar.register_id=r.id and ar.student_id=q.student_id where q.school_id=? and q.id=?"
            + (lock ? " for update of q" : ""),
        school,
        id);
  }

  private void requestRead(Actor a, UUID school, Map<String, Object> q) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    if ("TEACHER".equals(c.role())
        && !a.accountId().equals(q.get("requested_by"))
        && !c.subjectId().equals(q.get("attendance_teacher_id")))
      throw fail(404, "NOT_FOUND", "This attendance request is unavailable.");
  }

  private void event(Actor a, UUID id, String action, Object details) {
    s.db.update(
        "insert into attendance_request_events(id,request_id,action,details,actor_id) values (?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        id,
        action,
        s.json.write(details),
        a.accountId());
  }

  private Object wire(Map<String, Object> q) {
    return map(
        "id",
        q.get("id"),
        "registerId",
        q.get("register_id"),
        "studentId",
        q.get("student_id"),
        "studentName",
        q.get("student_name"),
        "classId",
        q.get("class_id"),
        "onDate",
        q.get("on_date"),
        "state",
        q.get("state"),
        "version",
        q.get("version"),
        "sourceRegisterVersion",
        q.get("source_register_version"),
        "registerVersion",
        q.get("current_register_version"),
        "sourceStatus",
        q.get("source_status"),
        "currentStatus",
        q.get("current_status"),
        "requestedStatus",
        q.get("requested_status"),
        "reason",
        q.get("reason"),
        "decisionReason",
        q.get("decision_reason"),
        "createdAt",
        q.get("created_at"),
        "staleSource",
        "PENDING".equals(q.get("state"))
            && ((Number) q.get("source_register_version")).longValue()
                != ((Number) q.get("current_register_version")).longValue());
  }

  public record Correction(UUID studentId, String status, String reason, Long registerVersion) {}

  public record Decision(String action, String reason) {}

  public record Reason(String reason) {}

  public record Notices(List<UUID> studentIds, String reason) {}

  public Object correctionHistory(Actor a, UUID school, UUID register, UUID student) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "PARENT", "STUDENT");
    if (Set.of("PARENT", "STUDENT").contains(c.role())) {
      if (student == null)
        throw fail(422, "STUDENT_REQUIRED", "Choose the selected pupil's attendance history.");
      s.learning(a, school, student);
      register(school, register, false);
      s.one(
          "select student_id from attendance_rows where register_id=? and student_id=?",
          register,
          student);
    } else attendance.view(a, school, register);
    return map(
        "items",
        s.db.queryForList(
            "select id,student_id as \"studentId\",register_version as \"registerVersion\",before_status as \"beforeStatus\",after_status as \"afterStatus\",reason,created_at as \"createdAt\" from attendance_correction_events where school_id=? and register_id=? and (?::uuid is null or student_id=?::uuid) order by register_version,id",
            school,
            register,
            student,
            student),
        "hasMore",
        false);
  }

  public Object correctionNotices(
      Actor a, UUID school, UUID reg, Notices b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "attendance/registers/" + reg + "/correction-alerts",
        key,
        map("input", b, "version", version),
        () -> {
          var r = register(school, reg, true);
          expectVersion(version, ((Number) r.get("version")).longValue());
          if (!"SUBMITTED".equals(r.get("state")))
            throw fail(
                409,
                "REGISTER_NOT_SUBMITTED",
                "Only accepted attendance corrections can be communicated.");
          if (b.studentIds() == null
              || b.studentIds().isEmpty()
              || b.studentIds().size() > 200
              || new HashSet<>(b.studentIds()).size() != b.studentIds().size())
            throw fail(
                422,
                "INVALID_REVIEWED_PUPILS",
                "Choose one to 200 distinct corrected pupil records.");
          String reason = text(b.reason(), 5, 500, "Office communication review reason");
          var items = new ArrayList<Object>();
          for (UUID child : b.studentIds()) {
            var correction =
                s.one(
                    "select id from attendance_correction_events where school_id=? and register_id=? and student_id=? order by register_version desc limit 1 for update",
                    school,
                    reg,
                    child);
            UUID id = (UUID) correction.get("id");
            var previous =
                s.db.queryForList(
                    "select event_id from attendance_correction_notices where correction_id=?", id);
            if (!previous.isEmpty()) {
              items.add(
                  map(
                      "studentId",
                      child,
                      "alreadyQueued",
                      true,
                      "eventId",
                      previous.get(0).get("event_id")));
              continue;
            }
            UUID event =
                s.outbox.add(
                    "communication",
                    "CHILD_NOTIFICATION",
                    map(
                        "schoolId",
                        school,
                        "childId",
                        child,
                        "category",
                        "LEARNING",
                        "title",
                        "Attendance correction reviewed",
                        "safePreview",
                        "Your school reviewed a correction to an attendance record. Open the app to view it.",
                        "target",
                        map(
                            "screenId",
                            "PA07",
                            "schoolId",
                            school,
                            "childId",
                            child,
                            "resourceId",
                            reg)));
            s.db.update(
                "insert into attendance_correction_notices(correction_id,event_id,reviewed_by,reason) values (?,?,?,?)",
                id,
                event,
                a.accountId(),
                reason);
            items.add(map("studentId", child, "eventId", event, "alreadyQueued", false));
          }
          s.commands.audit(
              school,
              a.accountId(),
              "ATTENDANCE_CORRECTION_ALERTS_REVIEWED",
              "ATTENDANCE_REGISTER",
              reg,
              reason,
              map("pupilCount", items.size()));
          return map("items", items, "accepted", true);
        });
  }

  public Object request(Actor a, UUID school, UUID reg, Correction b, long version, String key) {
    a.school(school, "TEACHER");
    attendance.view(a, school, reg);
    status(b.status());
    String why = text(b.reason(), 5, 500, "Correction request reason");
    return command(
        a,
        school,
        "attendance/registers/" + reg + "/correction-requests",
        key,
        map("input", b, "version", version),
        () -> {
          var r = register(school, reg, true);
          attendance.view(a, school, reg);
          expectVersion(version, ((Number) r.get("version")).longValue());
          if (b.registerVersion() != null) expectVersion(b.registerVersion(), version);
          if (!"SUBMITTED".equals(r.get("state")))
            throw fail(409, "REGISTER_NOT_SUBMITTED", "Edit an unsubmitted draft directly.");
          var old =
              s.one(
                  "select status from attendance_rows where register_id=? and student_id=?",
                  reg,
                  b.studentId());
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into attendance_correction_requests(id,school_id,register_id,student_id,source_register_version,source_status,requested_status,reason,requested_by,teacher_id) values (?,?,?,?,?,?,?,?,?,?)",
              id,
              school,
              reg,
              b.studentId(),
              version,
              old.get("status"),
              b.status(),
              why,
              a.accountId(),
              a.context().subjectId());
          event(
              a,
              id,
              "REQUESTED",
              map("status", b.status(), "registerVersion", version, "reason", why));
          s.commands.audit(
              school,
              a.accountId(),
              "ATTENDANCE_CORRECTION_REQUESTED",
              "ATTENDANCE_REQUEST",
              id,
              why,
              map("registerId", reg, "studentId", b.studentId()));
          return detail(a, school, id);
        });
  }

  public Object updateRequest(
      Actor a, UUID school, UUID id, Correction b, long version, String key) {
    a.school(school, "TEACHER");
    var before = correction(school, id, false);
    requestRead(a, school, before);
    if (!a.accountId().equals(before.get("requested_by")))
      throw fail(
          403,
          "REQUEST_AUTHOR_REQUIRED",
          "Only the requesting teacher edits their pending request.");
    return command(
        a,
        school,
        "attendance/correction-requests/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          var q = correction(school, id, true);
          expectVersion(version, ((Number) q.get("version")).longValue());
          if (!"PENDING".equals(q.get("state")))
            throw fail(409, "REQUEST_CLOSED", "Only pending requests can be edited.");
          var reg = register(school, (UUID) q.get("register_id"), true);
          attendance.view(a, school, (UUID) q.get("register_id"));
          if (!Objects.equals(b.studentId(), q.get("student_id")) || b.registerVersion() == null)
            throw fail(
                422,
                "REQUEST_SOURCE_REQUIRED",
                "Keep this pupil and provide the current register version.");
          expectVersion(b.registerVersion(), ((Number) reg.get("version")).longValue());
          var accepted =
              s.one(
                  "select status from attendance_rows where register_id=? and student_id=?",
                  q.get("register_id"),
                  q.get("student_id"));
          status(b.status());
          String why = text(b.reason(), 5, 500, "Reviewed request reason");
          s.db.update(
              "update attendance_correction_requests set source_register_version=?,source_status=?,requested_status=?,reason=?,version=version+1 where id=?",
              b.registerVersion(),
              accepted.get("status"),
              b.status(),
              why,
              id);
          event(
              a,
              id,
              "UPDATED",
              map("status", b.status(), "registerVersion", b.registerVersion(), "reason", why));
          return detail(a, school, id);
        });
  }

  public Object decide(Actor a, UUID school, UUID id, Decision b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "attendance/correction-requests/" + id + "/decision",
        key,
        map("input", b, "version", version),
        () -> {
          var q = correction(school, id, true);
          expectVersion(version, ((Number) q.get("version")).longValue());
          if (!"PENDING".equals(q.get("state")))
            throw fail(409, "REQUEST_CLOSED", "This request has already been decided.");
          if (!Set.of("APPROVE", "DECLINE").contains(Objects.toString(b.action(), "")))
            throw fail(422, "INVALID_ATTENDANCE_DECISION", "Choose APPROVE or DECLINE.");
          String why = text(b.reason(), 5, 500, "Decision reason");
          if ("APPROVE".equals(b.action())) {
            register(school, (UUID) q.get("register_id"), true);
            long source = ((Number) q.get("source_register_version")).longValue();
            expectVersion(
                source,
                ((Number) register(school, (UUID) q.get("register_id"), false).get("version"))
                    .longValue());
            String applyKey =
                UUID.nameUUIDFromBytes(
                        ("attendance-approval:" + id)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString();
            attendance.correct(
                a,
                school,
                (UUID) q.get("register_id"),
                new AttendanceService.Mark(
                    (UUID) q.get("student_id"), (String) q.get("requested_status"), why),
                source,
                applyKey);
          }
          String state = "APPROVE".equals(b.action()) ? "APPROVED" : "DECLINED";
          s.db.update(
              "update attendance_correction_requests set state=?,decided_by=?,decision_reason=?,decided_at=now(),version=version+1 where id=?",
              state,
              a.accountId(),
              why,
              id);
          event(a, id, state, map("reason", why));
          s.commands.audit(
              school,
              a.accountId(),
              "ATTENDANCE_REQUEST_" + state,
              "ATTENDANCE_REQUEST",
              id,
              why,
              Map.of());
          return detail(a, school, id);
        });
  }

  public Object cancel(Actor a, UUID school, UUID id, Reason b, long version, String key) {
    a.school(school, "TEACHER");
    var before = correction(school, id, false);
    requestRead(a, school, before);
    if (!a.accountId().equals(before.get("requested_by")))
      throw fail(403, "REQUEST_AUTHOR_REQUIRED", "Only the request's author cancels it.");
    return command(
        a,
        school,
        "attendance/correction-requests/" + id + "/cancel",
        key,
        map("input", b, "version", version),
        () -> {
          var q = correction(school, id, true);
          expectVersion(version, ((Number) q.get("version")).longValue());
          if (!"PENDING".equals(q.get("state")))
            throw fail(409, "REQUEST_CLOSED", "Only a pending request can be cancelled.");
          String why = text(b.reason(), 5, 500, "Cancellation reason");
          s.db.update(
              "update attendance_correction_requests set state='CANCELLED',decision_reason=?,decided_at=now(),version=version+1 where id=?",
              why,
              id);
          event(a, id, "CANCELLED", map("reason", why));
          return detail(a, school, id);
        });
  }

  public Object detail(Actor a, UUID school, UUID id) {
    var q = correction(school, id, false);
    requestRead(a, school, q);
    return wire(q);
  }

  public Object requestHistory(Actor a, UUID school, UUID id) {
    detail(a, school, id);
    var rows =
        s.db.queryForList(
            "select action,details,created_at as \"createdAt\" from attendance_request_events where request_id=? order by created_at,id",
            id);
    for (var row : rows) row.put("details", s.json.value(row.get("details").toString()));
    return map("items", rows, "hasMore", false);
  }

  public Object requests(Actor a, UUID school, UUID classId, String state, UUID cursor, int limit) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    UUID teacher = "TEACHER".equals(c.role()) ? c.subjectId() : null;
    var rows =
        s.db.queryForList(
            "select q.id from attendance_correction_requests q join attendance_registers r on r.id=q.register_id join classes cl on cl.id=r.class_id where q.school_id=? and (?::uuid is null or r.class_id=?::uuid) and (?::text is null or q.state=?::text) and (?::uuid is null or q.id<?::uuid) and (?::uuid is null or q.requested_by=? or cl.attendance_teacher_id=?::uuid) order by q.id desc limit ?",
            school,
            classId,
            classId,
            state,
            state,
            cursor,
            cursor,
            teacher,
            a.accountId(),
            teacher,
            limit + 1);
    var items = new ArrayList<Map<String, Object>>();
    for (var r : rows) {
      @SuppressWarnings("unchecked")
      var item = (Map<String, Object>) detail(a, school, (UUID) r.get("id"));
      items.add(item);
    }
    return page(items, limit, r -> r.get("id").toString());
  }

  public Object registers(
      Actor a,
      UUID school,
      UUID classId,
      LocalDate from,
      LocalDate to,
      String state,
      UUID cursor,
      int limit) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    LocalDate end = to == null ? LocalDate.now(ZoneId.of("Africa/Lagos")) : to,
        start = from == null ? end.minusDays(30) : from;
    if (start.isAfter(end) || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 366)
      throw fail(422, "INVALID_ATTENDANCE_RANGE", "Use an attendance range of at most 366 days.");
    UUID teacher = "TEACHER".equals(c.role()) ? c.subjectId() : null;
    return page(
        s.db.queryForList(
            "select r.id,r.class_id as \"classId\",r.on_date as \"onDate\",r.state,r.version,r.submitted_at as \"submittedAt\",(select count(*) from attendance_correction_requests q where q.register_id=r.id and q.state='PENDING') as \"pendingCorrectionRequests\" from attendance_registers r join classes cl on cl.id=r.class_id where r.school_id=? and r.on_date between ? and ? and (?::uuid is null or r.class_id=?::uuid) and (?::text is null or r.state=?::text) and (?::uuid is null or r.id<?::uuid) and (?::uuid is null or cl.attendance_teacher_id=?::uuid) order by r.id desc limit ?",
            school,
            start,
            end,
            classId,
            classId,
            state,
            state,
            cursor,
            cursor,
            teacher,
            teacher,
            limit + 1),
        limit,
        r -> r.get("id").toString());
  }
}
