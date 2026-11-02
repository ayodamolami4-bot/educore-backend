package com.educore.communication;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CommunicationService {
  final JdbcTemplate db;
  final Commands commands;
  final Json json;
  final Crypto crypto;
  final ServiceHttp http;
  final String identity, academics;

  public CommunicationService(
      JdbcTemplate db,
      Commands commands,
      Json json,
      Crypto crypto,
      ServiceHttp http,
      @Value("${educore.identity-url}") String identity,
      @Value("${educore.academics-url}") String academics) {
    this.db = db;
    this.commands = commands;
    this.json = json;
    this.crypto = crypto;
    this.http = http;
    this.identity = identity;
    this.academics = academics;
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "The communication record is unavailable.");
    return rows.get(0);
  }

  void child(Actor a, UUID school, UUID student) {
    var c = a.school(school, "PARENT", "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    UUID guardian = null;
    if ("PARENT".equals(c.role())) {
      a.child(student, false);
      guardian = c.subjectId();
    }
    http.post(
        academics,
        "/internal/student-context",
        map("schoolId", school, "studentId", student, "guardianId", guardian),
        Object.class);
  }

  public Object notifications(
      Actor a, UUID school, String category, boolean unread, UUID cursor, int limit) {
    a.school(school);
    if ("PARENT".equals(a.context().role())) child(a, school, a.context().childId());
    if (category != null
        && !Set.of("SCHOOL", "LEARNING", "FEES", "MESSAGES", "CASES").contains(category))
      throw fail(422, "INVALID_CATEGORY", "Choose a supported notification category.");
    var rows =
        db
            .queryForList(
                "select * from notifications where account_id=? and school_id=? and (?::text is null or category=?::text) and (not ? or read_at is null) and (?::uuid is null or id<?::uuid) and (?::uuid is null or child_id is null or child_id=?::uuid) order by id desc limit ?",
                a.accountId(),
                school,
                category,
                category,
                unread,
                cursor,
                cursor,
                "PARENT".equals(a.context().role()) ? a.context().childId() : null,
                "PARENT".equals(a.context().role()) ? a.context().childId() : null,
                limit + 1)
            .stream()
            .map(
                n ->
                    map(
                        "id",
                        n.get("id"),
                        "version",
                        n.get("version"),
                        "schoolId",
                        n.get("school_id"),
                        "category",
                        n.get("category"),
                        "title",
                        n.get("title"),
                        "safePreview",
                        n.get("safe_preview"),
                        "createdAt",
                        n.get("created_at"),
                        "readAt",
                        n.get("read_at"),
                        "target",
                        json.value(n.get("target").toString())))
            .toList();
    return page(rows, limit, n -> n.get("id").toString());
  }

  public Object read(Actor a, UUID id) {
    var n =
        one(
            "select school_id,child_id from notifications where id=? and account_id=?",
            id,
            a.accountId());
    a.school((UUID) n.get("school_id"));
    if ("PARENT".equals(a.context().role()) && n.get("child_id") != null)
      child(a, (UUID) n.get("school_id"), (UUID) n.get("child_id"));
    db.update(
        "update notifications set read_at=coalesce(read_at,now()),version=version+case when read_at is null then 1 else 0 end where id=? and account_id=?",
        id,
        a.accountId());
    return map("accepted", true, "message", "Marked as read.", "requestId", UUID.randomUUID());
  }

  public Object event(UUID event, String kind, JsonNode payload) {
    Actor.internalOnly("identity", "academics", "finance");
    String caller = Actor.internalCaller();
    if ("PRIVATE_EMAIL".equals(kind) && !"identity".equals(caller))
      throw fail(403, "SERVICE_FORBIDDEN", "Only identity can issue authentication mail.");
    if ("CHILD_NOTIFICATION".equals(kind) && !Set.of("academics", "finance").contains(caller))
      throw fail(403, "SERVICE_FORBIDDEN", "This service cannot publish child events.");
    return commands.transaction(
        () -> {
          int inserted =
              db.update(
                  "insert into inbox(event_id,kind) values (?,?) on conflict do nothing",
                  event,
                  kind);
          if (inserted == 0) return map("accepted", true);
          switch (kind) {
            case "SCHOOL_NOTIFICATION" -> {
              Actor.internalOnly("academics");
              UUID school = uuid(payload.path("schoolId").asText());
              @SuppressWarnings("unchecked")
              Map<String, Object> audience =
                  http.post(
                      identity, "/internal/school-audience", map("schoolId", school), Map.class);
              for (Object item : (List<?>) audience.get("items")) {
                Map<?, ?> recipient = (Map<?, ?>) item;
                String role = recipient.get("role").toString();
                String home =
                    switch (role) {
                      case "TEACHER" -> "TE01";
                      case "STUDENT" -> "ST01";
                      case "PARENT" -> "PA01";
                      default -> "AD01";
                    };
                notify(
                    event,
                    uuid(recipient.get("accountId").toString()),
                    school,
                    null,
                    "SCHOOL",
                    text(payload.path("title").asText(), 1, 160, "Notification title"),
                    text(payload.path("safePreview").asText(), 1, 240, "Safe preview"),
                    map("screenId", home, "schoolId", school));
              }
            }
            case "PRIVATE_EMAIL" -> {
              String to = email(payload.path("to").asText()),
                  template = payload.path("template").asText();
              if (!Set.of("RESET", "VERIFY_EMAIL").contains(template)
                  || !payload.path("token").asText().matches("[A-Za-z0-9_-]{40,100}"))
                throw fail(422, "INVALID_MAIL_EVENT", "Unsupported private mail event.");
              db.update(
                  "insert into private_mail(id,event_id,payload_cipher) values (?,?,?)",
                  UUID.randomUUID(),
                  event,
                  crypto.seal(json.write(payload)));
            }
            case "CHILD_NOTIFICATION" -> {
              UUID school = uuid(payload.path("schoolId").asText()),
                  child = uuid(payload.path("childId").asText());
              @SuppressWarnings("unchecked")
              Map<String, Object> audience =
                  http.post(
                      identity,
                      "/internal/audience",
                      map(
                          "schoolId",
                          school,
                          "childId",
                          child,
                          "category",
                          payload.path("category").asText()),
                      Map.class);
              String category = payload.path("category").asText();
              if (!Set.of("SCHOOL", "LEARNING", "FEES", "MESSAGES", "CASES").contains(category))
                throw fail(422, "INVALID_CATEGORY", "Invalid event category.");
              for (Object value : (List<?>) audience.get("recipients")) {
                Map<?, ?> recipient = (Map<?, ?>) value;
                Object target = payload.path("target");
                if ("STUDENT".equals(recipient.get("role")))
                  target =
                      map(
                          "screenId",
                          "ST01",
                          "schoolId",
                          school,
                          "childId",
                          child,
                          "resourceId",
                          payload.path("target").path("resourceId").asText());
                notify(
                    event,
                    uuid(recipient.get("accountId").toString()),
                    school,
                    child,
                    category,
                    text(payload.path("title").asText(), 1, 160, "Notification title"),
                    text(payload.path("safePreview").asText(), 1, 240, "Safe preview"),
                    target);
              }
            }
            default -> throw fail(422, "UNKNOWN_EVENT", "Unsupported communication event.");
          }
          return map("accepted", true);
        });
  }

  void notify(
      UUID event,
      UUID account,
      UUID school,
      UUID child,
      String category,
      String title,
      String preview,
      Object target) {
    db.update(
        "insert into notifications(id,event_id,account_id,school_id,child_id,category,title,safe_preview,target) values (?,?,?,?,?,?,?,?,?::jsonb) on conflict(event_id,account_id) do nothing",
        UUID.randomUUID(),
        event,
        account,
        school,
        child,
        category,
        title,
        preview,
        json.write(target));
  }

  public record NoticeInput(String title, String body) {}

  public Object notice(Actor a, UUID school, NoticeInput b, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    String title = text(b.title(), 2, 160, "Notice title"),
        body = text(b.body(), 2, 8000, "Notice body");
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "notices",
        key,
        b,
        () -> {
          UUID id = UUID.randomUUID();
          db.update(
              "insert into notices(id,school_id,title,body,published_by) values (?,?,?,?,?)",
              id,
              school,
              title,
              body,
              a.accountId());
          commands.audit(school, a.accountId(), "NOTICE_PUBLISHED", "NOTICE", id, null, Map.of());
          return map("id", id, "version", 1, "published", true);
        });
  }

  public Object notices(Actor a, UUID school, UUID cursor, int limit) {
    a.school(school);
    var rows =
        db.queryForList(
            "select id,title,body,published_at as \"publishedAt\",version from notices where school_id=? and (?::uuid is null or id<?::uuid) order by id desc limit ?",
            school,
            cursor,
            cursor,
            limit + 1);
    return page(rows, limit, n -> n.get("id").toString());
  }

  public record Complaint(UUID studentId, String category, String subject, String body) {}

  public Object complaint(Actor a, UUID school, Complaint b, String key) {
    a.school(school, "PARENT");
    child(a, school, b.studentId());
    if (b.category() == null
        || !Set.of("FEES", "ATTENDANCE", "ACADEMIC_RECORDS", "STAFF_CONDUCT", "GENERAL")
            .contains(b.category()))
      throw fail(422, "INVALID_CATEGORY", "Choose a supported complaint category.");
    String subject = text(b.subject(), 3, 160, "Complaint subject"),
        body = text(b.body(), 5, 8000, "Complaint description");
    @SuppressWarnings("unchecked")
    Map<String, Object> calendar =
        http.post(academics, "/internal/response-target", map("schoolId", school), Map.class);
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "complaints",
        key,
        b,
        () -> {
          UUID id = UUID.randomUUID();
          String due = (String) calendar.get("firstResponseDueAt");
          db.update(
              "insert into complaints(id,school_id,student_id,requester_id,category,subject,first_response_due_at) values (?,?,?,?,?,?,?::timestamptz)",
              id,
              school,
              b.studentId(),
              a.accountId(),
              b.category(),
              subject,
              due);
          db.update(
              "insert into case_entries(id,case_id,author_id,visibility,body) values (?,?,?,'PUBLIC',?)",
              UUID.randomUUID(),
              id,
              a.accountId(),
              body);
          commands.audit(
              school,
              a.accountId(),
              "COMPLAINT_CREATED",
              "COMPLAINT",
              id,
              null,
              map("category", b.category()));
          return caseDetail(a, school, id);
        });
  }

  private void permission(Actor a, UUID school, Map<String, Object> c) {
    var context = a.school(school, "PARENT", "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    String category = (String) c.get("category");
    boolean allowed =
        switch (context.role()) {
          case "PRINCIPAL" -> true;
          case "PARENT" -> a.accountId().equals(c.get("requester_id"));
          case "ACCOUNTS" -> "FEES".equals(category);
          case "SCHOOL_OFFICE" ->
              Set.of("GENERAL", "ATTENDANCE", "ACADEMIC_RECORDS").contains(category);
          default -> false;
        };
    if (!allowed) throw fail(404, "NOT_FOUND", "This case is unavailable.");
    if ("PARENT".equals(context.role())) child(a, school, (UUID) c.get("student_id"));
  }

  public Object caseDetail(Actor a, UUID school, UUID id) {
    var c = one("select * from complaints where school_id=? and id=?", school, id);
    permission(a, school, c);
    boolean parent = "PARENT".equals(a.context().role());
    return map(
        "id",
        id,
        "version",
        c.get("version"),
        "studentId",
        c.get("student_id"),
        "category",
        c.get("category"),
        "subject",
        c.get("subject"),
        "state",
        c.get("state"),
        "firstResponseDueAt",
        c.get("first_response_due_at"),
        "calendarConfigured",
        c.get("first_response_due_at") != null,
        "firstResponseAt",
        c.get("first_response_at"),
        "createdAt",
        c.get("created_at"),
        "entries",
        db.queryForList(
            "select id,author_id as \"authorId\",visibility,body,created_at as \"createdAt\" from case_entries where case_id=? and (not ? or visibility='PUBLIC') order by created_at,id",
            id,
            parent));
  }

  public Object cases(Actor a, UUID school, UUID cursor, int limit) {
    var context = a.school(school, "PARENT", "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    String role = context.role();
    UUID requester = "PARENT".equals(role) ? a.accountId() : null;
    var rows =
        db.queryForList(
            "select id,student_id as \"studentId\",category,subject,state,version,created_at as \"createdAt\",first_response_due_at as \"firstResponseDueAt\" from complaints where school_id=? and (?::uuid is null or requester_id=?::uuid) and (?<>'ACCOUNTS' or category='FEES') and (?<>'SCHOOL_OFFICE' or category in ('GENERAL','ATTENDANCE','ACADEMIC_RECORDS')) and (?::uuid is null or id<?::uuid) order by id desc limit ?",
            school,
            requester,
            requester,
            role,
            role,
            cursor,
            cursor,
            limit + 1);
    return page(rows, limit, n -> n.get("id").toString());
  }

  public record Reply(String body, boolean internal) {}

  public Object reply(Actor a, UUID school, UUID id, Reply b, long version, String key) {
    var before = one("select * from complaints where school_id=? and id=?", school, id);
    permission(a, school, before);
    String body = text(b.body(), 2, 8000, "Reply");
    boolean parent = "PARENT".equals(a.context().role());
    if (parent && b.internal())
      throw fail(403, "INTERNAL_NOTE_FORBIDDEN", "Parents cannot create staff-only notes.");
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "complaints/" + id + "/replies",
        key,
        map("reply", b, "version", version),
        () -> {
          var c = one("select * from complaints where id=? for update", id);
          expectVersion(version, ((Number) c.get("version")).longValue());
          if ("CLOSED".equals(c.get("state")))
            throw fail(409, "CASE_CLOSED", "This case is closed.");
          UUID reply = UUID.randomUUID();
          db.update(
              "insert into case_entries(id,case_id,author_id,visibility,body) values (?,?,?,?,?)",
              reply,
              id,
              a.accountId(),
              b.internal() ? "INTERNAL" : "PUBLIC",
              body);
          db.update(
              "update complaints set version=version+1,first_response_at=case when ? then coalesce(first_response_at,now()) else first_response_at end,state=case when ? then 'IN_PROGRESS' else state end where id=?",
              !parent && !b.internal(),
              !parent && !b.internal(),
              id);
          if (!parent && !b.internal())
            notify(
                reply,
                (UUID) c.get("requester_id"),
                school,
                (UUID) c.get("student_id"),
                "CASES",
                "School case update",
                "Your school replied to your case.",
                map(
                    "screenId",
                    "PA01",
                    "schoolId",
                    school,
                    "childId",
                    c.get("student_id"),
                    "resourceId",
                    id));
          return caseDetail(a, school, id);
        });
  }

  public Object close(Actor a, UUID school, UUID id, String reason, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    var c = one("select * from complaints where school_id=? and id=?", school, id);
    permission(a, school, c);
    String why = text(reason, 5, 2000, "Closure reason");
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "complaints/" + id + "/close",
        key,
        map("reason", why, "version", version),
        () -> {
          var current = one("select * from complaints where id=? for update", id);
          expectVersion(version, ((Number) current.get("version")).longValue());
          if ("CLOSED".equals(current.get("state")))
            throw fail(409, "CASE_CLOSED", "This case is already closed.");
          UUID entry = UUID.randomUUID();
          db.update(
              "insert into case_entries(id,case_id,author_id,visibility,body) values (?,?,?,'PUBLIC',?)",
              entry,
              id,
              a.accountId(),
              why);
          db.update(
              "update complaints set state='CLOSED',closed_at=now(),first_response_at=coalesce(first_response_at,now()),version=version+1 where id=?",
              id);
          commands.audit(school, a.accountId(), "COMPLAINT_CLOSED", "COMPLAINT", id, why, Map.of());
          notify(
              entry,
              (UUID) c.get("requester_id"),
              school,
              (UUID) c.get("student_id"),
              "CASES",
              "School case closed",
              "Your school posted a case resolution.",
              map(
                  "screenId",
                  "PA01",
                  "schoolId",
                  school,
                  "childId",
                  c.get("student_id"),
                  "resourceId",
                  id));
          return caseDetail(a, school, id);
        });
  }
}
