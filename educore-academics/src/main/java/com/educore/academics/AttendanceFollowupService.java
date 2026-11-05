package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AttendanceFollowupService {
  private final SchoolService s;
  private final SchoolWorkspaceService workspace;

  public AttendanceFollowupService(SchoolService s, SchoolWorkspaceService workspace) {
    this.s = s;
    this.workspace = workspace;
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object body,
      java.util.function.Supplier<Object> action) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return s.commands.execute(a.accountId().toString(), school.toString(), path, key, body, action);
  }

  private Map<String, Object> row(UUID school, UUID id, boolean lock) {
    return s.one(
        "select c.*,st.full_name as student_name,owner.full_name as owner_name from attendance_followup_cases c join students st on st.id=c.student_id join staff owner on owner.id=c.owner_id where c.school_id=? and c.id=?"
            + (lock ? " for update of c" : ""),
        school,
        id);
  }

  private void owner(UUID school, UUID id) {
    s.one(
        "select id from staff where school_id=? and id=? and active and staff_role in ('PRINCIPAL','SCHOOL_OFFICE')",
        school,
        id);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> flag(Actor a, UUID school, UUID student, LocalDate on) {
    var result = (Map<String, Object>) workspace.followUps(a, school, on);
    return ((List<Map<String, Object>>) result.get("items"))
        .stream().filter(r -> student.equals(r.get("studentId"))).findFirst().orElse(null);
  }

  private void entry(Actor a, UUID id, String state, UUID owner, String note, String reason) {
    s.db.update(
        "insert into attendance_followup_entries(id,case_id,state,note,reason,owner_id,actor_id) values (?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        state,
        note,
        reason,
        owner,
        a.accountId());
  }

  public record Open(UUID studentId, LocalDate onDate, UUID ownerId, String note, String reason) {}

  public record Change(String state, UUID ownerId, String note, String reason) {}

  public Object open(Actor a, UUID school, Open b, String key) {
    return command(
        a,
        school,
        "attendance/follow-up-cases",
        key,
        b,
        () -> {
          LocalDate on = b.onDate() == null ? LocalDate.now(ZoneId.of("Africa/Lagos")) : b.onDate();
          if (b.studentId() == null)
            throw fail(
                422,
                "STUDENT_REQUIRED",
                "Choose the pupil whose confirmed absences are being reviewed.");
          var evidence = flag(a, school, b.studentId(), on);
          if (evidence == null)
            throw fail(
                422,
                "CONFIRMED_ABSENCE_FLAG_REQUIRED",
                "Only three confirmed absences in the reviewed ten-teaching-day window can open this follow-up. Missing registers are not absences.");
          UUID owner = b.ownerId() == null ? a.context().subjectId() : b.ownerId();
          owner(school, owner);
          String why = text(b.reason(), 5, 500, "Follow-up reason"),
              note = text(b.note(), 2, 1500, "Office follow-up note");
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into attendance_followup_cases(id,school_id,student_id,on_date,evidence,owner_id,created_by) values (?,?,?,?,?::jsonb,?,?)",
              id,
              school,
              b.studentId(),
              on,
              s.json.write(evidence),
              owner,
              a.accountId());
          entry(a, id, "OPEN", owner, note, why);
          s.commands.audit(
              school,
              a.accountId(),
              "ATTENDANCE_FOLLOWUP_OPENED",
              "ATTENDANCE_FOLLOWUP",
              id,
              why,
              map("studentId", b.studentId(), "onDate", on));
          return detail(a, school, id);
        });
  }

  public Object change(Actor a, UUID school, UUID id, Change b, long version, String key) {
    return command(
        a,
        school,
        "attendance/follow-up-cases/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          var row = row(school, id, true);
          expectVersion(version, ((Number) row.get("version")).longValue());
          if (b.state() == null
              || !Set.of("OPEN", "CONTACTED", "MONITORING", "RESOLVED").contains(b.state()))
            throw fail(
                422, "INVALID_FOLLOWUP_STATE", "Choose OPEN, CONTACTED, MONITORING or RESOLVED.");
          String current = (String) row.get("state");
          if ("RESOLVED".equals(current) && !Set.of("OPEN", "RESOLVED").contains(b.state()))
            throw fail(
                409,
                "REOPEN_FOLLOWUP_FIRST",
                "Reopen a resolved follow-up before further contact or monitoring.");
          UUID owner = b.ownerId() == null ? (UUID) row.get("owner_id") : b.ownerId();
          owner(school, owner);
          String why = text(b.reason(), 5, 500, "Follow-up decision reason"),
              note = text(b.note(), 2, 1500, "Office follow-up note");
          s.db.update(
              "update attendance_followup_cases set state=?,owner_id=?,resolved_at=case when ?='RESOLVED' then now() else null end,version=version+1 where id=?",
              b.state(),
              owner,
              b.state(),
              id);
          entry(a, id, b.state(), owner, note, why);
          s.commands.audit(
              school,
              a.accountId(),
              "ATTENDANCE_FOLLOWUP_UPDATED",
              "ATTENDANCE_FOLLOWUP",
              id,
              why,
              map("before", current, "after", b.state(), "ownerId", owner));
          return detail(a, school, id);
        });
  }

  public Object detail(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var r = row(school, id, false);
    var currentFlag =
        flag(a, school, (UUID) r.get("student_id"), LocalDate.now(ZoneId.of("Africa/Lagos")));
    return map(
        "id",
        id,
        "studentId",
        r.get("student_id"),
        "studentName",
        r.get("student_name"),
        "onDate",
        r.get("on_date"),
        "state",
        r.get("state"),
        "version",
        r.get("version"),
        "ownerId",
        r.get("owner_id"),
        "ownerName",
        r.get("owner_name"),
        "originalEvidence",
        s.json.value(r.get("evidence").toString()),
        "currentFlag",
        currentFlag,
        "currentlyFlagged",
        currentFlag != null,
        "createdAt",
        r.get("created_at"),
        "resolvedAt",
        r.get("resolved_at"),
        "entries",
        s.db.queryForList(
            "select state,note,reason,owner_id as \"ownerId\",actor_id as \"actorId\",created_at as \"createdAt\" from attendance_followup_entries where case_id=? order by created_at,id",
            id));
  }

  public Object list(Actor a, UUID school, UUID student, String state, UUID cursor, int limit) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return page(
        s.db.queryForList(
            "select c.id,c.student_id as \"studentId\",st.full_name as \"studentName\",c.on_date as \"onDate\",c.state,c.owner_id as \"ownerId\",c.version,c.created_at as \"createdAt\" from attendance_followup_cases c join students st on st.id=c.student_id where c.school_id=? and (?::uuid is null or c.student_id=?::uuid) and (?::text is null or c.state=?::text) and (?::uuid is null or c.id<?::uuid) order by c.id desc limit ?",
            school,
            student,
            student,
            state,
            state,
            cursor,
            cursor,
            limit + 1),
        limit,
        r -> r.get("id").toString());
  }
}
