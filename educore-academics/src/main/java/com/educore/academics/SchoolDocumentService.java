package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SchoolDocumentService {
  private final SchoolService s;
  private final SchoolDocumentScope scope;

  public SchoolDocumentService(SchoolService s, SchoolDocumentScope scope) {
    this.s = s;
    this.scope = scope;
  }

  private Map<String, Object> row(UUID school, UUID id, boolean lock) {
    return s.one(
        "select * from school_documents where school_id=? and id=?" + (lock ? " for update" : ""),
        school,
        id);
  }

  private boolean office(Actor a) {
    return Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(a.context().role());
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

  public record Input(
      UUID studentId,
      UUID staffId,
      String kind,
      String title,
      UUID fileId,
      LocalDate issuedOn,
      String sessionName,
      Integer termNumber,
      String issuer,
      String verificationReference,
      boolean familyVisible,
      boolean staffVisible,
      String reason) {}

  public record Reason(String reason) {}

  private Object validate(UUID school, Input b) {
    if (b.kind() == null
        || !Set.of("ADMISSION", "HISTORICAL_REPORT", "STAFF_CREDENTIAL").contains(b.kind())
        || b.fileId() == null)
      throw fail(
          422,
          "DOCUMENT_KIND_REQUIRED",
          "Choose an admission document, historical report or staff credential and its ready file.");
    boolean staff = "STAFF_CREDENTIAL".equals(b.kind());
    if (staff
        ? b.staffId() == null || b.studentId() != null || b.familyVisible()
        : b.studentId() == null || b.staffId() != null || b.staffVisible())
      throw fail(
          422,
          "DOCUMENT_TARGET_MISMATCH",
          "Choose exactly the pupil or staff profile matching this document kind.");
    if (staff) s.one("select id from staff where school_id=? and id=?", school, b.staffId());
    else s.one("select id from students where school_id=? and id=?", school, b.studentId());
    if (b.issuedOn() == null || b.issuedOn().isAfter(LocalDate.now(ZoneId.of("Africa/Lagos"))))
      throw fail(422, "INVALID_DOCUMENT_DATE", "Use an actual issue date through today.");
    if ("HISTORICAL_REPORT".equals(b.kind())
        && (b.termNumber() == null || b.termNumber() < 1 || b.termNumber() > 3))
      throw fail(
          422,
          "HISTORICAL_TERM_REQUIRED",
          "A historical report needs its original session and term from one to three.");
    String session =
        b.sessionName() == null ? null : text(b.sessionName(), 3, 80, "Original academic session");
    if ("HISTORICAL_REPORT".equals(b.kind()) && session == null)
      throw fail(
          422, "HISTORICAL_SESSION_REQUIRED", "Record the report's original academic session.");
    var f = s.one("select * from school_files where school_id=? and id=?", school, b.fileId());
    if (!"READY".equals(f.get("state"))
        || !(staff ? "STAFF_DOCUMENT" : "STUDENT_DOCUMENT").equals(f.get("purpose"))
        || !Objects.equals(
            f.get(staff ? "staff_id" : "student_id"), staff ? b.staffId() : b.studentId()))
      throw fail(
          422,
          "DOCUMENT_FILE_MISMATCH",
          "Choose a ready document file for this exact school profile.");
    return map(
        "title",
        text(b.title(), 3, 160, "Document title"),
        "fileId",
        b.fileId(),
        "issuedOn",
        b.issuedOn(),
        "sessionName",
        session,
        "termNumber",
        b.termNumber(),
        "issuer",
        text(b.issuer(), 2, 160, "Issuing school or authority"),
        "verificationReference",
        text(b.verificationReference(), 5, 200, "School verification reference"),
        "familyVisible",
        b.familyVisible(),
        "staffVisible",
        b.staffVisible(),
        "source",
        "VERIFIED_DOCUMENT_COPY",
        "usedInAutomaticGrading",
        false);
  }

  private void bind(Actor a, UUID file, UUID id) {
    s.db.update(
        "insert into file_links(file_id,resource_type,resource_id,created_by) values (?,'SCHOOL_DOCUMENT',?,?) on conflict do nothing",
        file,
        id,
        a.accountId());
  }

  public Object create(Actor a, UUID school, Input b, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "documents",
        key,
        b,
        () -> {
          Object draft = validate(school, b);
          String reason = text(b.reason(), 5, 500, "Document intake reason");
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into school_documents(id,school_id,student_id,staff_id,kind,draft,created_by) values (?,?,?,?,?,?::jsonb,?)",
              id,
              school,
              b.studentId(),
              b.staffId(),
              b.kind(),
              s.json.write(draft),
              a.accountId());
          bind(a, b.fileId(), id);
          s.commands.audit(
              school,
              a.accountId(),
              "SCHOOL_DOCUMENT_PREPARED",
              "SCHOOL_DOCUMENT",
              id,
              reason,
              map("kind", b.kind(), "studentId", b.studentId(), "staffId", b.staffId()));
          return detail(a, school, id);
        });
  }

  public Object edit(Actor a, UUID school, UUID id, Input b, long version, String key) {
    return command(
        a,
        school,
        "documents/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          var d = row(school, id, true);
          expectVersion(version, ((Number) d.get("version")).longValue());
          if ("ARCHIVED".equals(d.get("state")))
            throw fail(409, "DOCUMENT_ARCHIVED", "Prepare a new record after document archival.");
          if (!Objects.equals(b.studentId(), d.get("student_id"))
              || !Objects.equals(b.staffId(), d.get("staff_id"))
              || !Objects.equals(b.kind(), d.get("kind")))
            throw fail(
                422,
                "DOCUMENT_SCOPE_IMMUTABLE",
                "A document cannot move to another profile or change kind.");
          Object draft = validate(school, b);
          bind(a, b.fileId(), id);
          s.db.update(
              "update school_documents set draft=?::jsonb,version=version+1 where id=?",
              s.json.write(draft),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "SCHOOL_DOCUMENT_DRAFT_SAVED",
              "SCHOOL_DOCUMENT",
              id,
              text(b.reason(), 5, 500, "Document revision reason"),
              map("previousVersion", version));
          return detail(a, school, id);
        });
  }

  public Object publish(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    if ("HISTORICAL_REPORT".equals(row(school, id, false).get("kind")))
      a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "documents/" + id + "/publish",
        key,
        map("version", version),
        () -> {
          var d = row(school, id, true);
          expectVersion(version, ((Number) d.get("version")).longValue());
          if (d.get("draft") == null || "ARCHIVED".equals(d.get("state")))
            throw fail(409, "DOCUMENT_NOT_DRAFT", "Review a document draft before publication.");
          int rev = ((Number) d.get("published_revision")).intValue() + 1;
          String snapshot = d.get("draft").toString();
          s.db.update(
              "insert into school_document_revisions(document_id,revision,snapshot,published_by) values (?,?,?::jsonb,?)",
              id,
              rev,
              snapshot,
              a.accountId());
          s.db.update(
              "update school_documents set state='PUBLISHED',published_snapshot=?::jsonb,published_revision=?,published_at=coalesce(published_at,now()),draft=null,version=version+1 where id=?",
              snapshot,
              rev,
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "SCHOOL_DOCUMENT_PUBLISHED",
              "SCHOOL_DOCUMENT",
              id,
              "School verified the original document",
              map("revision", rev, "kind", d.get("kind")));
          return detail(a, school, id);
        });
  }

  public Object archive(Actor a, UUID school, UUID id, Reason b, long version, String key) {
    return command(
        a,
        school,
        "documents/" + id + "/archive",
        key,
        map("input", b, "version", version),
        () -> {
          var d = row(school, id, true);
          expectVersion(version, ((Number) d.get("version")).longValue());
          if ("ARCHIVED".equals(d.get("state")))
            throw fail(409, "ALREADY_ARCHIVED", "This document is already archived.");
          s.db.update(
              "update school_documents set state='ARCHIVED',version=version+1 where id=?", id);
          s.commands.audit(
              school,
              a.accountId(),
              "SCHOOL_DOCUMENT_ARCHIVED",
              "SCHOOL_DOCUMENT",
              id,
              text(b.reason(), 5, 500, "Archive reason"),
              Map.of());
          return map("id", id, "state", "ARCHIVED", "version", version + 1);
        });
  }

  public Object detail(Actor a, UUID school, UUID id) {
    var d = row(school, id, false);
    scope.read(
        a,
        school,
        d,
        d.get("published_snapshot") == null
            ? null
            : s.json.tree(d.get("published_snapshot").toString()));
    var result =
        map(
            "id",
            id,
            "studentId",
            d.get("student_id"),
            "staffId",
            d.get("staff_id"),
            "kind",
            d.get("kind"),
            "state",
            d.get("state"),
            "version",
            d.get("version"),
            "publishedRevision",
            d.get("published_revision"),
            "published",
            d.get("published_snapshot") == null
                ? null
                : s.json.value(d.get("published_snapshot").toString()));
    if (office(a))
      result.put("draft", d.get("draft") == null ? null : s.json.value(d.get("draft").toString()));
    return result;
  }

  public Object list(
      Actor a, UUID school, UUID student, UUID staff, String kind, UUID cursor, int limit) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "PARENT", "STUDENT");
    if (student == null && staff == null && !office(a))
      throw fail(
          422, "DOCUMENT_PROFILE_REQUIRED", "Choose a permitted pupil or your own staff profile.");
    if (student != null) s.learning(a, school, student);
    if (staff != null
        && !office(a)
        && !("TEACHER".equals(a.context().role()) && staff.equals(a.context().subjectId())))
      throw fail(404, "NOT_FOUND", "This staff profile is unavailable.");
    var args =
        new ArrayList<Object>(
            Arrays.asList(school, student, student, staff, staff, kind, kind, cursor, cursor));
    String sql =
        "select id from school_documents where school_id=? and (?::uuid is null or student_id=?::uuid) and (?::uuid is null or staff_id=?::uuid) and (?::text is null or kind=?::text) and (?::uuid is null or id<?::uuid)";
    if (!office(a)) {
      sql += " and state='PUBLISHED' and published_snapshot->>?='true'";
      args.add(staff != null ? "staffVisible" : "familyVisible");
      if ("TEACHER".equals(a.context().role()) && student != null)
        sql += " and kind='HISTORICAL_REPORT'";
    }
    sql += " order by id desc limit ?";
    args.add(limit + 1);
    var items = new ArrayList<Map<String, Object>>();
    for (var r : s.db.queryForList(sql, args.toArray())) {
      try {
        @SuppressWarnings("unchecked")
        var d = (Map<String, Object>) detail(a, school, (UUID) r.get("id"));
        items.add(d);
      } catch (Api.Failure ex) {
        if (ex.status != 404) throw ex;
      }
    }
    return page(items, limit, r -> r.get("id").toString());
  }

  public Object history(Actor a, UUID school, UUID id) {
    var d = row(school, id, false);
    detail(a, school, id);
    var items = new ArrayList<Map<String, Object>>();
    for (var r :
        s.db.queryForList(
            "select revision,snapshot,published_at as \"publishedAt\" from school_document_revisions where document_id=? order by revision",
            id)) {
      try {
        scope.read(a, school, d, s.json.tree(r.get("snapshot").toString()));
        r.put("snapshot", s.json.value(r.get("snapshot").toString()));
        items.add(r);
      } catch (Api.Failure ex) {
        if (ex.status != 404) throw ex;
      }
    }
    return map("items", items, "hasMore", false);
  }
}
