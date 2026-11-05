package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AcademicImportService {
  private final SchoolService s;
  private final Crypto crypto;

  public AcademicImportService(SchoolService s, Crypto crypto) {
    this.s = s;
    this.crypto = crypto;
  }

  private void role(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object body,
      java.util.function.Supplier<Object> action) {
    role(a, school);
    return s.commands.execute(a.accountId().toString(), school.toString(), path, key, body, action);
  }

  private static final List<String> STUDENT_FIELDS =
      List.of(
          "row_reference",
          "admission_type",
          "admission_number",
          "legacy_reference",
          "full_name",
          "date_of_birth",
          "sex",
          "class_key",
          "department",
          "starts_on",
          "guardian_reference",
          "existing_guardian_id",
          "guardian_name",
          "guardian_phone",
          "guardian_email",
          "existing_login_id",
          "guardian_relationship",
          "verification_reference",
          "subject_codes");
  private static final List<String> TEACHER_FIELDS =
      List.of(
          "row_reference",
          "staff_number",
          "full_name",
          "phone",
          "email",
          "existing_login_id",
          "eligible_levels",
          "subject_codes",
          "class_assignments");

  public Object template(Actor a, UUID school, String kind) {
    role(a, school);
    if (kind == null || !Set.of("STUDENTS", "TEACHERS").contains(kind))
      throw fail(422, "INVALID_IMPORT_KIND", "Choose STUDENTS or TEACHERS.");
    return map(
        "kind",
        kind,
        "columns",
        "STUDENTS".equals(kind) ? STUDENT_FIELDS : TEACHER_FIELDS,
        "maximumRows",
        1000,
        "maximumBytes",
        1048576,
        "classKeyExample",
        "JSS1-A",
        "classAssignmentsExample",
        "JSS1-A/ENGLISH/4;JSS1-B/ENGLISH/4",
        "listSeparator",
        ";",
        "metadataColumns",
        "Add meta_<published-field-key> columns for required school form fields.",
        "guardianGrouping",
        "Use the same guardian_reference with identical verified parent details to link siblings within this import; phone numbers never merge accounts automatically.");
  }

  private String optional(Map<String, String> r, String key) {
    String value = r.get(key);
    return value == null || value.isBlank() ? null : value.strip();
  }

  private String required(Map<String, String> r, String key) {
    return text(optional(r, key), 1, 240, key);
  }

  private LocalDate date(Map<String, String> r, String key) {
    try {
      return LocalDate.parse(required(r, key));
    } catch (RuntimeException ex) {
      throw fail(422, "INVALID_IMPORT_DATE", key + " must use YYYY-MM-DD.");
    }
  }

  private List<String> list(Map<String, String> r, String key) {
    String value = optional(r, key);
    if (value == null) return List.of();
    return Arrays.stream(value.split(";", -1))
        .map(String::strip)
        .filter(v -> !v.isEmpty())
        .toList();
  }

  private UUID classId(UUID school, UUID session, String key) {
    return (UUID)
        s.one(
                "select id from classes where school_id=? and session_id=? and level||'-'||arm=?",
                school,
                session,
                key)
            .get("id");
  }

  private List<UUID> subjects(UUID school, List<String> codes) {
    var ids = new ArrayList<UUID>();
    for (String code : codes)
      ids.add(
          (UUID)
              s.one("select id from subjects where school_id=? and code=?", school, code)
                  .get("id"));
    return ids;
  }

  private String guardianSignature(Map<String, String> row) {
    var data = new TreeMap<String, String>();
    for (String key :
        List.of(
            "existing_guardian_id",
            "guardian_name",
            "guardian_phone",
            "guardian_email",
            "existing_login_id",
            "verification_reference")) data.put(key, Objects.toString(optional(row, key), ""));
    return Crypto.hash(s.json.write(data));
  }

  private Object input(
      Actor a, UUID school, Map<String, Object> job, Map<String, String> r, boolean running) {
    UUID session = (UUID) job.get("session_id");
    String kind = job.get("kind").toString();
    if ("STUDENTS".equals(kind)) {
      String type =
          Objects.toString(
              optional(r, "admission_type"),
              optional(r, "admission_number") == null ? "FRESH" : "LEGACY_MIGRATION");
      if (!Set.of("FRESH", "TRANSFER_IN", "LEGACY_MIGRATION").contains(type))
        throw fail(422, "INVALID_ADMISSION_TYPE", "Choose FRESH, TRANSFER_IN or LEGACY_MIGRATION.");
      if ("LEGACY_MIGRATION".equals(type)) {
        a.school(school, "PRINCIPAL");
        required(r, "admission_number");
        text(optional(r, "legacy_reference"), 5, 160, "Existing-school admission evidence");
      }
      personName(required(r, "full_name"));
      LocalDate dob = date(r, "date_of_birth");
      if (dob.isAfter(LocalDate.now()) || dob.isBefore(LocalDate.of(1900, 1, 1)))
        throw fail(422, "INVALID_BIRTH_DATE", "Use the recorded date of birth in the past.");
      if (!Set.of("MALE", "FEMALE").contains(required(r, "sex")))
        throw fail(422, "INVALID_SEX", "Use MALE or FEMALE.");
      UUID cls = classId(school, session, required(r, "class_key"));
      var c = s.one("select level from classes where id=?", cls);
      SchoolRules.department(c.get("level").toString(), optional(r, "department"));
      LocalDate start = date(r, "starts_on");
      var year = s.one("select starts_on,ends_on from academic_sessions where id=?", session);
      if (start.isBefore(((java.sql.Date) year.get("starts_on")).toLocalDate())
          || start.isAfter(((java.sql.Date) year.get("ends_on")).toLocalDate()))
        throw fail(
            422,
            "INVALID_ENROLMENT_DATE",
            "Use a placement date within the selected import session.");
      var fields = new LinkedHashMap<String, Object>();
      for (var e : r.entrySet())
        if (e.getKey().startsWith("meta_") && optional(r, e.getKey()) != null)
          fields.put(e.getKey().substring(5), e.getValue());
      var meta =
          s.one(
              "select definition from metadata_versions where school_id=? and version=?",
              school,
              job.get("metadata_version"));
      s.validateFields(s.json.tree(meta.get("definition").toString()), fields);
      UUID guardian =
          optional(r, "existing_guardian_id") == null
              ? null
              : uuid(optional(r, "existing_guardian_id"));
      String group = optional(r, "guardian_reference");
      if (group != null && !group.matches("[A-Za-z0-9_.-]{1,80}"))
        throw fail(
            422,
            "INVALID_GUARDIAN_REFERENCE",
            "Use a stable parent-group reference without spaces.");
      if (guardian == null) {
        personName(required(r, "guardian_name"));
        phone(required(r, "guardian_phone"));
        if (optional(r, "guardian_email") != null) email(optional(r, "guardian_email"));
        text(optional(r, "verification_reference"), 5, 160, "Guardian verification reference");
        if (running && group != null) {
          var g =
              s.db.queryForList(
                  "select guardian_id,source_hash from import_guardian_groups where job_id=? and guardian_reference=?",
                  job.get("id"),
                  group);
          if (!g.isEmpty()) {
            if (!Crypto.equal(guardianSignature(r), g.get(0).get("source_hash").toString()))
              throw fail(
                  422,
                  "GUARDIAN_GROUP_MISMATCH",
                  "This parent-group reference has different verified parent details.");
            guardian = (UUID) g.get(0).get("guardian_id");
          }
        }
      } else
        s.one(
            "select id from guardians where school_id=? and id=? and relationship_verified",
            school,
            guardian);
      var g =
          new SchoolService.GuardianInput(
              guardian,
              optional(r, "guardian_name"),
              optional(r, "guardian_phone"),
              optional(r, "guardian_email"),
              optional(r, "existing_login_id"),
              true,
              optional(r, "verification_reference"),
              required(r, "guardian_relationship"));
      return new SchoolService.EnrolInput(
          optional(r, "admission_number"),
          required(r, "full_name"),
          dob,
          required(r, "sex"),
          cls,
          start,
          optional(r, "department"),
          ((Number) job.get("metadata_version")).longValue(),
          fields,
          g,
          type,
          optional(r, "legacy_reference"),
          subjects(school, list(r, "subject_codes")));
    }
    String staffNumber = text(optional(r, "staff_number"), 2, 40, "Staff number");
    if (!staffNumber.matches("[A-Za-z0-9/-]+"))
      throw fail(422, "INVALID_STAFF_NUMBER", "Use letters, numbers, slashes or hyphens.");
    personName(required(r, "full_name"));
    phone(required(r, "phone"));
    if (optional(r, "email") != null) email(optional(r, "email"));
    var levels = list(r, "eligible_levels");
    if (levels.isEmpty()
        || !Set.of("JSS1", "JSS2", "JSS3", "SSS1", "SSS2", "SSS3").containsAll(levels))
      throw fail(
          422,
          "INVALID_ELIGIBLE_LEVELS",
          "Supply eligible secondary levels separated by semicolons.");
    var subjectIds = subjects(school, list(r, "subject_codes"));
    if (subjectIds.isEmpty())
      throw fail(422, "SUBJECTS_REQUIRED", "Choose at least one eligible subject code.");
    var assignments = new ArrayList<SchoolService.AssignmentInput>();
    for (String value : list(r, "class_assignments")) {
      String[] p = value.split("/", -1);
      if (p.length < 3 || p.length > 4)
        throw fail(
            422,
            "INVALID_CLASS_ASSIGNMENT",
            "Use class-key/subject-code/weekly-periods, with an optional department as the fourth part.");
      UUID cls = classId(school, session, p[0]);
      var c = s.one("select level from classes where id=?", cls);
      UUID subject = subjects(school, List.of(p[1])).get(0);
      int periods;
      try {
        periods = Integer.parseInt(p[2]);
      } catch (NumberFormatException ex) {
        throw fail(422, "INVALID_WEEKLY_TARGET", "Use a whole-number weekly target.");
      }
      if (periods < 1
          || periods > 10
          || !subjectIds.contains(subject)
          || !levels.contains(c.get("level")))
        throw fail(
            422,
            "TEACHER_NOT_ELIGIBLE",
            "Every class assignment must match an eligible subject and level, with one to ten periods.");
      String department = p.length == 4 ? p[3] : null;
      if (department != null) SchoolRules.department(c.get("level").toString(), department);
      assignments.add(new SchoolService.AssignmentInput(cls, subject, department, periods));
    }
    if (assignments.isEmpty())
      throw fail(
          422, "CLASS_ASSIGNMENTS_REQUIRED", "Provide at least one actual class assignment.");
    return new SchoolService.TeacherInput(
        required(r, "full_name"),
        required(r, "phone"),
        optional(r, "email"),
        optional(r, "existing_login_id"),
        true,
        levels,
        subjectIds,
        assignments);
  }

  private record Validation(String state, String code, String message, List<String> warnings) {}

  private Validation validate(
      Actor a, UUID school, Map<String, Object> job, Map<String, String> row) {
    try {
      Object body = input(a, school, job, row, false);
      String ref = required(row, "row_reference");
      if (!ref.matches("[A-Za-z0-9_.-]{1,80}"))
        throw fail(
            422,
            "INVALID_ROW_REFERENCE",
            "Use a stable row reference with letters, digits, periods, underscores or hyphens.");
      String hash = Crypto.hash(s.json.write(row));
      var prior =
          s.db.queryForList(
              "select source_hash from academic_import_outcomes where school_id=? and kind=? and row_reference=?",
              school,
              job.get("kind"),
              ref);
      if (!prior.isEmpty()) {
        if (!Crypto.equal(hash, prior.get(0).get("source_hash").toString()))
          throw fail(
              422,
              "ROW_REFERENCE_REUSED",
              "This permanent row reference already identifies a different successful import.");
        return new Validation("READY", null, null, List.of("ALREADY_IMPORTED_REFERENCE"));
      }
      if (body instanceof SchoolService.EnrolInput b) {
        if (b.admissionNumber() != null
            && s.db.queryForObject(
                    "select count(*) from students where school_id=? and admission_number=?",
                    Integer.class,
                    school,
                    b.admissionNumber().toUpperCase(Locale.ROOT))
                > 0)
          throw fail(
              422,
              "DUPLICATE_ADMISSION_NUMBER",
              "This school admission number already exists. Review the existing student instead of enrolling again.");
        if (b.guardian().existingGuardianId() == null
            && s.db.queryForObject(
                    "select count(*) from guardians where school_id=? and phone=?",
                    Integer.class,
                    school,
                    phone(b.guardian().phone()))
                > 0)
          return new Validation(
              "READY", null, null, List.of("POSSIBLE_EXISTING_GUARDIAN_REQUIRES_EXPLICIT_REVIEW"));
      } else if (s.db.queryForObject(
              "select count(*) from staff where school_id=? and upper(staff_number)=upper(?)",
              Integer.class,
              school,
              required(row, "staff_number"))
          > 0)
        throw fail(
            422,
            "DUPLICATE_STAFF_NUMBER",
            "This staff number already exists; review the existing teacher.");
      return new Validation("READY", null, null, List.of());
    } catch (Api.Failure ex) {
      return new Validation("INVALID", ex.code, ex.getMessage(), List.of());
    } catch (RuntimeException ex) {
      return new Validation(
          "INVALID",
          "INVALID_IMPORT_ROW",
          "Check the row's required fields, school references and dates.",
          List.of());
    }
  }

  public record Preview(
      String kind, UUID sessionId, String csv, Map<String, String> columnMapping) {}

  public Object preview(Actor a, UUID school, Preview b, String key) {
    role(a, school);
    template(a, school, b.kind());
    var parsed = Documents.parseCsv(b.csv());
    if (parsed.isEmpty()) throw fail(422, "EMPTY_CSV", "The CSV needs at least one data row.");
    var mapped = new ArrayList<Map<String, String>>();
    List<String> columns = "STUDENTS".equals(b.kind()) ? STUDENT_FIELDS : TEACHER_FIELDS;
    Set<String> refs = new HashSet<>();
    for (var source : parsed) {
      Map<String, String> row = new TreeMap<>();
      if (b.columnMapping() == null || b.columnMapping().isEmpty()) {
        for (var e : source.entrySet())
          if (columns.contains(e.getKey()) || e.getKey().startsWith("meta_"))
            row.put(e.getKey(), e.getValue().strip());
      } else
        for (var e : b.columnMapping().entrySet()) {
          if (!columns.contains(e.getKey()) && !e.getKey().startsWith("meta_"))
            throw fail(422, "UNKNOWN_IMPORT_FIELD", "Map only published import fields.");
          if (!source.containsKey(e.getValue()))
            throw fail(422, "CSV_COLUMN_MISSING", "A mapped CSV header does not exist.");
          row.put(e.getKey(), source.get(e.getValue()).strip());
        }
      String ref = text(optional(row, "row_reference"), 1, 80, "Stable row reference");
      if (!ref.matches("[A-Za-z0-9_.-]{1,80}"))
        throw fail(422, "INVALID_ROW_REFERENCE", "Use a stable row reference without spaces.");
      if (!refs.add(ref))
        throw fail(
            422,
            "DUPLICATE_ROW_REFERENCE",
            "Every row in a preview needs a distinct stable reference.");
      mapped.add(row);
    }
    return command(
        a,
        school,
        "imports",
        key,
        b,
        () -> {
          s.one(
              "select id from academic_sessions where school_id=? and id=?", school, b.sessionId());
          UUID id = UUID.randomUUID();
          long meta =
              s.db.queryForObject(
                  "select max(version) from metadata_versions where school_id=?",
                  Long.class,
                  school);
          s.db.update(
              "insert into academic_imports(id,school_id,session_id,kind,metadata_version,actor_cipher,created_by) values (?,?,?,?,?,?,?)",
              id,
              school,
              b.sessionId(),
              b.kind(),
              meta,
              crypto.seal(s.json.write(a)),
              a.accountId());
          var job = s.one("select * from academic_imports where id=?", id);
          Map<String, String> groups = new HashMap<>();
          int index = 0;
          for (var row : mapped) {
            var v = validate(a, school, job, row);
            String group = optional(row, "guardian_reference");
            if (group != null) {
              String signature = guardianSignature(row);
              if (groups.putIfAbsent(group, signature) != null
                  && !groups.get(group).equals(signature))
                v =
                    new Validation(
                        "INVALID",
                        "GUARDIAN_GROUP_MISMATCH",
                        "Rows sharing a guardian reference must contain identical verified parent details.",
                        List.of());
            }
            s.db.update(
                "insert into academic_import_rows(id,job_id,row_number,row_reference,source_hash,input_cipher,state,error_code,error_message,warnings) values (?,?,?,?,?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(),
                id,
                ++index,
                required(row, "row_reference"),
                Crypto.hash(s.json.write(row)),
                crypto.seal(s.json.write(row)),
                v.state(),
                v.code(),
                v.message(),
                s.json.write(v.warnings()));
          }
          s.commands.audit(
              school,
              a.accountId(),
              "IMPORT_PREVIEW_CREATED",
              "IMPORT",
              id,
              null,
              map("kind", b.kind(), "rows", mapped.size()));
          return detail(a, school, id);
        });
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> rowData(Map<String, Object> row) {
    return (Map<String, String>)
        (Map<?, ?>) s.json.read(crypto.open(row.get("input_cipher").toString()), Map.class);
  }

  public Object detail(Actor a, UUID school, UUID id) {
    role(a, school);
    var job = s.one("select * from academic_imports where school_id=? and id=?", school, id);
    var rows =
        s.db.queryForList(
            "select * from academic_import_rows where job_id=? order by row_number", id);
    var out = new ArrayList<Object>();
    for (var r : rows)
      out.add(
          map(
              "id",
              r.get("id"),
              "rowNumber",
              r.get("row_number"),
              "rowReference",
              r.get("row_reference"),
              "state",
              r.get("state"),
              "version",
              r.get("version"),
              "input",
              rowData(r),
              "errorCode",
              r.get("error_code"),
              "errorMessage",
              r.get("error_message"),
              "warnings",
              s.json.value(r.get("warnings").toString()),
              "result",
              r.get("result_cipher") == null
                  ? null
                  : s.json.value(crypto.open(r.get("result_cipher").toString()))));
    return map(
        "id",
        id,
        "kind",
        job.get("kind"),
        "sessionId",
        job.get("session_id"),
        "state",
        job.get("state"),
        "version",
        job.get("version"),
        "rows",
        out);
  }

  public Object list(Actor a, UUID school) {
    role(a, school);
    return map(
        "items",
        s.db.queryForList(
            "select id,kind,state,version,created_at as \"createdAt\" from academic_imports where school_id=? order by created_at desc limit 100",
            school),
        "hasMore",
        false);
  }

  public record Correction(Map<String, String> fields, String reason) {}

  public Object correct(
      Actor a, UUID school, UUID jobId, UUID rowId, Correction b, long version, String key) {
    return command(
        a,
        school,
        "imports/" + jobId + "/rows/" + rowId,
        key,
        map("input", b, "version", version),
        () -> {
          var job =
              s.one(
                  "select * from academic_imports where school_id=? and id=? for update",
                  school,
                  jobId);
          if (Set.of("QUEUED", "PROCESSING", "COMPLETE").contains(job.get("state")))
            throw fail(
                409,
                "IMPORT_NOT_EDITABLE",
                "Edit failed or invalid rows while the import is at review.");
          var row =
              s.one(
                  "select * from academic_import_rows where job_id=? and id=? for update",
                  jobId,
                  rowId);
          expectVersion(version, ((Number) row.get("version")).longValue());
          if ("SUCCEEDED".equals(row.get("state")))
            throw fail(
                409,
                "IMPORTED_ROW_IMMUTABLE",
                "Successful rows are preserved and are never imported again.");
          if (b.fields() == null
              || !row.get("row_reference").equals(b.fields().get("row_reference")))
            throw fail(
                422, "ROW_REFERENCE_IMMUTABLE", "Keep this row's stable reference unchanged.");
          var validation = validate(a, school, job, b.fields());
          s.db.update(
              "update academic_import_rows set input_cipher=?,source_hash=?,state=?,error_code=?,error_message=?,warnings=?::jsonb,version=version+1 where id=?",
              crypto.seal(s.json.write(b.fields())),
              Crypto.hash(s.json.write(b.fields())),
              validation.state(),
              validation.code(),
              validation.message(),
              s.json.write(validation.warnings()),
              rowId);
          s.db.update(
              "update academic_imports set state='PREVIEW',version=version+1 where id=?", jobId);
          s.commands.audit(
              school,
              a.accountId(),
              "IMPORT_ROW_CORRECTED",
              "IMPORT",
              jobId,
              text(b.reason(), 5, 500, "Correction reason"),
              map("rowId", rowId));
          return detail(a, school, jobId);
        });
  }

  public record Confirmation(
      boolean relationshipsVerified, boolean duplicatesReviewed, String reason) {}

  public Object confirm(Actor a, UUID school, UUID id, Confirmation b, long version, String key) {
    return command(
        a,
        school,
        "imports/" + id + "/confirm",
        key,
        map("input", b, "version", version),
        () -> {
          var job =
              s.one(
                  "select * from academic_imports where school_id=? and id=? for update",
                  school,
                  id);
          expectVersion(version, ((Number) job.get("version")).longValue());
          if (!Set.of("PREVIEW", "PARTIAL", "PAUSED").contains(job.get("state")))
            throw fail(409, "IMPORT_ALREADY_RUNNING", "Review this import's current progress.");
          if (!b.relationshipsVerified() || !b.duplicatesReviewed())
            throw fail(
                422,
                "IMPORT_REVIEW_REQUIRED",
                "Confirm that the school verified parent/account relationships and reviewed duplicates before applying rows.");
          var rows =
              s.db.queryForList(
                  "select * from academic_import_rows where job_id=? and state<>'SUCCEEDED' order by row_number",
                  id);
          if (rows.isEmpty()) throw fail(409, "IMPORT_COMPLETE", "All rows already succeeded.");
          for (var row : rows) {
            var v = validate(a, school, job, rowData(row));
            if (!"READY".equals(v.state()))
              throw fail(
                  422,
                  "IMPORT_ROWS_INVALID",
                  "Resolve every invalid or failed row before confirmation.");
          }
          String reason = text(b.reason(), 10, 500, "Reviewed import reason");
          s.db.update(
              "update academic_import_rows set state='READY',error_code=null,error_message=null where job_id=? and state<>'SUCCEEDED'",
              id);
          s.db.update(
              "update academic_imports set state='QUEUED',actor_cipher=?,review_reason=?,version=version+1 where id=?",
              crypto.seal(s.json.write(a)),
              reason,
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "IMPORT_CONFIRMED",
              "IMPORT",
              id,
              reason,
              map("remainingRows", rows.size()));
          return map("id", id, "state", "QUEUED", "version", version + 1);
        });
  }

  @Scheduled(fixedDelayString = "${educore.import-delay-ms:3000}")
  public void process() {
    UUID lease = UUID.randomUUID();
    var jobs =
        s.db.queryForList(
            "update academic_imports set state='PROCESSING',lease_token=?,lease_until=now()+interval '2 minutes' where id=(select id from academic_imports where state='QUEUED' or (state='PROCESSING' and lease_until<now()) order by created_at for update skip locked limit 1) returning *",
            lease);
    if (jobs.isEmpty()) return;
    var job = jobs.get(0);
    UUID school = (UUID) job.get("school_id"), jobId = (UUID) job.get("id");
    Actor a = s.json.read(crypto.open(job.get("actor_cipher").toString()), Actor.class);
    var permissions =
        s.db.queryForList(
            "select id from staff where school_id=? and id=? and active and staff_role=?",
            school,
            a.context().subjectId(),
            a.context().role());
    if (permissions.isEmpty()) {
      s.db.update(
          "update academic_imports set state='PAUSED',lease_token=null,lease_until=null,version=version+1 where id=? and lease_token=?",
          jobId,
          lease);
      return;
    }
    for (var row :
        s.db.queryForList(
            "select * from academic_import_rows where job_id=? and state='READY' order by row_number limit 50",
            jobId)) {
      UUID rowId = (UUID) row.get("id");
      try {
        var fields = rowData(row);
        Object result =
            s.commands.transaction(
                () -> {
                  String ref = row.get("row_reference").toString();
                  s.db.queryForObject(
                      "select pg_advisory_xact_lock(hashtextextended(?,0))",
                      Object.class,
                      "import-row|" + school + "|" + job.get("kind") + "|" + ref);
                  var previous =
                      s.db.queryForList(
                          "select * from academic_import_outcomes where school_id=? and kind=? and row_reference=?",
                          school,
                          job.get("kind"),
                          ref);
                  if (!previous.isEmpty()) {
                    if (!Crypto.equal(
                        previous.get(0).get("source_hash").toString(),
                        row.get("source_hash").toString()))
                      throw fail(
                          409,
                          "ROW_REFERENCE_REUSED",
                          "This row reference identifies an earlier successful import.");
                    return s.json.value(
                        crypto.open(previous.get(0).get("result_cipher").toString()));
                  }
                  String commandKey =
                      UUID.nameUUIDFromBytes(
                              ("import|" + school + "|" + job.get("kind") + "|" + ref)
                                  .getBytes(StandardCharsets.UTF_8))
                          .toString();
                  Object input = input(a, school, job, fields, true), out;
                  if (input instanceof SchoolService.EnrolInput student) {
                    out = s.enrol(a, school, student, commandKey);
                    String group = optional(fields, "guardian_reference");
                    if (group != null && student.guardian().existingGuardianId() == null) {
                      @SuppressWarnings("unchecked")
                      var r = (Map<String, Object>) out;
                      s.db.update(
                          "insert into import_guardian_groups(job_id,guardian_reference,guardian_id,source_hash) values (?,?,?,?) on conflict do nothing",
                          jobId,
                          group,
                          uuid(r.get("guardianId").toString()),
                          guardianSignature(fields));
                    }
                  } else {
                    out = s.teacher(a, school, (SchoolService.TeacherInput) input, commandKey);
                    @SuppressWarnings("unchecked")
                    var r = (Map<String, Object>) out;
                    s.db.update(
                        "update staff set staff_number=? where school_id=? and id=?",
                        required(fields, "staff_number").toUpperCase(Locale.ROOT),
                        school,
                        uuid(r.get("id").toString()));
                  }
                  s.db.update(
                      "insert into academic_import_outcomes(school_id,kind,row_reference,source_hash,result_cipher) values (?,?,?,?,?)",
                      school,
                      job.get("kind"),
                      ref,
                      row.get("source_hash"),
                      crypto.seal(s.json.write(out)));
                  return out;
                });
        s.db.update(
            "update academic_import_rows set state='SUCCEEDED',result_cipher=?,error_code=null,error_message=null,version=version+1 where id=?",
            crypto.seal(s.json.write(result)),
            rowId);
      } catch (RuntimeException ex) {
        String code = ex instanceof Api.Failure f ? f.code : "IMPORT_ROW_CONFLICT";
        String message =
            ex instanceof Api.Failure f
                ? f.getMessage()
                : "The row could not be applied. Review duplicate records and related school setup before retrying.";
        s.db.update(
            "update academic_import_rows set state='FAILED',error_code=?,error_message=?,version=version+1 where id=?",
            code,
            message,
            rowId);
      }
      s.db.update(
          "update academic_imports set lease_until=now()+interval '2 minutes' where id=? and lease_token=?",
          jobId,
          lease);
    }
    int
        remaining =
            s.db.queryForObject(
                "select count(*) from academic_import_rows where job_id=? and state='READY'",
                Integer.class,
                jobId),
        failed =
            s.db.queryForObject(
                "select count(*) from academic_import_rows where job_id=? and state in ('FAILED','INVALID')",
                Integer.class,
                jobId);
    s.db.update(
        "update academic_imports set state=?,lease_token=null,lease_until=null,version=version+1 where id=? and lease_token=?",
        remaining > 0 ? "QUEUED" : failed > 0 ? "PARTIAL" : "COMPLETE",
        jobId,
        lease);
  }

  public byte[] errors(Actor a, UUID school, UUID id) {
    role(a, school);
    s.one("select id from academic_imports where school_id=? and id=?", school, id);
    return Documents.csv(
        List.of("row_number", "row_reference", "state", "error_code", "error_message"),
        s
            .db
            .queryForList(
                "select row_number,row_reference,state,error_code,error_message from academic_import_rows where job_id=? and state in ('INVALID','FAILED') order by row_number",
                id)
            .stream()
            .map(
                r ->
                    List.of(
                        r.get("row_number"),
                        r.get("row_reference"),
                        r.get("state"),
                        Objects.toString(r.get("error_code"), ""),
                        Objects.toString(r.get("error_message"), "")))
            .toList());
  }
}
