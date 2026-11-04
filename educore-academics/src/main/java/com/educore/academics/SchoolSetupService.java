package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SchoolSetupService {
  private final SchoolService s;

  public SchoolSetupService(SchoolService s) {
    this.s = s;
  }

  private Object command(
      Actor a,
      UUID school,
      String route,
      Object b,
      long version,
      String key,
      java.util.function.Supplier<Object> action) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        route,
        key,
        map("input", b, "version", version),
        action);
  }

  public record Dates(String name, LocalDate startsOn, LocalDate endsOn, String reason) {}

  public Object session(Actor a, UUID school, UUID id, Dates b, long version, String key) {
    return command(
        a,
        school,
        "academic-sessions/" + id,
        b,
        version,
        key,
        () -> {
          s.lockPlacements(school);
          var old =
              s.one(
                  "select * from academic_sessions where school_id=? and id=? for update",
                  school,
                  id);
          expectVersion(version, ((Number) old.get("version")).longValue());
          SchoolService.dates(b.startsOn(), b.endsOn());
          if (s.db.queryForObject(
                      "select count(*) from terms where session_id=? and (starts_on<? or ends_on>?)",
                      Integer.class,
                      id,
                      b.startsOn(),
                      b.endsOn())
                  > 0
              || s.db.queryForObject(
                      "select count(*) from enrolments where session_id=? and (starts_on<? or starts_on>? or ends_on>?)",
                      Integer.class,
                      id,
                      b.startsOn(),
                      b.endsOn(),
                      b.endsOn())
                  > 0)
            throw fail(
                422,
                "SESSION_HAS_OUTSIDE_RECORDS",
                "Keep all existing term and enrolment dates within the session.");
          if (s.db.queryForObject(
                  "select count(*) from calendar_exceptions where school_id=? and on_date between ? and ? and (on_date<? or on_date>?)",
                  Integer.class,
                  school,
                  old.get("starts_on"),
                  old.get("ends_on"),
                  b.startsOn(),
                  b.endsOn())
              > 0)
            throw fail(
                422,
                "SESSION_HAS_CALENDAR_DATES",
                "Keep accepted calendar dates within their session.");
          s.db.update(
              "update academic_sessions set name=?,starts_on=?,ends_on=?,version=version+1 where id=?",
              text(b.name(), 4, 40, "Session name"),
              b.startsOn(),
              b.endsOn(),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "ACADEMIC_SESSION_UPDATED",
              "ACADEMIC_SESSION",
              id,
              text(b.reason(), 5, 500, "Change reason"),
              map("before", old, "startsOn", b.startsOn(), "endsOn", b.endsOn()));
          return map(
              "id",
              id,
              "version",
              version + 1,
              "name",
              b.name(),
              "startsOn",
              b.startsOn(),
              "endsOn",
              b.endsOn());
        });
  }

  public Object term(Actor a, UUID school, UUID id, Dates b, long version, String key) {
    return command(
        a,
        school,
        "terms/" + id,
        b,
        version,
        key,
        () -> {
          s.lockPlacements(school);
          UUID session =
              (UUID)
                  s.one("select session_id from terms where school_id=? and id=?", school, id)
                      .get("session_id");
          var parent =
              s.one(
                  "select * from academic_sessions where school_id=? and id=? for update",
                  school,
                  session);
          var old = s.one("select * from terms where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) old.get("version")).longValue());
          SchoolService.dates(b.startsOn(), b.endsOn());
          if (b.startsOn().isBefore(((java.sql.Date) parent.get("starts_on")).toLocalDate())
              || b.endsOn().isAfter(((java.sql.Date) parent.get("ends_on")).toLocalDate()))
            throw fail(422, "INVALID_TERM", "Keep the term inside its academic session.");
          boolean changed =
              !b.startsOn().equals(((java.sql.Date) old.get("starts_on")).toLocalDate())
                  || !b.endsOn().equals(((java.sql.Date) old.get("ends_on")).toLocalDate());
          if (changed
              && (s.db.queryForObject(
                          "select count(*) from timetable_plans where term_id=?", Integer.class, id)
                      > 0
                  || s.db.queryForObject(
                          "select count(*) from exam_schedules where term_id=?", Integer.class, id)
                      > 0
                  || s.db.queryForObject(
                          "select count(*) from assessment_cycles where term_id=?",
                          Integer.class,
                          id)
                      > 0
                  || s.db.queryForObject(
                          "select count(*) from attendance_registers r join classes c on c.id=r.class_id where c.session_id=? and r.on_date between ? and ?",
                          Integer.class,
                          session,
                          old.get("starts_on"),
                          old.get("ends_on"))
                      > 0))
            throw fail(
                409,
                "TERM_DATES_IN_USE",
                "This term already has dated school work. Preserve its dates and use a reviewed calendar revision for closures or exceptions.");
          s.db.update(
              "update terms set starts_on=?,ends_on=?,version=version+1 where id=?",
              b.startsOn(),
              b.endsOn(),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "TERM_DATES_UPDATED",
              "TERM",
              id,
              text(b.reason(), 5, 500, "Change reason"),
              map("before", old, "startsOn", b.startsOn(), "endsOn", b.endsOn()));
          return map(
              "id",
              id,
              "sessionId",
              session,
              "number",
              old.get("number"),
              "version",
              version + 1,
              "startsOn",
              b.startsOn(),
              "endsOn",
              b.endsOn());
        });
  }

  public record ActiveSession(UUID sessionId, String reason) {}

  public Object activeSession(Actor a, UUID school, ActiveSession b, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "active-academic-session",
        b,
        version,
        key,
        () -> {
          var old = s.one("select * from schools where id=? for update", school);
          expectVersion(version, ((Number) old.get("version")).longValue());
          s.one(
              "select id from academic_sessions where school_id=? and id=?", school, b.sessionId());
          s.db.update(
              "update schools set active_session_id=?,version=version+1 where id=?",
              b.sessionId(),
              school);
          s.commands.audit(
              school,
              a.accountId(),
              "ACTIVE_SESSION_PREFERENCE_CHANGED",
              "SCHOOL",
              school,
              text(b.reason(), 5, 500, "Change reason"),
              map("previousSessionId", old.get("active_session_id"), "sessionId", b.sessionId()));
          return map(
              "activeSessionId",
              b.sessionId(),
              "version",
              version + 1,
              "displayPreferenceOnly",
              true);
        });
  }

  public record Subject(String name, boolean active, String reason) {}

  public Object subject(Actor a, UUID school, UUID id, Subject b, long version, String key) {
    return command(
        a,
        school,
        "subjects/" + id,
        b,
        version,
        key,
        () -> {
          var old =
              s.one("select * from subjects where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) old.get("version")).longValue());
          if (!b.active()
              && (s.db.queryForObject(
                          "select count(*) from teaching_assignments t join classes c on c.id=t.class_id join academic_sessions y on y.id=c.session_id where t.school_id=? and t.subject_id=? and t.active and y.ends_on>=current_date",
                          Integer.class,
                          school,
                          id)
                      > 0
                  || s.db.queryForObject(
                          "select count(*) from curriculum_templates c join academic_sessions y on y.id=c.session_id where c.school_id=? and y.ends_on>=current_date and c.definition @> ?::jsonb",
                          Integer.class,
                          school,
                          s.json.write(List.of(map("subjectId", id))))
                      > 0))
            throw fail(
                409,
                "SUBJECT_IN_USE",
                "This subject is still used by a current or future teaching assignment or curriculum.");
          s.db.update(
              "update subjects set name=?,active=?,version=version+1 where id=?",
              text(b.name(), 2, 100, "Subject name"),
              b.active(),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "SUBJECT_UPDATED",
              "SUBJECT",
              id,
              text(b.reason(), 5, 500, "Change reason"),
              map("before", old, "active", b.active(), "name", b.name()));
          return s.one(
              "select id,code,name,core,active,version from subjects where school_id=? and id=?",
              school,
              id);
        });
  }

  public Object rooms(Actor a, UUID school, Boolean active, UUID cursor, int limit) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    return page(
        s.db.queryForList(
            "select id,code,name,capacity,active,version from school_rooms where school_id=? and (?::boolean is null or active=?::boolean) and (?::uuid is null or id>?::uuid) order by id limit ?",
            school,
            active,
            active,
            cursor,
            cursor,
            limit + 1),
        limit,
        r -> r.get("id").toString());
  }

  public record Room(String code, String name, Integer capacity, boolean active, String reason) {}

  public Object room(Actor a, UUID school, UUID id, Room b, long version, String key) {
    return command(
        a,
        school,
        "rooms/" + Objects.toString(id, "new"),
        b,
        version,
        key,
        () -> {
          if (b.capacity() != null && (b.capacity() < 1 || b.capacity() > 5000))
            throw fail(422, "INVALID_CAPACITY", "Room capacity must be between one and 5,000.");
          String code = text(b.code(), 1, 40, "Room code").toUpperCase(Locale.ROOT),
              name = text(b.name(), 1, 100, "Room name");
          UUID target = id == null ? UUID.randomUUID() : id;
          Map<String, Object> old =
              id == null
                  ? Map.of()
                  : s.one(
                      "select * from school_rooms where school_id=? and id=? for update",
                      school,
                      id);
          if (id != null) {
            expectVersion(version, ((Number) old.get("version")).longValue());
            if (!code.equals(old.get("code")))
              throw fail(
                  422, "ROOM_CODE_IMMUTABLE", "Keep the room code; edit its display name instead.");
          }
          if (!b.active()
              && (s.db.queryForObject(
                          "select count(*) from classes c join academic_sessions y on y.id=c.session_id where c.school_id=? and upper(btrim(c.room))=? and y.ends_on>=current_date",
                          Integer.class,
                          school,
                          code)
                      > 0
                  || s.db.queryForObject(
                          "select count(*) from lessons l join timetable_plans p on p.id=l.plan_id join terms t on t.id=p.term_id where l.school_id=? and upper(btrim(l.room))=? and t.ends_on>=current_date",
                          Integer.class,
                          school,
                          code)
                      > 0
                  || s.db.queryForObject(
                          "select count(*) from exam_events e join exam_schedules x on x.id=e.schedule_id join terms t on t.id=x.term_id where e.school_id=? and upper(btrim(e.room))=? and t.ends_on>=current_date",
                          Integer.class,
                          school,
                          code)
                      > 0))
            throw fail(
                409,
                "ROOM_IN_USE",
                "This room is still referenced by a current or future class or schedule.");
          if (id == null)
            s.db.update(
                "insert into school_rooms(id,school_id,code,name,capacity,active) values (?,?,?,?,?,?)",
                target,
                school,
                code,
                name,
                b.capacity(),
                b.active());
          else
            s.db.update(
                "update school_rooms set name=?,capacity=?,active=?,version=version+1 where id=?",
                name,
                b.capacity(),
                b.active(),
                target);
          s.commands.audit(
              school,
              a.accountId(),
              id == null ? "ROOM_CREATED" : "ROOM_UPDATED",
              "ROOM",
              target,
              text(b.reason(), 5, 500, "Room change reason"),
              map("before", old, "code", code, "active", b.active()));
          return s.one(
              "select id,code,name,capacity,active,version from school_rooms where school_id=? and id=?",
              school,
              target);
        });
  }
}
