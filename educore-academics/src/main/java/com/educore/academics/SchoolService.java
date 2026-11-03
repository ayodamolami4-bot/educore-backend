package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SchoolService {
  final JdbcTemplate db;
  final Commands commands;
  final Json json;
  final Outbox outbox;

  public SchoolService(JdbcTemplate db, Commands commands, Json json, Outbox outbox) {
    this.db = db;
    this.commands = commands;
    this.json = json;
    this.outbox = outbox;
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "The school record is unavailable.");
    return rows.get(0);
  }

  Map<String, Object> school(UUID id) {
    return one("select * from schools where id=?", id);
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object body,
      java.util.function.Supplier<Object> action) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return commands.execute(a.accountId().toString(), school.toString(), path, key, body, action);
  }

  public record Principal(String fullName, String phone) {}

  public record Bootstrap(UUID schoolId, String code, String name, Principal principal) {}

  public Object bootstrap(Bootstrap b, String key) {
    Actor.internalOnly("operator");
    return commands.execute(
        "operator",
        "platform",
        "school-bootstrap",
        key,
        b,
        () -> {
          if (b.principal() == null)
            throw fail(422, "PRINCIPAL_REQUIRED", "Provide the first principal details.");
          UUID id = b.schoolId() == null ? UUID.randomUUID() : b.schoolId();
          String code = text(b.code(), 2, 30, "School code").toUpperCase(Locale.ROOT);
          if (!code.matches("[A-Z0-9-]+"))
            throw fail(422, "INVALID_SCHOOL_CODE", "Use letters, numbers and hyphens.");
          String name = text(b.name(), 2, 160, "School name");
          String fullName = personName(b.principal().fullName()),
              phone = phone(b.principal().phone());
          UUID staff = UUID.randomUUID();
          db.update("insert into schools(id,code,name) values (?,?,?)", id, code, name);
          db.update(
              "insert into staff(id,school_id,full_name,phone,staff_role) values (?,?,?,?,'PRINCIPAL')",
              staff,
              id,
              fullName,
              phone);
          db.update(
              "insert into metadata_versions(school_id,version,definition) values (?,1,?::jsonb)",
              id,
              json.write(
                  map(
                      "studentFields",
                      List.of(
                          map(
                              "key",
                              "homeAddress",
                              "type",
                              "TEXT",
                              "required",
                              false,
                              "maxLength",
                              240),
                          map(
                              "key",
                              "previousSchool",
                              "type",
                              "TEXT",
                              "required",
                              false,
                              "maxLength",
                              160)))));
          UUID event =
              outbox.add(
                  "identity",
                  "SCHOOL_PROVISIONED",
                  map(
                      "schoolId",
                      id,
                      "schoolName",
                      name,
                      "principal",
                      map("id", staff, "fullName", fullName, "phone", phone)));
          return map(
              "schoolId",
              id,
              "principalSubjectId",
              staff,
              "identityProvisioningEventId",
              event,
              "status",
              "ACCOUNTS_PENDING");
        });
  }

  public Object metadata(Actor a, UUID school) {
    a.school(school);
    var s = school(school);
    var m =
        one(
            "select * from metadata_versions where school_id=? order by version desc limit 1",
            school);
    return map(
        "schoolId",
        school,
        "schoolName",
        s.get("name"),
        "timezone",
        s.get("timezone"),
        "currency",
        s.get("currency"),
        "version",
        m.get("version"),
        "definition",
        json.value(m.get("definition").toString()),
        "schoolDay",
        map(
            "start",
            "08:00",
            "end",
            "13:00",
            "breakStart",
            "11:30",
            "breakEnd",
            "12:00",
            "wednesdayPtEnabled",
            s.get("wednesday_pt"),
            "fridaySportsStart",
            "12:00"),
        "departments",
        List.of("SCIENCE", "ARTS", "COMMERCIAL"));
  }

  public record SessionInput(String name, LocalDate startsOn, LocalDate endsOn) {}

  public Object session(Actor a, UUID school, SessionInput b, String key) {
    return command(
        a,
        school,
        "academic-sessions",
        key,
        b,
        () -> {
          dates(b.startsOn(), b.endsOn());
          UUID id = UUID.randomUUID();
          db.update(
              "insert into academic_sessions(id,school_id,name,starts_on,ends_on) values (?,?,?,?,?)",
              id,
              school,
              text(b.name(), 4, 40, "Session name"),
              b.startsOn(),
              b.endsOn());
          return map(
              "id",
              id,
              "version",
              1,
              "name",
              b.name(),
              "startsOn",
              b.startsOn(),
              "endsOn",
              b.endsOn());
        });
  }

  static void dates(LocalDate start, LocalDate end) {
    if (start == null || end == null || !start.isBefore(end))
      throw fail(422, "INVALID_DATES", "The start date must precede the end date.");
  }

  public Object sessions(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS", "TEACHER");
    return map(
        "items",
        db.queryForList(
            "select id,name,starts_on as \"startsOn\",ends_on as \"endsOn\",version from academic_sessions where school_id=? order by starts_on desc",
            school),
        "hasMore",
        false);
  }

  public record TermInput(UUID sessionId, int number, LocalDate startsOn, LocalDate endsOn) {}

  public Object term(Actor a, UUID school, TermInput b, String key) {
    return command(
        a,
        school,
        "terms",
        key,
        b,
        () -> {
          dates(b.startsOn(), b.endsOn());
          var s =
              one(
                  "select * from academic_sessions where school_id=? and id=?",
                  school,
                  b.sessionId());
          if (b.number() < 1
              || b.number() > 3
              || b.startsOn().isBefore(((java.sql.Date) s.get("starts_on")).toLocalDate())
              || b.endsOn().isAfter(((java.sql.Date) s.get("ends_on")).toLocalDate()))
            throw fail(422, "INVALID_TERM", "Term 1–3 must fall within its academic session.");
          UUID id = UUID.randomUUID();
          db.update(
              "insert into terms(id,school_id,session_id,number,starts_on,ends_on) values (?,?,?,?,?,?)",
              id,
              school,
              b.sessionId(),
              b.number(),
              b.startsOn(),
              b.endsOn());
          return map("id", id, "version", 1);
        });
  }

  public record ClassInput(UUID sessionId, String level, String arm, UUID attendanceTeacherId) {}

  public Object addClass(Actor a, UUID school, ClassInput b, String key) {
    return command(
        a,
        school,
        "classes",
        key,
        b,
        () -> {
          if (b.level() == null
              || !Set.of("JSS1", "JSS2", "JSS3", "SSS1", "SSS2", "SSS3").contains(b.level()))
            throw fail(422, "INVALID_LEVEL", "Select JSS1–JSS3 or SSS1–SSS3.");
          UUID id = UUID.randomUUID();
          db.update(
              "insert into classes(id,school_id,session_id,level,arm,attendance_teacher_id) values (?,?,?,?,?,?)",
              id,
              school,
              b.sessionId(),
              b.level(),
              text(b.arm(), 1, 10, "Class arm").toUpperCase(Locale.ROOT),
              b.attendanceTeacherId());
          return map("id", id, "version", 1, "level", b.level(), "arm", b.arm());
        });
  }

  public Object classes(Actor a, UUID school, UUID session) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    if ("TEACHER".equals(c.role()))
      return map(
          "items",
          db.queryForList(
              "select distinct c.id,c.session_id as \"sessionId\",c.level,c.arm,c.version from classes c left join teaching_assignments t on t.class_id=c.id where c.school_id=? and (c.attendance_teacher_id=? or t.teacher_id=?) and (?::uuid is null or c.session_id=?::uuid) order by c.level,c.arm",
              school,
              c.subjectId(),
              c.subjectId(),
              session,
              session),
          "hasMore",
          false);
    return map(
        "items",
        db.queryForList(
            "select id,session_id as \"sessionId\",level,arm,attendance_teacher_id as \"attendanceTeacherId\",version from classes where school_id=? and (?::uuid is null or session_id=?::uuid) order by level,arm",
            school,
            session,
            session),
        "hasMore",
        false);
  }

  public Object attendanceTeacher(
      Actor a, UUID school, UUID classId, UUID teacher, long expected, String key) {
    return command(
        a,
        school,
        "class/" + classId + "/attendance-teacher",
        key,
        map("teacherId", teacher, "version", expected),
        () -> {
          var c =
              one("select * from classes where school_id=? and id=? for update", school, classId);
          expectVersion(expected, ((Number) c.get("version")).longValue());
          one("select id from staff where school_id=? and id=? and active", school, teacher);
          db.update(
              "update classes set attendance_teacher_id=?,version=version+1 where id=?",
              teacher,
              classId);
          return map("id", classId, "version", expected + 1, "attendanceTeacherId", teacher);
        });
  }

  public record SubjectInput(String code, String name, boolean core) {}

  public Object subject(Actor a, UUID school, SubjectInput b, String key) {
    return command(
        a,
        school,
        "subjects",
        key,
        b,
        () -> {
          String code = text(b.code(), 2, 20, "Subject code").toUpperCase(Locale.ROOT);
          if (!code.matches("[A-Z][A-Z0-9_]*"))
            throw fail(422, "INVALID_SUBJECT_CODE", "Use an uppercase subject code.");
          UUID id = UUID.randomUUID();
          db.update(
              "insert into subjects(id,school_id,code,name,core) values (?,?,?,?,?)",
              id,
              school,
              code,
              text(b.name(), 2, 100, "Subject name"),
              b.core());
          return map("id", id, "code", code, "name", b.name());
        });
  }

  public Object subjects(Actor a, UUID school) {
    a.school(school);
    return map(
        "items",
        db.queryForList(
            "select id,code,name,core from subjects where school_id=? order by name", school),
        "hasMore",
        false);
  }

  public record AssignmentInput(
      UUID classId, UUID subjectId, String department, int weeklyPeriods) {}

  public record TeacherInput(
      String fullName,
      String phone,
      String email,
      String existingLoginId,
      boolean relationshipVerified,
      List<String> eligibleLevels,
      List<UUID> subjectIds,
      List<AssignmentInput> assignments) {}

  public Object teacher(Actor a, UUID school, TeacherInput b, String key) {
    return command(
        a,
        school,
        "teachers",
        key,
        b,
        () -> {
          String name = personName(b.fullName()), phone = phone(b.phone());
          if (b.eligibleLevels() == null
              || b.eligibleLevels().isEmpty()
              || b.subjectIds() == null
              || b.subjectIds().isEmpty()
              || b.assignments() == null
              || b.assignments().isEmpty())
            throw fail(
                422,
                "TEACHING_SCOPE_REQUIRED",
                "Choose eligible levels, subjects and at least one actual class assignment.");
          if (!Set.of("JSS1", "JSS2", "JSS3", "SSS1", "SSS2", "SSS3")
              .containsAll(b.eligibleLevels()))
            throw fail(422, "INVALID_LEVEL", "Choose supported teaching levels.");
          UUID id = UUID.randomUUID();
          db.update(
              "insert into staff(id,school_id,full_name,phone,email) values (?,?,?,?,?)",
              id,
              school,
              name,
              phone,
              b.email() == null ? null : email(b.email()));
          for (String level : new HashSet<>(b.eligibleLevels()))
            for (UUID subject : new HashSet<>(b.subjectIds()))
              db.update(
                  "insert into staff_qualifications(school_id,staff_id,subject_id,level) values (?,?,?,?)",
                  school,
                  id,
                  subject,
                  level);
          for (var assignment : b.assignments()) assignment(school, id, assignment);
          UUID event =
              outbox.add(
                  "identity",
                  "TEACHER_REGISTERED",
                  map(
                      "schoolId",
                      school,
                      "schoolName",
                      school(school).get("name"),
                      "teacher",
                      map(
                          "id",
                          id,
                          "fullName",
                          name,
                          "phone",
                          phone,
                          "existingLoginId",
                          b.existingLoginId(),
                          "relationshipVerified",
                          b.relationshipVerified())));
          commands.audit(
              school,
              a.accountId(),
              "TEACHER_REGISTERED",
              "TEACHER",
              id,
              null,
              map("assignmentCount", b.assignments().size()));
          return map("id", id, "version", 1, "onboardingEventId", event);
        });
  }

  UUID assignment(UUID school, UUID teacher, AssignmentInput b) {
    var c =
        one("select level from classes where school_id=? and id=? for update", school, b.classId());
    String department = b.department();
    if (department != null) {
      department = SchoolRules.department((String) c.get("level"), department);
    }
    if (db.queryForObject(
            "select count(*) from staff_qualifications q join staff s on s.id=q.staff_id where q.school_id=? and q.staff_id=? and q.subject_id=? and q.level=? and s.active",
            Integer.class,
            school,
            teacher,
            b.subjectId(),
            c.get("level"))
        == 0)
      throw fail(
          422,
          "TEACHER_NOT_ELIGIBLE",
          "This teacher is not eligible for the selected subject and level.");
    if (db.queryForObject(
            "select count(*) from teaching_assignments where class_id=? and subject_id=? and active and (department is null or ?::text is null or department=?::text)",
            Integer.class,
            b.classId(),
            b.subjectId(),
            department,
            department)
        > 0)
      throw fail(
          409,
          "SUBJECT_SCOPE_OVERLAP",
          "A common or matching-department teaching assignment already covers this subject; use the reasoned handover action.");
    if (b.weeklyPeriods() < 1 || b.weeklyPeriods() > 10)
      throw fail(422, "INVALID_WEEKLY_TARGET", "Weekly periods must be between 1 and 10.");
    UUID id = UUID.randomUUID();
    db.update(
        "insert into teaching_assignments(id,school_id,class_id,subject_id,teacher_id,department,weekly_periods) values (?,?,?,?,?,?,?)",
        id,
        school,
        b.classId(),
        b.subjectId(),
        teacher,
        department,
        b.weeklyPeriods());
    return id;
  }

  public Object teachers(Actor a, UUID school, UUID cursor, int limit) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var rows =
        db.queryForList(
            "select id,full_name as \"fullName\",phone,email,active,version from staff where school_id=? and (?::uuid is null or id>?::uuid) order by id limit ?",
            school,
            cursor,
            cursor,
            limit + 1);
    return page(rows, limit, r -> r.get("id").toString());
  }

  public record GuardianInput(
      UUID existingGuardianId,
      String fullName,
      String phone,
      String email,
      String existingLoginId,
      boolean relationshipVerified,
      String verificationReference,
      String relationship) {}

  public record EnrolInput(
      String admissionNumber,
      String fullName,
      LocalDate dateOfBirth,
      String sex,
      UUID classId,
      LocalDate startsOn,
      String department,
      long metadataVersion,
      Map<String, Object> fields,
      GuardianInput guardian,
      String admissionType,
      String legacyReference,
      List<UUID> subjectIds) {}

  public Object enrol(Actor a, UUID school, EnrolInput b, String key) {
    return command(
        a,
        school,
        "students",
        key,
        b,
        () -> {
          lockPlacements(school);
          var c =
              one(
                  "select c.*,s.starts_on as session_start,s.ends_on as session_end from classes c join academic_sessions s on s.id=c.session_id where c.school_id=? and c.id=? for update of c",
                  school,
                  b.classId());
          String admissionType = b.admissionType() == null ? "FRESH" : b.admissionType();
          if (!Set.of("FRESH", "TRANSFER_IN", "LEGACY_MIGRATION").contains(admissionType))
            throw fail(
                422, "INVALID_ADMISSION_TYPE", "Choose FRESH, TRANSFER_IN or LEGACY_MIGRATION.");
          if ("LEGACY_MIGRATION".equals(admissionType)) {
            a.school(school, "PRINCIPAL");
            text(b.legacyReference(), 5, 160, "Existing-school admission evidence");
          } else {
            if (b.admissionNumber() != null && !b.admissionNumber().isBlank())
              throw fail(
                  422,
                  "ADMISSION_NUMBER_AUTOMATIC",
                  "Fresh and incoming transfer admissions receive an automatic school admission number.");
            if ("SSS3".equals(c.get("level"))
                && !((java.sql.Date) c.get("session_start"))
                    .toLocalDate()
                    .isBefore(LocalDate.of(2026, 9, 1)))
              throw fail(
                  422,
                  "EXTERNAL_SSS3_ADMISSION_RESTRICTED",
                  "New external SSS3 admission is restricted for this cohort. Record an existing school pupil through a principal-reviewed legacy migration or use internal promotion from SSS2.");
          }
          if (c.get("capacity") != null
              && db.queryForObject(
                      "select count(*) from enrolments where class_id=? and (ends_on is null or ends_on>=?::date)",
                      Integer.class,
                      b.classId(),
                      b.startsOn())
                  >= ((Number) c.get("capacity")).intValue())
            throw fail(409, "CLASS_FULL", "This class has reached its configured capacity.");
          LocalDate start = b.startsOn();
          if (start == null
              || start.isBefore(((java.sql.Date) c.get("session_start")).toLocalDate())
              || start.isAfter(((java.sql.Date) c.get("session_end")).toLocalDate()))
            throw fail(
                422,
                "INVALID_ENROLMENT_DATE",
                "Enrolment must start within the selected class session.");
          if (b.dateOfBirth() == null
              || b.dateOfBirth().isAfter(LocalDate.now(ZoneId.of("Africa/Lagos")))
              || b.dateOfBirth().isBefore(LocalDate.of(1900, 1, 1)))
            throw fail(422, "INVALID_BIRTH_DATE", "Use a valid date of birth in the past.");
          if (b.sex() == null || !Set.of("MALE", "FEMALE").contains(b.sex()))
            throw fail(422, "INVALID_SEX", "Select the student's recorded sex.");
          String name = personName(b.fullName()),
              department = SchoolRules.department((String) c.get("level"), b.department());
          var metadata =
              one(
                  "select * from metadata_versions where school_id=? order by version desc limit 1",
                  school);
          expectVersion(b.metadataVersion(), ((Number) metadata.get("version")).longValue());
          Map<String, Object> fields = b.fields() == null ? Map.of() : b.fields();
          validateFields(json.tree(metadata.get("definition").toString()), fields);
          GuardianInput g = b.guardian();
          if (g == null)
            throw fail(422, "GUARDIAN_REQUIRED", "Provide a new or existing school guardian.");
          UUID guardian;
          Map<String, Object> parent;
          if (g.existingGuardianId() != null) {
            guardian = g.existingGuardianId();
            var row = one("select * from guardians where school_id=? and id=?", school, guardian);
            parent =
                map(
                    "id",
                    guardian,
                    "fullName",
                    row.get("full_name"),
                    "phone",
                    row.get("phone"),
                    "existingLoginId",
                    row.get("existing_login_id"),
                    "relationshipVerified",
                    row.get("relationship_verified"));
          } else {
            if (!g.relationshipVerified())
              throw fail(
                  422,
                  "RELATIONSHIP_NOT_VERIFIED",
                  "Verify the guardian relationship in person before enrolment.");
            guardian = UUID.randomUUID();
            String parentName = personName(g.fullName()),
                parentPhone = phone(g.phone()),
                reference = text(g.verificationReference(), 4, 160, "Verification reference");
            db.update(
                "insert into guardians(id,school_id,full_name,phone,email,existing_login_id,relationship_verified,verification_reference) values (?,?,?,?,?,?,true,?)",
                guardian,
                school,
                parentName,
                parentPhone,
                g.email() == null ? null : email(g.email()),
                g.existingLoginId(),
                reference);
            parent =
                map(
                    "id",
                    guardian,
                    "fullName",
                    parentName,
                    "phone",
                    parentPhone,
                    "existingLoginId",
                    g.existingLoginId(),
                    "relationshipVerified",
                    true);
          }
          UUID student = UUID.randomUUID(), enrolment = UUID.randomUUID();
          String admission;
          if ("LEGACY_MIGRATION".equals(admissionType))
            admission =
                text(b.admissionNumber(), 1, 40, "Existing admission number")
                    .toUpperCase(Locale.ROOT);
          else {
            String code = school(school).get("code").toString();
            if (code.length() > 20) code = code.substring(0, 20);
            do {
              long n =
                  db.queryForObject(
                      "insert into student_number_sequences(school_id,session_id,next_number) values (?,?,2) on conflict(school_id,session_id) do update set next_number=student_number_sequences.next_number+1 returning next_number-1",
                      Long.class,
                      school,
                      c.get("session_id"));
              admission =
                  code
                      + "/"
                      + ((java.sql.Date) c.get("session_start")).toLocalDate().getYear()
                      + "/"
                      + String.format(Locale.ROOT, "%06d", n);
            } while (db.queryForObject(
                    "select count(*) from students where school_id=? and admission_number=?",
                    Integer.class,
                    school,
                    admission)
                > 0);
          }
          if (!admission.matches("[A-Z0-9/-]+"))
            throw fail(
                422, "INVALID_ADMISSION_NUMBER", "Use letters, numbers, slashes or hyphens.");
          db.update(
              "insert into students(id,school_id,admission_number,full_name,date_of_birth,sex,metadata) values (?,?,?,?,?,?,?::jsonb)",
              student,
              school,
              admission,
              name,
              b.dateOfBirth(),
              b.sex(),
              json.write(fields));
          db.update(
              "insert into enrolments(id,school_id,student_id,session_id,class_id,department,starts_on) values (?,?,?,?,?,?,?)",
              enrolment,
              school,
              student,
              c.get("session_id"),
              b.classId(),
              department,
              start);
          db.update(
              "update students set admission_type=?,legacy_reference=? where id=?",
              admissionType,
              b.legacyReference(),
              student);
          enrolChoices(
              school, student, (UUID) c.get("session_id"), b.classId(), department, b.subjectIds());
          db.update(
              "insert into guardian_links(school_id,student_id,guardian_id,relationship,primary_contact) values (?,?,?,?,true)",
              school,
              student,
              guardian,
              text(g.relationship(), 2, 40, "Guardian relationship"));
          UUID event =
              outbox.add(
                  "identity",
                  "STUDENT_ENROLLED",
                  map(
                      "schoolId",
                      school,
                      "schoolName",
                      school(school).get("name"),
                      "student",
                      map("id", student, "fullName", name, "phone", parent.get("phone")),
                      "guardian",
                      parent));
          db.update(
              "insert into enrolment_events(student_id,event_id) values (?,?)", student, event);
          commands.audit(
              school,
              a.accountId(),
              "STUDENT_ENROLLED",
              "STUDENT",
              student,
              null,
              map("classId", b.classId(), "guardianId", guardian));
          return map(
              "id",
              student,
              "version",
              1,
              "admissionNumber",
              admission,
              "fullName",
              name,
              "classId",
              b.classId(),
              "enrolmentId",
              enrolment,
              "guardianId",
              guardian,
              "onboardingStatus",
              "ACCOUNTS_PENDING",
              "onboardingEventId",
              event);
        });
  }

  void lockPlacements(UUID school) {
    db.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        "school-placements:" + school);
  }

  void validateChoices(UUID classId, String department, List<UUID> choices) {
    var templates =
        db.queryForList(
            "select ct.definition from class_curricula cc join curriculum_templates ct on ct.id=cc.curriculum_id where cc.class_id=? and cc.department in ('',coalesce(?::text,''))",
            classId,
            department);
    if (templates.isEmpty()) {
      if (choices != null && !choices.isEmpty())
        throw fail(
            422,
            "CURRICULUM_REQUIRED",
            "Publish and apply the class curriculum before choosing alternatives.");
      return;
    }
    Set<UUID> selected = new HashSet<>(choices == null ? List.of() : choices);
    Map<String, Integer> groups = new HashMap<>();
    Set<UUID> allowed = new HashSet<>();
    for (var template : templates)
      for (var r : json.tree(template.get("definition").toString()))
        if (!r.path("required").asBoolean()) {
          UUID id = UUID.fromString(r.path("subjectId").asText());
          allowed.add(id);
          String group = r.path("choiceGroup").asText();
          groups.putIfAbsent(group, 0);
          if (selected.contains(id)) groups.merge(group, 1, Integer::sum);
        }
    if (selected.size() != (choices == null ? 0 : choices.size())
        || !allowed.containsAll(selected)
        || groups.values().stream().anyMatch(n -> n != 1))
      throw fail(
          422,
          "INVALID_SUBJECT_CHOICES",
          "Select exactly one alternative from every published language, religion or trade choice group.");
  }

  void enrolChoices(
      UUID school,
      UUID student,
      UUID session,
      UUID classId,
      String department,
      List<UUID> choices) {
    validateChoices(classId, department, choices);
    Set<UUID> selected = new HashSet<>(choices == null ? List.of() : choices);
    for (UUID sub : selected)
      db.update(
          "insert into student_subject_choices(school_id,student_id,session_id,subject_id) values (?,?,?,?)",
          school,
          student,
          session,
          sub);
    db.update(
        "insert into student_choice_versions(school_id,student_id,session_id) values (?,?,?) on conflict do nothing",
        school,
        student,
        session);
  }

  void validateFields(JsonNode definition, Map<String, Object> values) {
    Set<String> allowed = new HashSet<>();
    for (JsonNode f : definition.path("studentFields")) {
      String key = f.path("key").asText();
      allowed.add(key);
      Object v = values.get(key);
      if (f.path("required").asBoolean() && (v == null || v.toString().isBlank()))
        throw fail(422, "REQUIRED_METADATA", key + " is required by the current school form.");
      if (v != null
          && (!(v instanceof String) || v.toString().length() > f.path("maxLength").asInt()))
        throw fail(422, "INVALID_METADATA", key + " has an invalid value or length.");
    }
    if (!allowed.containsAll(values.keySet()))
      throw fail(
          422,
          "UNKNOWN_METADATA_FIELD",
          "The form includes fields outside the published school metadata.");
  }

  void learning(Actor a, UUID school, UUID student) {
    var c = a.school(school);
    if ("PRINCIPAL".equals(c.role()) || "SCHOOL_OFFICE".equals(c.role())) return;
    if ("PARENT".equals(c.role())) {
      a.child(student, false);
      if (db.queryForObject(
              "select count(*) from guardian_links where school_id=? and student_id=? and guardian_id=? and active and learning_access",
              Integer.class,
              school,
              student,
              c.subjectId())
          == 0) throw fail(404, "NOT_FOUND", "This child relationship is unavailable.");
      if (!c.actions().contains("child.learning.read"))
        throw fail(404, "NOT_FOUND", "This learning record is unavailable.");
      return;
    }
    if ("STUDENT".equals(c.role()) && student.equals(c.subjectId())) return;
    if ("TEACHER".equals(c.role())
        && db.queryForObject(
                "select count(*) from enrolments e join classes c on c.id=e.class_id left join teaching_assignments t on t.class_id=c.id and t.active where e.school_id=? and e.student_id=? and (c.attendance_teacher_id=? or t.teacher_id=?) and e.starts_on<=current_date and (e.ends_on is null or e.ends_on>=current_date)",
                Integer.class,
                school,
                student,
                c.subjectId(),
                c.subjectId())
            > 0) return;
    throw fail(404, "NOT_FOUND", "This learning record is unavailable.");
  }

  public Object student(Actor a, UUID school, UUID student) {
    learning(a, school, student);
    var s =
        one(
            "select s.*,o.delivered_at,o.last_error_code from students s left join enrolment_events e on e.student_id=s.id left join outbox o on o.id=e.event_id where s.school_id=? and s.id=?",
            school,
            student);
    var placements =
        db.queryForList(
            "select e.id,e.class_id as \"classId\",c.level,c.arm,e.department,e.starts_on as \"startsOn\",e.ends_on as \"endsOn\",e.session_id as \"sessionId\" from enrolments e join classes c on c.id=e.class_id where e.school_id=? and e.student_id=? order by e.starts_on desc",
            school,
            student);
    var result =
        map(
            "id",
            student,
            "version",
            s.get("version"),
            "admissionNumber",
            s.get("admission_number"),
            "fullName",
            s.get("full_name"),
            "status",
            s.get("status"),
            "enrolments",
            placements);
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(a.context().role())) {
      result.put("dateOfBirth", s.get("date_of_birth"));
      result.put("fields", json.value(s.get("metadata").toString()));
      result.put(
          "guardians",
          db.queryForList(
              "select g.id,g.full_name as \"fullName\",g.phone,g.email,l.relationship,l.active from guardian_links l join guardians g on g.id=l.guardian_id where l.school_id=? and l.student_id=?",
              school,
              student));
      result.put(
          "onboardingStatus",
          s.get("delivered_at") == null ? "ACCOUNTS_PENDING" : "ACCOUNTS_PROVISIONED");
    }
    return result;
  }

  public Object students(
      Actor a, UUID school, UUID classId, UUID cursor, int limit, String search) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    if ("TEACHER".equals(c.role())) {
      if (classId == null
          || db.queryForObject(
                  "select count(*) from classes c left join teaching_assignments t on t.class_id=c.id where c.school_id=? and c.id=? and (c.attendance_teacher_id=? or t.teacher_id=?)",
                  Integer.class,
                  school,
                  classId,
                  c.subjectId(),
                  c.subjectId())
              == 0) throw fail(404, "NOT_FOUND", "This class is unavailable.");
    }
    String q = search == null ? "" : search.strip();
    if (q.length() > 80) throw fail(422, "SEARCH_TOO_LONG", "Use at most 80 search characters.");
    var rows =
        db.queryForList(
            "select distinct s.id,s.admission_number as \"admissionNumber\",s.full_name as \"fullName\",s.status,s.version from students s left join enrolments e on e.student_id=s.id where s.school_id=? and (?::uuid is null or e.class_id=?::uuid) and (?::uuid is null or s.id>?::uuid) and (s.full_name ilike ? or s.admission_number ilike ?) order by s.id limit ?",
            school,
            classId,
            classId,
            cursor,
            cursor,
            "%" + q + "%",
            "%" + q + "%",
            limit + 1);
    return page(rows, limit, r -> r.get("id").toString());
  }

  public Object audit(Actor a, UUID school, UUID cursor, int limit) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var rows =
        db.queryForList(
            "select id,actor_id as \"actorId\",action,resource_type as \"resourceType\",resource_id as \"resourceId\",reason,created_at as \"createdAt\" from audit_entries where school_id=? and (?::uuid is null or id>?::uuid) order by id limit ?",
            school,
            cursor,
            cursor,
            limit + 1);
    return page(rows, limit, r -> r.get("id").toString());
  }

  public Object revokeGuardian(
      Actor a, UUID school, UUID student, UUID guardian, String reason, String key) {
    return command(
        a,
        school,
        "students/" + student + "/guardians/" + guardian + "/revoke",
        key,
        map("reason", reason),
        () -> {
          String why = text(reason, 5, 500, "Revocation reason");
          one(
              "select student_id from guardian_links where school_id=? and student_id=? and guardian_id=? and active for update",
              school,
              student,
              guardian);
          db.update(
              "update guardian_links set active=false,version=version+1 where school_id=? and student_id=? and guardian_id=?",
              school,
              student,
              guardian);
          UUID event =
              outbox.add(
                  "identity",
                  "GUARDIAN_LINK_REVOKED",
                  map(
                      "schoolId",
                      school,
                      "schoolName",
                      school(school).get("name"),
                      "studentId",
                      student,
                      "guardianId",
                      guardian,
                      "version",
                      one(
                              "select version from guardian_links where school_id=? and student_id=? and guardian_id=?",
                              school,
                              student,
                              guardian)
                          .get("version")));
          commands.audit(
              school,
              a.accountId(),
              "GUARDIAN_LINK_REVOKED",
              "STUDENT",
              student,
              why,
              map("guardianId", guardian));
          return map("accepted", true, "status", "ACCESS_REVOCATION_PENDING", "eventId", event);
        });
  }
}
