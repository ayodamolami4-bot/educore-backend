package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MaterialService {
  private final SchoolService s;
  private final LearningScope scope;
  private final FileService files;

  public MaterialService(SchoolService s, LearningScope scope, FileService files) {
    this.s = s;
    this.scope = scope;
    this.files = files;
  }

  private Map<String, Object> row(UUID school, UUID id, boolean lock) {
    return s.one(
        "select m.*,t.class_id,t.subject_id,t.teacher_id,t.active,(st.active and st.staff_role='TEACHER') as teacher_active,c.attendance_teacher_id,c.session_id from learning_materials m join teaching_assignments t on t.id=m.teaching_assignment_id join staff st on st.id=t.teacher_id join classes c on c.id=t.class_id where m.school_id=? and m.id=?"
            + (lock ? " for update of m" : ""),
        school,
        id);
  }

  private boolean author(Actor a, Map<String, Object> m) {
    return Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(a.context().role())
        || ("TEACHER".equals(a.context().role())
            && a.context().subjectId().equals(m.get("teacher_id"))
            && Boolean.TRUE.equals(m.get("active"))
            && Boolean.TRUE.equals(m.get("teacher_active")));
  }

  private boolean published(Map<String, Object> m) {
    return "PUBLISHED".equals(m.get("state")) && m.get("published_snapshot") != null;
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object body,
      java.util.function.Supplier<Object> action) {
    return s.commands.execute(a.accountId().toString(), school.toString(), path, key, body, action);
  }

  public record Input(
      UUID teachingAssignmentId, UUID termId, String title, String body, List<UUID> fileIds) {}

  public record Reason(String reason) {}

  private Object draft(Input b) {
    String title = text(b.title(), 3, 160, "Material title"),
        body = b.body() == null ? "" : b.body().strip();
    if (body.length() > 8000 || (body.isEmpty() && (b.fileIds() == null || b.fileIds().isEmpty())))
      throw fail(
          422,
          "EMPTY_MATERIAL",
          "Provide material text or a ready attachment, with text up to 8,000 characters.");
    return map(
        "title",
        title,
        "body",
        body,
        "files",
        b.fileIds() == null ? List.of() : b.fileIds().stream().map(id -> map("id", id)).toList());
  }

  private void attach(Actor a, UUID school, UUID id, UUID classId, List<UUID> ids) {
    if (ids == null) return;
    if (ids.size() > 5 || new HashSet<>(ids).size() != ids.size())
      throw fail(422, "INVALID_ATTACHMENTS", "Use at most five distinct ready files.");
    for (UUID file : ids) {
      var f =
          s.one("select * from school_files where school_id=? and id=? for update", school, file);
      if (!"MATERIAL".equals(f.get("purpose"))
          || !"READY".equals(f.get("state"))
          || !classId.equals(f.get("class_id")))
        throw fail(422, "MATERIAL_FILE_SCOPE", "Choose ready material files for this class.");
      if (!a.accountId().equals(f.get("owner_id"))
          && s.db.queryForObject(
                  "select count(*) from file_links where file_id=? and resource_type='MATERIAL' and resource_id=?",
                  Integer.class,
                  file,
                  id)
              == 0)
        throw fail(
            422,
            "ATTACHMENT_NOT_OWNED",
            "Use your own upload or an attachment already belonging to this material.");
      s.db.update(
          "insert into file_links(file_id,resource_type,resource_id,created_by) values (?,'MATERIAL',?,?) on conflict(file_id,resource_type,resource_id) do update set active=true",
          file,
          id,
          a.accountId());
    }
  }

  public Object create(Actor a, UUID school, Input b, String key) {
    var ta = scope.assignment(school, b.teachingAssignmentId());
    scope.owner(a, school, ta);
    Object draft = draft(b);
    if (!ta.get("session_id")
        .equals(
            s.one("select session_id from terms where school_id=? and id=?", school, b.termId())
                .get("session_id")))
      throw fail(422, "INVALID_TERM", "Choose the teaching class's academic term.");
    return command(
        a,
        school,
        "materials",
        key,
        b,
        () -> {
          s.lockPlacements(school);
          scope.owner(a, school, scope.assignment(school, b.teachingAssignmentId()));
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into learning_materials(id,school_id,teaching_assignment_id,term_id,draft,required,department,created_by) values (?,?,?,?,?::jsonb,?,?,?)",
              id,
              school,
              b.teachingAssignmentId(),
              b.termId(),
              s.json.write(draft),
              ta.get("required"),
              ta.get("department"),
              a.accountId());
          attach(a, school, id, (UUID) ta.get("class_id"), b.fileIds());
          s.commands.audit(
              school, a.accountId(), "MATERIAL_DRAFT_CREATED", "MATERIAL", id, null, Map.of());
          return detail(a, school, id);
        });
  }

  public Object edit(Actor a, UUID school, UUID id, Input b, long version, String key) {
    scope.owner(a, school, row(school, id, false));
    Object draft = draft(b);
    return command(
        a,
        school,
        "materials/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          s.lockPlacements(school);
          var m = row(school, id, true);
          scope.owner(a, school, m);
          expectVersion(version, ((Number) m.get("version")).longValue());
          if ("ARCHIVED".equals(m.get("state")))
            throw fail(409, "MATERIAL_ARCHIVED", "Create a new material after archival.");
          if (!b.teachingAssignmentId().equals(m.get("teaching_assignment_id"))
              || !b.termId().equals(m.get("term_id")))
            throw fail(
                422,
                "MATERIAL_SCOPE_IMMUTABLE",
                "Use a new material to change its class, subject or term.");
          attach(a, school, id, (UUID) m.get("class_id"), b.fileIds());
          s.db.update(
              "update learning_materials set draft=?::jsonb,version=version+1 where id=?",
              s.json.write(draft),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "MATERIAL_DRAFT_SAVED",
              "MATERIAL",
              id,
              null,
              map("previousVersion", version));
          return detail(a, school, id);
        });
  }

  public Object publish(Actor a, UUID school, UUID id, long version, String key) {
    scope.owner(a, school, row(school, id, false));
    return command(
        a,
        school,
        "materials/" + id + "/publish",
        key,
        map("version", version),
        () -> {
          s.lockPlacements(school);
          var m = row(school, id, true);
          scope.owner(a, school, m);
          expectVersion(version, ((Number) m.get("version")).longValue());
          if ("ARCHIVED".equals(m.get("state")) || m.get("draft") == null)
            throw fail(409, "MATERIAL_NOT_DRAFT", "Save a material draft before publication.");
          var ta = scope.assignment(school, (UUID) m.get("teaching_assignment_id"));
          scope.owner(a, school, ta);
          int revision = ((Number) m.get("published_revision")).intValue() + 1;
          Object snap = s.json.value(m.get("draft").toString());
          s.db.update(
              "insert into material_revisions(material_id,revision,snapshot,published_by) values (?,?,?::jsonb,?)",
              id,
              revision,
              s.json.write(snap),
              a.accountId());
          s.db.update(
              "update learning_materials set state='PUBLISHED',published_snapshot=?::jsonb,published_revision=?,published_at=coalesce(published_at,now()),required=?,department=?,draft=null,version=version+1 where id=?",
              s.json.write(snap),
              revision,
              ta.get("required"),
              ta.get("department"),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "MATERIAL_PUBLISHED",
              "MATERIAL",
              id,
              null,
              map("revision", revision));
          scope.notifyClass(school, row(school, id, false), id);
          return detail(a, school, id);
        });
  }

  public Object archive(Actor a, UUID school, UUID id, Reason b, long version, String key) {
    scope.owner(a, school, row(school, id, false));
    return command(
        a,
        school,
        "materials/" + id + "/archive",
        key,
        map("input", b, "version", version),
        () -> {
          s.lockPlacements(school);
          var m = row(school, id, true);
          scope.owner(a, school, m);
          expectVersion(version, ((Number) m.get("version")).longValue());
          String why = text(b.reason(), 5, 500, "Archive reason");
          if ("ARCHIVED".equals(m.get("state")))
            throw fail(409, "ALREADY_ARCHIVED", "This material is already archived.");
          s.db.update(
              "update learning_materials set state='ARCHIVED',version=version+1 where id=?", id);
          s.commands.audit(
              school, a.accountId(), "MATERIAL_ARCHIVED", "MATERIAL", id, why, Map.of());
          return map("id", id, "state", "ARCHIVED", "version", version + 1);
        });
  }

  public Object detail(Actor a, UUID school, UUID id) {
    var m = row(school, id, false);
    scope.read(a, school, m, published(m));
    var result =
        map(
            "id",
            id,
            "teachingAssignmentId",
            m.get("teaching_assignment_id"),
            "classId",
            m.get("class_id"),
            "subjectId",
            m.get("subject_id"),
            "termId",
            m.get("term_id"),
            "state",
            m.get("state"),
            "version",
            m.get("version"),
            "publishedRevision",
            m.get("published_revision"),
            "publishedAt",
            m.get("published_at"),
            "published",
            m.get("published_snapshot") == null
                ? null
                : s.json.value(m.get("published_snapshot").toString()));
    if (author(a, m))
      result.put("draft", m.get("draft") == null ? null : s.json.value(m.get("draft").toString()));
    return result;
  }

  public Object list(
      Actor a, UUID school, UUID classId, UUID subjectId, UUID termId, UUID cursor, int limit) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "PARENT", "STUDENT");
    if (Set.of("PARENT", "STUDENT").contains(a.context().role()))
      s.learning(
          a,
          school,
          "PARENT".equals(a.context().role()) ? a.context().childId() : a.context().subjectId());
    var args =
        new ArrayList<Object>(
            Arrays.asList(
                school, classId, classId, subjectId, subjectId, termId, termId, cursor, cursor));
    String sql =
        "select m.id from learning_materials m join teaching_assignments t on t.id=m.teaching_assignment_id join classes c on c.id=t.class_id where m.school_id=? and (?::uuid is null or t.class_id=?::uuid) and (?::uuid is null or t.subject_id=?::uuid) and (?::uuid is null or m.term_id=?::uuid) and (?::uuid is null or m.id<?::uuid)";
    var c = a.context();
    if ("TEACHER".equals(c.role())) {
      sql +=
          " and ((t.teacher_id=? and t.active) or (c.attendance_teacher_id=? and m.state='PUBLISHED'))";
      args.add(c.subjectId());
      args.add(c.subjectId());
    } else if (Set.of("PARENT", "STUDENT").contains(c.role())) {
      sql +=
          " and m.state='PUBLISHED' and exists(select 1 from enrolments e where e.school_id=m.school_id and e.student_id=? and e.class_id=t.class_id and e.starts_on<=current_date and (e.ends_on is null or e.ends_on>=m.published_at::date) and (m.department is null or e.department=m.department) and (m.required or exists(select 1 from student_subject_choices ch where ch.student_id=e.student_id and ch.session_id=e.session_id and ch.subject_id=t.subject_id)))";
      args.add("PARENT".equals(c.role()) ? c.childId() : c.subjectId());
    }
    sql += " order by m.id desc limit ?";
    args.add(limit + 1);
    var items = new ArrayList<Map<String, Object>>();
    for (var entry : s.db.queryForList(sql, args.toArray())) {
      try {
        @SuppressWarnings("unchecked")
        var item = (Map<String, Object>) detail(a, school, (UUID) entry.get("id"));
        items.add(item);
      } catch (Api.Failure ex) {
        if (ex.status != 404) throw ex;
      }
    }
    return page(items, limit, r -> r.get("id").toString());
  }

  public Object history(Actor a, UUID school, UUID id) {
    var m = row(school, id, false);
    scope.read(a, school, m, published(m));
    var rows =
        s.db.queryForList(
            "select revision,snapshot,published_at as \"publishedAt\" from material_revisions where material_id=? order by revision",
            id);
    for (var r : rows) r.put("snapshot", s.json.value(r.get("snapshot").toString()));
    return map("items", rows, "hasMore", false);
  }
}
