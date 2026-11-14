package com.educore.communication;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CommunicationWorkService {
  private final CommunicationService s;

  public CommunicationWorkService(CommunicationService s) {
    this.s = s;
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object body,
      java.util.function.Supplier<Object> action) {
    a.school(school);
    return s.commands.execute(a.accountId().toString(), school.toString(), path, key, body, action);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> context(Actor a, UUID school, UUID student, String category) {
    return s.http.post(
        s.academics,
        "/internal/communication-context",
        map("schoolId", school, "actor", a, "studentId", student, "category", category),
        Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> staff(UUID school, UUID id, String preferred) {
    return s.http.post(
        s.academics,
        "/internal/staff-context",
        map("schoolId", school, "staffId", id, "preferredRole", preferred),
        Map.class);
  }

  @SuppressWarnings("unchecked")
  private UUID account(UUID school, UUID subject, String role, UUID student) {
    Map<String, Object> p =
        s.http.post(
            s.identity,
            "/internal/subject-account",
            map("schoolId", school, "subjectId", subject, "role", role, "childId", student),
            Map.class);
    return uuid(p.get("accountId").toString());
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> audience(UUID school) {
    Map<String, Object> p =
        s.http.post(s.identity, "/internal/school-audience", map("schoolId", school), Map.class);
    return (List<Map<String, Object>>) p.get("items");
  }

  @SuppressWarnings("unchecked")
  private void attach(
      Actor a,
      UUID school,
      List<UUID> files,
      String resource,
      UUID id,
      UUID student,
      UUID classId) {
    if (files == null) return;
    if (files.size() > 5 || new HashSet<>(files).size() != files.size())
      throw fail(
          422, "INVALID_ATTACHMENTS", "Attach at most five distinct ready PDF, JPG or PNG files.");
    for (UUID file : files) {
      Map<String, Object> response =
          s.http.post(
              s.academics,
              "/internal/file-context",
              map("schoolId", school, "fileId", file, "ownerId", a.accountId()),
              Map.class);
      Map<String, Object> f = (Map<String, Object>) response.get("file");
      if (!"COMMUNICATION".equals(f.get("purpose"))
          || !"READY".equals(f.get("state"))
          || (student != null && !student.toString().equals(f.get("studentId")))
          || (student == null && f.get("studentId") != null)
          || (classId != null
              && f.get("classId") != null
              && !classId.toString().equals(f.get("classId"))))
        throw fail(
            422,
            "ATTACHMENT_SCOPE_MISMATCH",
            "Use a ready communication attachment uploaded for this child or notice scope.");
      s.db.update(
          "insert into communication_attachments(school_id,file_id,resource_type,resource_id) values (?,?,?,?)",
          school,
          file,
          resource,
          id);
    }
  }

  private List<Map<String, Object>> files(String resource, UUID id) {
    return s.db.queryForList(
        "select file_id as id from communication_attachments where resource_type=? and resource_id=? order by file_id",
        resource,
        id);
  }

  private void notify(
      UUID event,
      UUID recipient,
      UUID school,
      UUID student,
      String category,
      UUID resource,
      String home,
      String title) {
    s.notify(
        event,
        recipient,
        school,
        student,
        category,
        title,
        "You have a school update. Open EduCore to view it.",
        map("screenId", home, "schoolId", school, "childId", student, "resourceId", resource));
  }

  public record Notice(
      String title,
      String body,
      String scope,
      UUID classId,
      String department,
      List<UUID> fileIds) {}

  private void validateNotice(UUID school, Notice b) {
    text(b.title(), 2, 160, "Notice title");
    text(b.body(), 2, 8000, "Notice body");
    if (b.scope() == null
        || !Set.of("SCHOOL", "CLASS", "DEPARTMENT").contains(b.scope())
        || ("SCHOOL".equals(b.scope()) && (b.classId() != null || b.department() != null))
        || (!"SCHOOL".equals(b.scope()) && b.classId() == null)
        || ("CLASS".equals(b.scope()) && b.department() != null)
        || ("DEPARTMENT".equals(b.scope())
            && (b.department() == null
                || !Set.of("SCIENCE", "ARTS", "COMMERCIAL").contains(b.department()))))
      throw fail(
          422,
          "INVALID_NOTICE_SCOPE",
          "Choose the school, one class, or one class department as the notice audience.");
    if (b.classId() != null)
      s.http.post(
          s.academics,
          "/internal/notice-subjects",
          map("schoolId", school, "classId", b.classId(), "department", b.department()),
          Map.class);
  }

  private void noticeRead(Actor a, UUID school, Map<String, Object> n) {
    var c = a.school(school);
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(c.role())) return;
    if (n.get("published_at") == null)
      throw fail(404, "NOT_FOUND", "This notice is not published.");
    if (Set.of("PARENT", "STUDENT").contains(c.role())) context(a, school, null, "LEARNING");
    if ("SCHOOL".equals(n.get("scope"))) return;
    var access = context(a, school, null, "LEARNING");
    if (!((List<?>) access.get("classIds")).contains(n.get("class_id").toString())
        || ("PARENT".equals(c.role()) || "STUDENT".equals(c.role()))
            && n.get("department") != null
            && !n.get("department").equals(access.get("department")))
      throw fail(404, "NOT_FOUND", "This notice audience is unavailable.");
  }

  public Object saveNotice(Actor a, UUID school, UUID id, Notice b, Long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    validateNotice(school, b);
    return command(
        a,
        school,
        "notices/" + Objects.toString(id, "new"),
        key,
        map("input", b, "version", version),
        () -> {
          UUID notice = id == null ? UUID.randomUUID() : id;
          if (id == null)
            s.db.update(
                "insert into notices(id,school_id,title,body,state,scope,class_id,department,created_by) values (?,?,?,?,'DRAFT',?,?,?,?)",
                notice,
                school,
                b.title().strip(),
                b.body().strip(),
                b.scope(),
                b.classId(),
                b.department(),
                a.accountId());
          else {
            var old =
                s.one("select * from notices where school_id=? and id=? for update", school, id);
            expectVersion(version, ((Number) old.get("version")).longValue());
            if (!"DRAFT".equals(old.get("state")))
              throw fail(
                  409,
                  "NOTICE_PUBLISHED",
                  "Create a new notice for changed published instructions.");
            s.db.update(
                "update notices set title=?,body=?,scope=?,class_id=?,department=?,version=version+1 where id=?",
                b.title().strip(),
                b.body().strip(),
                b.scope(),
                b.classId(),
                b.department(),
                id);
            s.db.update(
                "delete from communication_attachments where resource_type='NOTICE' and resource_id=?",
                id);
          }
          attach(a, school, b.fileIds(), "NOTICE", notice, null, b.classId());
          return notice(a, school, notice);
        });
  }

  public Object notice(Actor a, UUID school, UUID id) {
    var n = s.one("select * from notices where school_id=? and id=?", school, id);
    noticeRead(a, school, n);
    return map(
        "id",
        id,
        "title",
        n.get("title"),
        "body",
        n.get("body"),
        "scope",
        n.get("scope"),
        "classId",
        n.get("class_id"),
        "department",
        n.get("department"),
        "state",
        n.get("state"),
        "publishedAt",
        n.get("published_at"),
        "version",
        n.get("version"),
        "files",
        files("NOTICE", id));
  }

  public Object notices(Actor a, UUID school, UUID cursor, int limit) {
    var c = a.school(school);
    boolean admin = Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(c.role());
    var access =
        admin
            ? Map.<String, Object>of("classIds", List.of())
            : context(a, school, null, "LEARNING");
    var rows =
        s.db.queryForList(
            "select id from notices where school_id=? and (? or (state='PUBLISHED' and (scope='SCHOOL' or (class_id in(select value::uuid from jsonb_array_elements_text(?::jsonb)) and (? not in ('PARENT','STUDENT') or department is null or department=?::text))))) and (?::uuid is null or id<?::uuid) order by id desc limit ?",
            school,
            admin,
            s.json.write(access.get("classIds")),
            c.role(),
            access.get("department"),
            cursor,
            cursor,
            limit + 1);
    var out = new ArrayList<Map<String, Object>>();
    for (var r : rows) {
      @SuppressWarnings("unchecked")
      var n = (Map<String, Object>) notice(a, school, (UUID) r.get("id"));
      out.add(n);
    }
    return page(out, limit, r -> r.get("id").toString());
  }

  public Object publishNotice(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "notices/" + id + "/publish",
        key,
        map("version", version),
        () -> {
          var n = s.one("select * from notices where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) n.get("version")).longValue());
          if (!"DRAFT".equals(n.get("state")))
            throw fail(409, "NOTICE_NOT_DRAFT", "Only a reviewed draft notice can be published.");
          s.db.update(
              "update notices set state='PUBLISHED',published_by=?,published_at=now(),version=version+1 where id=?",
              a.accountId(),
              id);
          Set<String> students = new HashSet<>(), teachers = new HashSet<>();
          if (n.get("class_id") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> target =
                s.http.post(
                    s.academics,
                    "/internal/notice-subjects",
                    map(
                        "schoolId",
                        school,
                        "classId",
                        n.get("class_id"),
                        "department",
                        n.get("department")),
                    Map.class);
            for (Object x : (List<?>) target.get("studentIds")) students.add(x.toString());
            for (Object x : (List<?>) target.get("teacherIds")) teachers.add(x.toString());
          }
          for (var r : audience(school)) {
            String role = r.get("role").toString();
            boolean allowed =
                n.get("class_id") == null
                    || Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(role)
                    || ("TEACHER".equals(role)
                        && teachers.contains(Objects.toString(r.get("subjectId"), "")))
                    || ("STUDENT".equals(role)
                        && students.contains(Objects.toString(r.get("subjectId"), "")))
                    || ("PARENT".equals(role)
                        && Boolean.TRUE.equals(r.get("learningAccess"))
                        && students.contains(Objects.toString(r.get("childId"), "")));
            if (allowed)
              notify(
                  id,
                  uuid(r.get("accountId").toString()),
                  school,
                  null,
                  "SCHOOL",
                  id,
                  switch (role) {
                    case "PARENT" -> "PA01";
                    case "STUDENT" -> "ST01";
                    case "TEACHER" -> "TE01";
                    default -> "AD01";
                  },
                  "School notice published");
          }
          s.commands.audit(
              school,
              a.accountId(),
              "NOTICE_PUBLISHED",
              "NOTICE",
              id,
              null,
              map("scope", n.get("scope")));
          return notice(a, school, id);
        });
  }

  public Object archiveNotice(
      Actor a, UUID school, UUID id, String reason, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "notices/" + id + "/archive",
        key,
        map("reason", reason, "version", version),
        () -> {
          var n = s.one("select * from notices where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) n.get("version")).longValue());
          if ("ARCHIVED".equals(n.get("state")))
            throw fail(409, "NOTICE_ARCHIVED", "This notice is already archived.");
          s.db.update("update notices set state='ARCHIVED',version=version+1 where id=?", id);
          s.commands.audit(
              school,
              a.accountId(),
              "NOTICE_ARCHIVED",
              "NOTICE",
              id,
              text(reason, 5, 500, "Archive reason"),
              Map.of());
          return notice(a, school, id);
        });
  }

  public Object contacts(Actor a, UUID school, UUID student) {
    a.school(school, "PARENT", "TEACHER", "PRINCIPAL", "SCHOOL_OFFICE");
    var c = context(a, school, student, "LEARNING");
    if ("TEACHER".equals(a.context().role())
        && !a.context().subjectId().toString().equals(c.get("classTeacherId")))
      throw fail(
          404, "NOT_FOUND", "Only the current class teacher can open this parent's conversation.");

    var result = new LinkedHashMap<String, Object>(c);
    if (c.get("classTeacherId") != null) {
      var teacher = staff(school, uuid(c.get("classTeacherId").toString()), null);
      result.put("classTeacherName", teacher.get("fullName"));
    }
    var office = staff(school, null, "SCHOOL_OFFICE");
    result.put("officeName", office.get("fullName"));
    return result;
  }

  public record ThreadInput(
      UUID studentId,
      UUID guardianId,
      String target,
      String subject,
      String body,
      List<UUID> fileIds) {}

  private UUID guardian(Actor a, Map<String, Object> c, UUID requested, String category) {
    UUID id = "PARENT".equals(a.context().role()) ? a.context().subjectId() : requested;
    if (id == null) throw fail(422, "GUARDIAN_REQUIRED", "Choose a linked parent or guardian.");
    boolean allowed = false;
    for (Object x : (List<?>) c.get("guardians")) {
      Map<?, ?> g = (Map<?, ?>) x;
      if (id.toString().equals(g.get("id"))
          && ("FEES".equals(category)
              ? Boolean.TRUE.equals(g.get("payerAccess"))
              : Set.of("GENERAL", "STAFF_CONDUCT").contains(category)
                  ? Boolean.TRUE.equals(g.get("learningAccess"))
                      || Boolean.TRUE.equals(g.get("payerAccess"))
                  : Boolean.TRUE.equals(g.get("learningAccess")))) allowed = true;
    }
    if (!allowed) throw fail(404, "NOT_FOUND", "The current guardian relationship is unavailable.");
    return id;
  }

  private void threadRead(Actor a, UUID school, Map<String, Object> t) {
    var c = a.school(school, "PARENT", "TEACHER", "PRINCIPAL", "SCHOOL_OFFICE");
    var scope = context(a, school, (UUID) t.get("student_id"), "LEARNING");
    boolean allowed =
        switch (c.role()) {
          case "PRINCIPAL" -> true;
          case "SCHOOL_OFFICE" -> "OFFICE".equals(t.get("target"));
          case "PARENT" -> a.accountId().equals(t.get("requester_id"));
          case "TEACHER" ->
              "CLASS_TEACHER".equals(t.get("target"))
                  && c.subjectId().equals(t.get("teacher_id"))
                  && c.subjectId().toString().equals(scope.get("classTeacherId"));
          default -> false;
        };
    if (!allowed) throw fail(404, "NOT_FOUND", "This parent conversation is unavailable.");
  }

  private void threadNotification(Actor a, UUID school, Map<String, Object> t, UUID message) {
    if ("PARENT".equals(a.context().role())) {
      UUID subject =
          "CLASS_TEACHER".equals(t.get("target"))
              ? (UUID) t.get("teacher_id")
              : uuid(staff(school, null, "SCHOOL_OFFICE").get("id").toString());
      String role =
          "CLASS_TEACHER".equals(t.get("target"))
              ? "TEACHER"
              : staff(school, subject, null).get("role").toString();
      notify(
          message,
          account(school, subject, role, null),
          school,
          (UUID) t.get("student_id"),
          "MESSAGES",
          (UUID) t.get("id"),
          "TEACHER".equals(role) ? "TE01" : "AD01",
          "Parent message received");
    } else
      notify(
          message,
          (UUID) t.get("requester_id"),
          school,
          (UUID) t.get("student_id"),
          "MESSAGES",
          (UUID) t.get("id"),
          "PA01",
          "School message received");
  }

  public Object createThread(Actor a, UUID school, ThreadInput b, String key) {
    a.school(school, "PARENT", "TEACHER", "PRINCIPAL", "SCHOOL_OFFICE");
    var c = context(a, school, b.studentId(), "LEARNING");
    if (b.target() == null || !Set.of("CLASS_TEACHER", "OFFICE").contains(b.target()))
      throw fail(
          422,
          "INVALID_MESSAGE_TARGET",
          "Choose the child's current class teacher or school office.");
    UUID teacher =
        c.get("classTeacherId") == null ? null : uuid(c.get("classTeacherId").toString());
    if ("CLASS_TEACHER".equals(b.target()) && teacher == null)
      throw fail(
          409,
          "CLASS_TEACHER_NOT_ASSIGNED",
          "The school must assign a class teacher before opening this conversation.");
    if ("TEACHER".equals(a.context().role())
        && (!"CLASS_TEACHER".equals(b.target()) || !a.context().subjectId().equals(teacher)))
      throw fail(
          403,
          "CLASS_TEACHER_REQUIRED",
          "Teachers can open only their current class's parent conversations.");
    if ("SCHOOL_OFFICE".equals(a.context().role()) && !"OFFICE".equals(b.target()))
      throw fail(403, "OFFICE_CONVERSATION_REQUIRED", "School Office opens office conversations.");
    UUID guardian = guardian(a, c, b.guardianId(), "LEARNING"),
        recipient = account(school, guardian, "PARENT", b.studentId());
    return command(
        a,
        school,
        "threads",
        key,
        b,
        () -> {
          UUID id = UUID.randomUUID(), message = UUID.randomUUID();
          s.db.update(
              "insert into parent_threads(id,school_id,student_id,requester_id,guardian_id,target,teacher_id,subject,created_by) values (?,?,?,?,?,?,?,?,?)",
              id,
              school,
              b.studentId(),
              recipient,
              guardian,
              b.target(),
              "CLASS_TEACHER".equals(b.target()) ? teacher : null,
              text(b.subject(), 3, 160, "Message subject"),
              a.accountId());
          s.db.update(
              "insert into thread_messages(id,thread_id,author_id,body) values (?,?,?,?)",
              message,
              id,
              a.accountId(),
              text(b.body(), 2, 8000, "Message"));
          attach(a, school, b.fileIds(), "THREAD_MESSAGE", message, b.studentId(), null);
          threadNotification(
              a, school, s.one("select * from parent_threads where id=?", id), message);
          s.commands.audit(
              school,
              a.accountId(),
              "PARENT_CONVERSATION_OPENED",
              "THREAD",
              id,
              null,
              map("target", b.target(), "studentId", b.studentId()));
          return thread(a, school, id);
        });
  }

  public Object thread(Actor a, UUID school, UUID id) {
    var t = s.one("select * from parent_threads where school_id=? and id=?", school, id);
    threadRead(a, school, t);
    var messages =
        s.db.queryForList(
            "select id,author_id as \"authorId\",body,created_at as \"createdAt\" from thread_messages where thread_id=? order by created_at,id",
            id);
    for (var m : messages) m.put("files", files("THREAD_MESSAGE", (UUID) m.get("id")));
    return map(
        "id",
        id,
        "studentId",
        t.get("student_id"),
        "subject",
        t.get("subject"),
        "target",
        t.get("target"),
        "state",
        t.get("state"),
        "version",
        t.get("version"),
        "messages",
        messages);
  }

  public Object threads(Actor a, UUID school, UUID cursor, int limit) {
    var c = a.school(school, "PARENT", "TEACHER", "PRINCIPAL", "SCHOOL_OFFICE");
    var scope = context(a, school, null, "LEARNING");
    UUID student = "PARENT".equals(c.role()) ? c.childId() : null;
    var rows =
        s.db.queryForList(
            "select t.id,t.student_id as \"studentId\",t.subject,t.target,t.state,t.version,t.created_at as \"createdAt\" from parent_threads t where t.school_id=? and (?='PRINCIPAL' or (?='PARENT' and t.requester_id=? and t.student_id=?::uuid) or (?='SCHOOL_OFFICE' and t.target='OFFICE') or (?='TEACHER' and t.target='CLASS_TEACHER' and t.teacher_id=? and t.student_id in (select value::uuid from jsonb_array_elements_text(?::jsonb)))) and (?::uuid is null or t.id<?::uuid) order by t.id desc limit ?",
            school,
            c.role(),
            c.role(),
            a.accountId(),
            student,
            c.role(),
            c.role(),
            c.subjectId(),
            s.json.write(currentClassChildren(a, school, scope)),
            cursor,
            cursor,
            limit + 1);
    return page(rows, limit, r -> r.get("id").toString());
  }

  @SuppressWarnings("unchecked")
  private List<Object> currentClassChildren(Actor a, UUID school, Map<String, Object> scope) {
    if (!"TEACHER".equals(a.context().role())) return List.of();
    var children = new ArrayList<Object>();
    for (Object cl : (List<?>) scope.get("classIds")) {
      Map<String, Object> p =
          s.http.post(
              s.academics,
              "/internal/notice-subjects",
              map("schoolId", school, "classId", cl),
              Map.class);
      for (Object child : (List<?>) p.get("studentIds")) {
        var c = context(a, school, uuid(child.toString()), "LEARNING");
        if (a.context().subjectId().toString().equals(c.get("classTeacherId"))) children.add(child);
      }
    }
    return children;
  }

  public record Message(String body, List<UUID> fileIds) {}

  public Object message(Actor a, UUID school, UUID id, Message b, long version, String key) {
    var before = s.one("select * from parent_threads where school_id=? and id=?", school, id);
    threadRead(a, school, before);
    return command(
        a,
        school,
        "threads/" + id + "/messages",
        key,
        map("input", b, "version", version),
        () -> {
          var t =
              s.one(
                  "select * from parent_threads where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) t.get("version")).longValue());
          if (!"OPEN".equals(t.get("state")))
            throw fail(409, "CONVERSATION_CLOSED", "This conversation is closed.");
          UUID message = UUID.randomUUID();
          s.db.update(
              "insert into thread_messages(id,thread_id,author_id,body) values (?,?,?,?)",
              message,
              id,
              a.accountId(),
              text(b.body(), 2, 8000, "Message"));
          attach(
              a, school, b.fileIds(), "THREAD_MESSAGE", message, (UUID) t.get("student_id"), null);
          s.db.update("update parent_threads set version=version+1 where id=?", id);
          threadNotification(a, school, t, message);
          return thread(a, school, id);
        });
  }

  public Object readThread(Actor a, UUID school, UUID id) {
    var t = s.one("select * from parent_threads where school_id=? and id=?", school, id);
    threadRead(a, school, t);
    s.db.update(
        "insert into thread_reads(thread_id,account_id) values (?,?) on conflict(thread_id,account_id) do update set last_read_at=now()",
        id,
        a.accountId());
    return map("accepted", true);
  }

  public Object closeThread(
      Actor a, UUID school, UUID id, String reason, long version, String key) {
    var before = s.one("select * from parent_threads where school_id=? and id=?", school, id);
    threadRead(a, school, before);
    return command(
        a,
        school,
        "threads/" + id + "/close",
        key,
        map("reason", reason, "version", version),
        () -> {
          var t =
              s.one(
                  "select * from parent_threads where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) t.get("version")).longValue());
          if (!"OPEN".equals(t.get("state")))
            throw fail(409, "CONVERSATION_CLOSED", "This conversation is already closed.");
          s.db.update("update parent_threads set state='CLOSED',version=version+1 where id=?", id);
          s.commands.audit(
              school,
              a.accountId(),
              "PARENT_CONVERSATION_CLOSED",
              "THREAD",
              id,
              text(reason, 5, 500, "Closure reason"),
              Map.of());
          return thread(a, school, id);
        });
  }

  public record Complaint(
      UUID studentId,
      UUID guardianId,
      String category,
      String subject,
      String body,
      String intakeChannel,
      List<UUID> fileIds) {}

  private void category(String value) {
    if (value == null
        || !Set.of("FEES", "ATTENDANCE", "ACADEMIC_RECORDS", "STAFF_CONDUCT", "GENERAL")
            .contains(value))
      throw fail(422, "INVALID_COMPLAINT_CATEGORY", "Choose a supported complaint category.");
  }

  private boolean categoryRole(String role, String category) {
    return "PRINCIPAL".equals(role)
        || "ACCOUNTS".equals(role) && "FEES".equals(category)
        || "SCHOOL_OFFICE".equals(role)
            && Set.of("GENERAL", "ATTENDANCE", "ACADEMIC_RECORDS").contains(category);
  }

  private void caseRead(Actor a, UUID school, Map<String, Object> c) {
    var selected = a.school(school, "PARENT", "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    if ("PARENT".equals(selected.role())) {
      if (!a.accountId().equals(c.get("requester_id")))
        throw fail(404, "NOT_FOUND", "This case is unavailable.");
      context(a, school, (UUID) c.get("student_id"), c.get("category").toString());
    } else if (!categoryRole(selected.role(), c.get("category").toString()))
      throw fail(404, "NOT_FOUND", "This complaint category is unavailable.");
  }

  private void caseOwner(Actor a, Map<String, Object> c) {
    if (!"PARENT".equals(a.context().role())
        && !"PRINCIPAL".equals(a.context().role())
        && c.get("owner_id") != null
        && !a.context().subjectId().equals(c.get("owner_id")))
      throw fail(403, "CASE_OWNER_REQUIRED", "The assigned owner or principal manages this case.");
  }

  private void history(
      Actor a, UUID id, String action, Object before, Object after, UUID owner, String reason) {
    s.db.update(
        "insert into complaint_history(id,case_id,action,actor_id,reason,before_state,after_state,owner_id) values (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        action,
        a.accountId(),
        reason,
        before,
        after,
        owner);
  }

  public Object createCase(Actor a, UUID school, Complaint b, String key) {
    a.school(school, "PARENT", "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    category(b.category());
    String role = a.context().role();
    if (!"PARENT".equals(role) && !categoryRole(role, b.category()))
      throw fail(
          403,
          "COMPLAINT_INTAKE_FORBIDDEN",
          "Your staff preset cannot record this complaint category.");
    String channel = "PARENT".equals(role) ? "APP" : Objects.toString(b.intakeChannel(), "WALK_IN");
    if (!Set.of("APP", "WALK_IN", "PHONE").contains(channel)
        || !"PARENT".equals(role) && "APP".equals(channel))
      throw fail(
          422,
          "INVALID_INTAKE_CHANNEL",
          "Use APP for parents, or WALK_IN and PHONE for staff-recorded complaints.");
    var scope = context(a, school, b.studentId(), b.category());
    UUID guardian = guardian(a, scope, b.guardianId(), b.category()),
        recipient = account(school, guardian, "PARENT", b.studentId());
    var owner =
        staff(
            school,
            null,
            "FEES".equals(b.category())
                ? "ACCOUNTS"
                : "STAFF_CONDUCT".equals(b.category()) ? "PRINCIPAL" : "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "complaints",
        key,
        b,
        () -> {
          @SuppressWarnings("unchecked")
          Map<String, Object> calendar =
              s.http.post(
                  s.academics, "/internal/response-target", map("schoolId", school), Map.class);
          UUID id = UUID.randomUUID(), entry = UUID.randomUUID();
          UUID ownerId = uuid(owner.get("id").toString());
          s.db.update(
              "insert into complaints(id,school_id,student_id,requester_id,guardian_id,category,subject,first_response_due_at,owner_id,intake_channel) values (?,?,?,?,?,?,?,?::timestamptz,?,?)",
              id,
              school,
              b.studentId(),
              recipient,
              guardian,
              b.category(),
              text(b.subject(), 3, 160, "Complaint subject"),
              calendar.get("firstResponseDueAt"),
              ownerId,
              channel);
          s.db.update(
              "insert into case_entries(id,case_id,author_id,visibility,body) values (?,?,?,'PUBLIC',?)",
              entry,
              id,
              a.accountId(),
              text(b.body(), 5, 8000, "Complaint description"));
          attach(a, school, b.fileIds(), "CASE_ENTRY", entry, b.studentId(), null);
          history(a, id, "CREATED", null, "NEW", ownerId, "Recorded through " + channel);
          notify(
              entry,
              recipient,
              school,
              b.studentId(),
              "CASES",
              id,
              "PA01",
              "School case acknowledged");
          s.commands.audit(
              school,
              a.accountId(),
              "COMPLAINT_CREATED",
              "COMPLAINT",
              id,
              null,
              map("category", b.category(), "intakeChannel", channel, "ownerId", ownerId));
          return complaint(a, school, id);
        });
  }

  public Object complaint(Actor a, UUID school, UUID id) {
    var c = s.one("select * from complaints where school_id=? and id=?", school, id);
    caseRead(a, school, c);
    boolean parent = "PARENT".equals(a.context().role());
    var entries =
        s.db.queryForList(
            "select id,author_id as \"authorId\",visibility,body,created_at as \"createdAt\" from case_entries where case_id=? and (not ? or visibility='PUBLIC') order by created_at,id",
            id,
            parent);
    for (var e : entries) e.put("files", files("CASE_ENTRY", (UUID) e.get("id")));
    return map(
        "id",
        id,
        "studentId",
        c.get("student_id"),
        "category",
        c.get("category"),
        "subject",
        c.get("subject"),
        "state",
        c.get("state"),
        "ownerId",
        c.get("owner_id"),
        "intakeChannel",
        c.get("intake_channel"),
        "firstResponseDueAt",
        c.get("first_response_due_at"),
        "firstResponseAt",
        c.get("first_response_at"),
        "calendarConfigured",
        c.get("first_response_due_at") != null,
        "resolution",
        c.get("resolution_text"),
        "version",
        c.get("version"),
        "entries",
        entries);
  }

  public Object cases(Actor a, UUID school, UUID cursor, int limit) {
    var c = a.school(school, "PARENT", "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    if ("PARENT".equals(c.role())) s.child(a, school, c.childId());
    return page(
        s.db.queryForList(
            "select id,student_id as \"studentId\",category,subject,state,owner_id as \"ownerId\",intake_channel as \"intakeChannel\",first_response_due_at as \"firstResponseDueAt\",first_response_at as \"firstResponseAt\",version from complaints where school_id=? and (?<>'PARENT' or (requester_id=? and student_id=?::uuid)) and (?<>'ACCOUNTS' or category='FEES') and (?<>'SCHOOL_OFFICE' or category in ('GENERAL','ATTENDANCE','ACADEMIC_RECORDS')) and (?::uuid is null or id<?::uuid) order by id desc limit ?",
            school,
            c.role(),
            a.accountId(),
            c.childId(),
            c.role(),
            c.role(),
            cursor,
            cursor,
            limit + 1),
        limit,
        r -> r.get("id").toString());
  }

  public record Reply(String body, boolean internal, List<UUID> fileIds) {}

  public Object reply(Actor a, UUID school, UUID id, Reply b, long version, String key) {
    var before = s.one("select * from complaints where school_id=? and id=?", school, id);
    caseRead(a, school, before);
    caseOwner(a, before);
    boolean parent = "PARENT".equals(a.context().role());
    if (parent && b.internal())
      throw fail(403, "INTERNAL_NOTE_FORBIDDEN", "Only staff can create private notes.");
    return command(
        a,
        school,
        "complaints/" + id + "/replies",
        key,
        map("input", b, "version", version),
        () -> {
          var c =
              s.one("select * from complaints where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) c.get("version")).longValue());
          if ("CLOSED".equals(c.get("state")) || "RESOLVED".equals(c.get("state")) && !b.internal())
            throw fail(
                409,
                "CASE_NOT_OPEN",
                "Accept the resolution or request a review before continuing a resolved case.");
          UUID entry = UUID.randomUUID();
          s.db.update(
              "insert into case_entries(id,case_id,author_id,visibility,body) values (?,?,?,?,?)",
              entry,
              id,
              a.accountId(),
              b.internal() ? "INTERNAL" : "PUBLIC",
              text(b.body(), 2, 8000, "Case reply"));
          attach(a, school, b.fileIds(), "CASE_ENTRY", entry, (UUID) c.get("student_id"), null);
          String state =
              b.internal()
                  ? c.get("state").toString()
                  : parent ? "IN_REVIEW" : "WAITING_FOR_PARENT";
          s.db.update(
              "update complaints set state=?,first_response_at=case when ? then coalesce(first_response_at,now()) else first_response_at end,version=version+1 where id=?",
              state,
              !parent && !b.internal(),
              id);
          if (!parent && !b.internal())
            notify(
                entry,
                (UUID) c.get("requester_id"),
                school,
                (UUID) c.get("student_id"),
                "CASES",
                id,
                "PA01",
                "School case update");
          return complaint(a, school, id);
        });
  }

  public record StateChange(String action, String reason) {}

  public Object state(Actor a, UUID school, UUID id, StateChange b, long version, String key) {
    var old = s.one("select * from complaints where school_id=? and id=?", school, id);
    caseRead(a, school, old);
    caseOwner(a, old);
    return command(
        a,
        school,
        "complaints/" + id + "/status",
        key,
        map("input", b, "version", version),
        () -> {
          var c =
              s.one("select * from complaints where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) c.get("version")).longValue());
          String current = c.get("state").toString(),
              action = Objects.toString(b.action(), ""),
              next;
          String why = text(b.reason(), 5, 2000, "Case action reason");
          boolean parent = "PARENT".equals(a.context().role());
          if (parent) {
            if (!"RESOLVED".equals(current) || !Set.of("ACCEPT", "REQUEST_REVIEW").contains(action))
              throw fail(
                  409,
                  "INVALID_PARENT_CASE_ACTION",
                  "Accept a resolved case or request a documented review.");
            next = "ACCEPT".equals(action) ? "CLOSED" : "IN_REVIEW";
          } else {
            if ("CLOSED".equals(current)) throw fail(409, "CASE_CLOSED", "This case is closed.");
            next =
                switch (action) {
                  case "START_REVIEW" -> "IN_REVIEW";
                  case "WAIT_FOR_PARENT" -> "WAITING_FOR_PARENT";
                  case "RESOLVE" -> "RESOLVED";
                  case "CLOSE" -> {
                    if (!"RESOLVED".equals(current))
                      throw fail(
                          409,
                          "CASE_NOT_RESOLVED",
                          "Record a resolution before closing this case.");
                    yield "CLOSED";
                  }
                  default ->
                      throw fail(
                          422,
                          "INVALID_CASE_ACTION",
                          "Choose START_REVIEW, WAIT_FOR_PARENT, RESOLVE or CLOSE.");
                };
            if ("RESOLVED".equals(current) && !"CLOSE".equals(action))
              throw fail(
                  409,
                  "CASE_AWAITING_PARENT",
                  "The parent can accept the resolution or request a review.");
          }
          s.db.update(
              "update complaints set state=?,resolution_text=case when ?='RESOLVED' then ? else resolution_text end,resolved_at=case when ?='RESOLVED' then now() else resolved_at end,closed_at=case when ?='CLOSED' then now() else null end,first_response_at=case when ? then coalesce(first_response_at,now()) else first_response_at end,version=version+1 where id=?",
              next,
              next,
              why,
              next,
              next,
              !parent,
              id);
          history(a, id, action, current, next, (UUID) c.get("owner_id"), why);
          UUID entry = UUID.randomUUID();
          s.db.update(
              "insert into case_entries(id,case_id,author_id,visibility,body) values (?,?,?,'PUBLIC',?)",
              entry,
              id,
              a.accountId(),
              why);
          if (!parent)
            notify(
                entry,
                (UUID) c.get("requester_id"),
                school,
                (UUID) c.get("student_id"),
                "CASES",
                id,
                "PA01",
                "School case status updated");
          s.commands.audit(
              school,
              a.accountId(),
              "COMPLAINT_" + action,
              "COMPLAINT",
              id,
              why,
              map("before", current, "after", next));
          return complaint(a, school, id);
        });
  }

  public record Owner(UUID staffId, String reason) {}

  public Object assign(Actor a, UUID school, UUID id, Owner b, long version, String key) {
    a.school(school, "PRINCIPAL");
    var c = s.one("select * from complaints where school_id=? and id=?", school, id);
    var owner = staff(school, b.staffId(), null);
    if (!categoryRole(owner.get("role").toString(), c.get("category").toString()))
      throw fail(
          422,
          "OWNER_PRESET_MISMATCH",
          "Choose a staff owner whose preset can manage this category.");
    return command(
        a,
        school,
        "complaints/" + id + "/owner",
        key,
        map("input", b, "version", version),
        () -> {
          var old =
              s.one("select * from complaints where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) old.get("version")).longValue());
          if ("CLOSED".equals(old.get("state")))
            throw fail(409, "CASE_CLOSED", "Closed cases retain their owner history.");
          String why = text(b.reason(), 5, 500, "Reassignment reason");
          s.db.update(
              "update complaints set owner_id=?,version=version+1 where id=?", b.staffId(), id);
          history(a, id, "OWNER_CHANGED", old.get("state"), old.get("state"), b.staffId(), why);
          s.commands.audit(
              school,
              a.accountId(),
              "COMPLAINT_OWNER_CHANGED",
              "COMPLAINT",
              id,
              why,
              map("previousOwner", old.get("owner_id"), "ownerId", b.staffId()));
          return complaint(a, school, id);
        });
  }

  public Object caseHistory(Actor a, UUID school, UUID id) {
    var c = s.one("select * from complaints where school_id=? and id=?", school, id);
    caseRead(a, school, c);
    boolean parent = "PARENT".equals(a.context().role());
    return map(
        "items",
        s.db.queryForList(
            "select action,before_state as \"beforeState\",after_state as \"afterState\",created_at as \"createdAt\",case when ? then null else reason end as reason from complaint_history where case_id=? and (not ? or action<>'OWNER_CHANGED') order by created_at,id",
            parent,
            id,
            parent),
        "hasMore",
        false);
  }

  public record FileAccess(UUID schoolId, UUID fileId, Actor actor) {}

  public Object fileAccess(FileAccess b) {
    Actor.internalOnly("academics");
    var rows =
        s.db.queryForList(
            "select resource_type,resource_id from communication_attachments where school_id=? and file_id=?",
            b.schoolId(),
            b.fileId());
    for (var r : rows) {
      try {
        switch (r.get("resource_type").toString()) {
          case "NOTICE" -> notice(b.actor(), b.schoolId(), (UUID) r.get("resource_id"));
          case "THREAD_MESSAGE" -> {
            var t =
                s.one(
                    "select t.* from parent_threads t join thread_messages m on m.thread_id=t.id where t.school_id=? and m.id=?",
                    b.schoolId(),
                    r.get("resource_id"));
            threadRead(b.actor(), b.schoolId(), t);
          }
          case "CASE_ENTRY" -> {
            var c =
                s.one(
                    "select c.*,e.visibility from complaints c join case_entries e on e.case_id=c.id where c.school_id=? and e.id=?",
                    b.schoolId(),
                    r.get("resource_id"));
            caseRead(b.actor(), b.schoolId(), c);
            if ("PARENT".equals(b.actor().context().role())
                && "INTERNAL".equals(c.get("visibility"))) continue;
          }
          default -> {
            continue;
          }
        }
        return map("allowed", true);
      } catch (Api.Failure ex) {
        if (ex.status >= 500) throw ex;
      }
    }
    return map("allowed", false);
  }
}
