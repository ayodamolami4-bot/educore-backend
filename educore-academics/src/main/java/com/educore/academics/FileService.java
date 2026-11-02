package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FileService {
  private final SchoolService s;
  private final Crypto crypto;
  private final ServiceHttp http;
  private final String communication;
  private final long schoolQuota;

  public FileService(
      SchoolService s,
      Crypto crypto,
      ServiceHttp http,
      @Value("${educore.communication-url}") String communication,
      @Value("${educore.file-school-quota-bytes:104857600}") long schoolQuota) {
    this.s = s;
    this.crypto = crypto;
    this.http = http;
    this.communication = communication;
    this.schoolQuota = schoolQuota;
  }

  public record Metadata(
      String purpose,
      UUID studentId,
      UUID classId,
      String filename,
      String mediaType,
      int size,
      String sha256,
      boolean familyVisible) {}

  private Map<String, Object> row(UUID school, UUID id, boolean lock) {
    return s.one(
        "select * from school_files where school_id=? and id=?" + (lock ? " for update" : ""),
        school,
        id);
  }

  private Object wire(Map<String, Object> f) {
    return map(
        "id",
        f.get("id"),
        "purpose",
        f.get("purpose"),
        "studentId",
        f.get("student_id"),
        "classId",
        f.get("class_id"),
        "filename",
        f.get("filename"),
        "mediaType",
        f.get("media_type"),
        "size",
        f.get("expected_size"),
        "sha256",
        f.get("sha256"),
        "state",
        f.get("state"),
        "version",
        f.get("version"),
        "familyVisible",
        f.get("family_visible"));
  }

  private void childAccess(Actor a, UUID school, UUID student, boolean payer) {
    a.child(student, payer);
    if (s.db.queryForObject(
            "select count(*) from guardian_links where school_id=? and student_id=? and guardian_id=? and active and (case when ? then payer_access else learning_access end)",
            Integer.class,
            school,
            student,
            a.context().subjectId(),
            payer)
        == 0) throw fail(404, "NOT_FOUND", "The child relationship is unavailable.");
  }

  private void creationScope(
      Actor a, UUID school, String purpose, UUID student, UUID classId, boolean familyVisible) {
    var c = a.school(school);
    String role = c.role();
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS", "TEACHER").contains(role)
        && s.db.queryForObject(
                "select count(*) from staff where school_id=? and id=? and active",
                Integer.class,
                school,
                c.subjectId())
            == 0) throw fail(404, "NOT_FOUND", "School access is unavailable.");
    if ("SCHOOL_ASSET".equals(purpose)) {
      a.school(school, "PRINCIPAL");
      return;
    }
    if (familyVisible && !Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(role))
      throw fail(
          403,
          "FILE_PUBLICATION_FORBIDDEN",
          "Only authorised school staff can publish student documents to families.");
    if ("PARENT".equals(role)) {
      if (!Set.of("STUDENT_DOCUMENT", "COMMUNICATION", "FINANCE_EVIDENCE").contains(purpose)
          || student == null)
        throw fail(403, "FILE_PURPOSE_FORBIDDEN", "Choose a permitted child-linked file purpose.");
      childAccess(a, school, student, "FINANCE_EVIDENCE".equals(purpose));
    } else if ("STUDENT".equals(role)) {
      if (student == null
          || !student.equals(c.subjectId())
          || !Set.of("SUBMISSION", "STUDENT_DOCUMENT").contains(purpose))
        throw fail(
            403, "FILE_PURPOSE_FORBIDDEN", "Students can upload only their own learning files.");
    } else if ("ACCOUNTS".equals(role)) {
      if (!Set.of("FINANCE_EVIDENCE", "COMMUNICATION").contains(purpose)
          || ("COMMUNICATION".equals(purpose) && student == null))
        throw fail(
            403, "FILE_PURPOSE_FORBIDDEN", "Accounts access is limited to finance evidence.");
    } else if ("SCHOOL_OFFICE".equals(role) && "FINANCE_EVIDENCE".equals(purpose))
      throw fail(403, "FILE_PURPOSE_FORBIDDEN", "School Office cannot manage financial evidence.");
    else if ("TEACHER".equals(role)) {
      if (!Set.of("ASSIGNMENT", "STUDENT_DOCUMENT", "COMMUNICATION").contains(purpose))
        throw fail(403, "FILE_PURPOSE_FORBIDDEN", "Choose a permitted teaching file purpose.");
      if (student != null) s.learning(a, school, student);
      if (classId != null
          && s.db.queryForObject(
                  "select count(*) from teaching_assignments where school_id=? and class_id=? and teacher_id=?",
                  Integer.class,
                  school,
                  classId,
                  c.subjectId())
              == 0) throw fail(404, "NOT_FOUND", "This teaching class is unavailable.");
      if (student == null && classId == null)
        throw fail(422, "FILE_SCOPE_REQUIRED", "Choose a teaching class or a permitted child.");
    }
    if (student != null)
      s.one("select id from students where school_id=? and id=?", school, student);
    if (classId != null)
      s.one("select id from classes where school_id=? and id=?", school, classId);
  }

  public Object create(Actor a, UUID school, Metadata b, String key) {
    if ("ASSIGNMENT".equals(b.purpose()) && (b.classId() == null || b.studentId() != null))
      throw fail(
          422,
          "ASSIGNMENT_FILE_SCOPE",
          "Assignment handouts belong to a teaching class, without a private child record.");
    if (b.purpose() == null
        || !Set.of(
                "STUDENT_DOCUMENT",
                "ASSIGNMENT",
                "SUBMISSION",
                "COMMUNICATION",
                "FINANCE_EVIDENCE",
                "SCHOOL_ASSET")
            .contains(b.purpose()))
      throw fail(422, "INVALID_FILE_PURPOSE", "Choose a supported file purpose.");
    creationScope(a, school, b.purpose(), b.studentId(), b.classId(), b.familyVisible());
    String filename = text(b.filename(), 1, 120, "Filename");
    if (filename.contains("/")
        || filename.contains("\\")
        || filename.codePoints().anyMatch(Character::isISOControl))
      throw fail(
          422,
          "INVALID_FILENAME",
          "Use a simple filename without directory paths or control characters.");
    String extension = filename.toLowerCase(Locale.ROOT);
    boolean type =
        ("application/pdf".equals(b.mediaType()) && extension.endsWith(".pdf"))
            || ("image/png".equals(b.mediaType()) && extension.endsWith(".png"))
            || ("image/jpeg".equals(b.mediaType())
                && (extension.endsWith(".jpg") || extension.endsWith(".jpeg")));
    if (!type
        || ("SCHOOL_ASSET".equals(b.purpose()) && "application/pdf".equals(b.mediaType()))
        || b.size() < 1
        || b.size() > 10 * 1024 * 1024
        || b.sha256() == null
        || !b.sha256().matches("[a-f0-9]{64}"))
      throw fail(
          422,
          "INVALID_FILE",
          "Use PDF, JPG or PNG, at most 10 MB, with its exact byte size and SHA-256. School logos must be images.");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "files",
        key,
        b,
        () -> {
          s.db.queryForObject(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              Object.class,
              "file-quota|" + school);
          s.db.update(
              "update school_files set state='EXPIRED' where school_id=? and state='PENDING' and created_at<now()-interval '1 hour'",
              school);
          long
              used =
                  s.db.queryForObject(
                      "select coalesce(sum(expected_size),0) from school_files where school_id=? and state<>'EXPIRED'",
                      Long.class,
                      school),
              owned =
                  s.db.queryForObject(
                      "select coalesce(sum(expected_size),0) from school_files where school_id=? and owner_id=? and state<>'EXPIRED'",
                      Long.class,
                      school,
                      a.accountId());
          if (used + b.size() > schoolQuota || owned + b.size() > 20 * 1024 * 1024L)
            throw fail(
                409,
                "FILE_STORAGE_QUOTA",
                "The configured school or account file allowance is full. Ask the principal to review storage.");
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into school_files(id,school_id,owner_id,student_id,class_id,purpose,filename,media_type,expected_size,sha256,family_visible) values (?,?,?,?,?,?,?,?,?,?,?)",
              id,
              school,
              a.accountId(),
              b.studentId(),
              b.classId(),
              b.purpose(),
              filename,
              b.mediaType(),
              b.size(),
              b.sha256(),
              b.familyVisible());
          s.commands.audit(
              school,
              a.accountId(),
              "FILE_UPLOAD_PREPARED",
              "FILE",
              id,
              null,
              map("purpose", b.purpose(), "size", b.size()));
          return wire(row(school, id, false));
        });
  }

  public Object upload(Actor a, UUID school, UUID id, byte[] bytes, long version) {
    var before = row(school, id, false);
    a.school(school);
    if (!a.accountId().equals(before.get("owner_id")))
      throw fail(404, "NOT_FOUND", "The upload is unavailable.");
    creationScope(
        a,
        school,
        (String) before.get("purpose"),
        (UUID) before.get("student_id"),
        (UUID) before.get("class_id"),
        Boolean.TRUE.equals(before.get("family_visible")));
    if (bytes.length != ((Number) before.get("expected_size")).intValue()
        || !Crypto.equal(Crypto.hashBytes(bytes), (String) before.get("sha256")))
      throw fail(
          422, "FILE_CONTENT_MISMATCH", "The content must match the reviewed size and SHA-256.");
    String mime = (String) before.get("media_type");
    boolean magic =
        "application/pdf".equals(mime)
            ? bytes.length >= 5
                && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                    .equals("%PDF-")
            : "image/png".equals(mime)
                ? bytes.length >= 8
                    && Arrays.equals(
                        Arrays.copyOf(bytes, 8),
                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})
                : bytes.length >= 3
                    && bytes[0] == (byte) 255
                    && bytes[1] == (byte) 216
                    && bytes[2] == (byte) 255;
    if (!magic)
      throw fail(
          422, "INVALID_FILE_CONTENT", "File content does not match its declared supported type.");
    return s.commands.transaction(
        () -> {
          var f = row(school, id, true);
          if ("READY".equals(f.get("state"))) return wire(f);
          expectVersion(version, ((Number) f.get("version")).longValue());
          if (!"PENDING".equals(f.get("state")))
            throw fail(
                409, "UPLOAD_CLOSED", "Create a new upload for an expired or archived file.");
          s.db.update(
              "update school_files set state='READY',payload_cipher=?,version=version+1 where id=?",
              crypto.sealBytes(bytes),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "FILE_STORED",
              "FILE",
              id,
              null,
              map("sha256", f.get("sha256")));
          return wire(row(school, id, false));
        });
  }

  private boolean learningFile(Actor a, UUID school, UUID id) {
    var rows =
        s.db.queryForList(
            "select l.resource_type,w.state,w.published_at,w.term_id,w.id as assignment_id,ta.class_id,ta.subject_id,ta.required,ta.department,ta.teacher_id,c.attendance_teacher_id,sub.id as submission_id,sub.student_id from file_links l left join assignment_submissions sub on l.resource_type='SUBMISSION' and sub.id=l.resource_id join learning_assignments w on w.id=case when l.resource_type='ASSIGNMENT' then l.resource_id else sub.assignment_id end join teaching_assignments ta on ta.id=w.teaching_assignment_id join classes c on c.id=ta.class_id where l.file_id=? and w.school_id=? and (l.active or l.resource_type='SUBMISSION')",
            id,
            school);
    for (var r : rows) {
      String role = a.context().role();
      if ("SUBMISSION".equals(r.get("resource_type"))
          && s.db.queryForObject(
                  "select count(*) from submission_revisions where submission_id=? and snapshot->'files' @> ?::jsonb",
                  Integer.class,
                  r.get("submission_id"),
                  s.json.write(List.of(map("id", id))))
              == 0) continue;
      if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(role)) return true;
      if ("TEACHER".equals(role)
          && (a.context().subjectId().equals(r.get("teacher_id"))
              || a.context().subjectId().equals(r.get("attendance_teacher_id")))) return true;
      if (Set.of("PARENT", "STUDENT").contains(role) && r.get("published_at") != null) {
        UUID student = "PARENT".equals(role) ? a.context().childId() : a.context().subjectId();
        s.learning(a, school, student);
        if (!Boolean.TRUE.equals(r.get("required"))
            && s.db.queryForObject(
                    "select count(*) from student_subject_choices where student_id=? and session_id=(select session_id from terms where id=?)",
                    Integer.class,
                    student,
                    r.get("term_id"))
                > 0
            && s.db.queryForObject(
                    "select count(*) from student_subject_choices where student_id=? and session_id=(select session_id from terms where id=?) and subject_id=?",
                    Integer.class,
                    student,
                    r.get("term_id"),
                    r.get("subject_id"))
                == 0) continue;
        if ("SUBMISSION".equals(r.get("resource_type")) && !student.equals(r.get("student_id")))
          continue;
        if (s.db.queryForObject(
                "select count(*) from enrolments where school_id=? and student_id=? and class_id=? and starts_on<=current_date and (ends_on is null or ends_on>=(select published_at::date from learning_assignments where id=?)) and (department is not distinct from ?::text or ?::text is null)",
                Integer.class,
                school,
                student,
                r.get("class_id"),
                r.get("assignment_id"),
                r.get("department"),
                r.get("department"))
            > 0) return true;
      }
    }
    return false;
  }

  private void readScope(Actor a, UUID school, Map<String, Object> f) {
    a.school(school);
    String purpose = (String) f.get("purpose"), role = a.context().role();
    UUID student = (UUID) f.get("student_id");
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS", "TEACHER").contains(role)
        && s.db.queryForObject(
                "select count(*) from staff where school_id=? and id=? and active",
                Integer.class,
                school,
                a.context().subjectId())
            == 0) throw fail(404, "NOT_FOUND", "School access is unavailable.");
    if ("PARENT".equals(role) && student != null)
      childAccess(a, school, student, "FINANCE_EVIDENCE".equals(purpose));
    if (a.accountId().equals(f.get("owner_id"))) return;
    if ("SCHOOL_ASSET".equals(purpose)) return;
    if ("FINANCE_EVIDENCE".equals(purpose) && Set.of("PRINCIPAL", "ACCOUNTS").contains(role))
      return;
    if ("STUDENT_DOCUMENT".equals(purpose)) {
      if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(role)) return;
      if (Boolean.TRUE.equals(f.get("family_visible")) && student != null) {
        s.learning(a, school, student);
        return;
      }
    }
    if (Set.of("ASSIGNMENT", "SUBMISSION").contains(purpose)
        && learningFile(a, school, (UUID) f.get("id"))) return;
    if ("COMMUNICATION".equals(purpose)) {
      @SuppressWarnings("unchecked")
      Map<String, Object> result =
          http.post(
              communication,
              "/internal/file-access",
              map("schoolId", school, "fileId", f.get("id"), "actor", a),
              Map.class);
      if (Boolean.TRUE.equals(result.get("allowed"))) return;
    }
    throw fail(404, "NOT_FOUND", "The file is unavailable.");
  }

  public Object metadata(Actor a, UUID school, UUID id) {
    var f = row(school, id, false);
    readScope(a, school, f);
    return wire(f);
  }

  public record Download(byte[] content, String filename, String mediaType) {}

  public Download download(Actor a, UUID school, UUID id) {
    var f = row(school, id, false);
    readScope(a, school, f);
    if (!"READY".equals(f.get("state")))
      throw fail(409, "FILE_NOT_READY", "Only completed files can be downloaded.");
    return new Download(
        crypto.openBytes((byte[]) f.get("payload_cipher")),
        (String) f.get("filename"),
        (String) f.get("media_type"));
  }

  public void attach(
      Actor a,
      UUID school,
      List<UUID> files,
      String purpose,
      String resourceType,
      UUID resource,
      UUID student) {
    if (files == null) return;
    if (files.size() > 5 || new HashSet<>(files).size() != files.size())
      throw fail(422, "INVALID_ATTACHMENTS", "Use at most five distinct ready files.");
    for (UUID id : files) {
      var f = row(school, id, true);
      if ("ASSIGNMENT".equals(resourceType)
          && !Objects.equals(
              f.get("class_id"),
              s.one(
                      "select t.class_id from learning_assignments w join teaching_assignments t on t.id=w.teaching_assignment_id where w.school_id=? and w.id=?",
                      school,
                      resource)
                  .get("class_id")))
        throw fail(
            422,
            "ATTACHMENT_CLASS_MISMATCH",
            "Use a handout uploaded for this assignment's class.");
      if (!a.accountId().equals(f.get("owner_id"))
          || !purpose.equals(f.get("purpose"))
          || !"READY".equals(f.get("state"))
          || (student != null && !student.equals(f.get("student_id"))))
        throw fail(
            422,
            "ATTACHMENT_NOT_OWNED",
            "Each attachment must be a ready file uploaded by you for this purpose and child.");
      s.db.update(
          "insert into file_links(file_id,resource_type,resource_id,created_by) values (?,?,?,?) on conflict(file_id,resource_type,resource_id) do update set active=true",
          id,
          resourceType,
          resource,
          a.accountId());
    }
  }

  public Object own(Actor a, UUID school) {
    a.school(school);
    return map(
        "items",
        s.db.queryForList(
            "select id,purpose,filename,media_type as \"mediaType\",expected_size as size,state,version from school_files where school_id=? and owner_id=? order by created_at desc limit 100",
            school,
            a.accountId()),
        "hasMore",
        false);
  }

  public record InternalFile(UUID schoolId, UUID fileId, UUID ownerId) {}

  public Object internal(InternalFile b) {
    Actor.internalOnly("communication", "finance");
    var f = row(b.schoolId(), b.fileId(), false);
    if (b.ownerId() != null && !b.ownerId().equals(f.get("owner_id")))
      throw fail(404, "NOT_FOUND", "The attachment is unavailable.");
    return map("file", wire(f), "ownerId", f.get("owner_id"));
  }
}
