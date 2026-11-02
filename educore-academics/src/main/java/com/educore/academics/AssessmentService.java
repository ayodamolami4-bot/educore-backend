package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AssessmentService {
  private final SchoolService s;

  public AssessmentService(SchoolService s) {
    this.s = s;
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

  private boolean classTeacher(Actor a, Map<String, Object> c) {
    return "TEACHER".equals(a.context().role())
        && a.context().subjectId().equals(c.get("attendance_teacher_id"));
  }

  Map<String, Object> cycle(Actor a, UUID school, UUID id, boolean lock, boolean full) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    var c =
        s.one(
            "select x.*,c.attendance_teacher_id,c.level,c.arm,t.session_id,t.number,t.starts_on,t.ends_on,se.name as session_name from assessment_cycles x join classes c on c.id=x.class_id join terms t on t.id=x.term_id join academic_sessions se on se.id=t.session_id where x.school_id=? and x.id=?"
                + (lock ? " for update of x" : ""),
            school,
            id);
    if ("TEACHER".equals(a.context().role())
        && !classTeacher(a, c)
        && (full
            || s.db.queryForObject(
                    "select count(*) from assessment_sheets where cycle_id=? and teacher_id=?",
                    Integer.class,
                    id,
                    a.context().subjectId())
                == 0)) throw fail(404, "NOT_FOUND", "This assessment scope is unavailable.");
    return c;
  }

  private void editable(Map<String, Object> c, boolean window) {
    if (!"OPEN".equals(c.get("state")))
      throw fail(
          409, "MARK_ENTRY_CLOSED", "The principal must open this assessment scope before entry.");
    if (window
        && (Instant.now().isBefore(((java.sql.Timestamp) c.get("opens_at")).toInstant())
            || Instant.now().isAfter(((java.sql.Timestamp) c.get("closes_at")).toInstant())))
      throw fail(
          409,
          "OUTSIDE_ENTRY_WINDOW",
          "The mark-entry window is closed. Ask for a reasoned reopening.");
  }

  private void touch(UUID cycle) {
    s.db.update("update assessment_cycles set version=version+1 where id=?", cycle);
  }

  public record CycleInput(UUID termId, UUID classId, Instant opensAt, Instant closesAt) {}

  public Object create(Actor a, UUID school, CycleInput b, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "assessment-cycles",
        key,
        b,
        () -> {
          var term = s.one("select * from terms where school_id=? and id=?", school, b.termId());
          var c = s.one("select * from classes where school_id=? and id=?", school, b.classId());
          if (!term.get("session_id").equals(c.get("session_id"))
              || c.get("attendance_teacher_id") == null)
            throw fail(
                422,
                "ASSESSMENT_CLASS_NOT_READY",
                "Choose a class in this term's session and assign its class teacher first.");
          if (b.opensAt() == null || b.closesAt() == null || !b.opensAt().isBefore(b.closesAt()))
            throw fail(
                422, "INVALID_ENTRY_WINDOW", "Provide ordered opening and closing instants.");
          UUID id = UUID.randomUUID();
          Object policy =
              map(
                  "version",
                  1,
                  "ca1Maximum",
                  20,
                  "ca2Maximum",
                  20,
                  "examMaximum",
                  60,
                  "decimalPlaces",
                  1,
                  "gradeBands",
                  map("A", 70, "B", 60, "C", 50, "D", 45, "E", 40, "F", 0),
                  "rankingEnabled",
                  false);
          s.db.update(
              "insert into assessment_cycles(id,school_id,term_id,class_id,policy,opens_at,closes_at,created_by) values (?,?,?,?,?::jsonb,?,?,?)",
              id,
              school,
              b.termId(),
              b.classId(),
              s.json.write(policy),
              java.sql.Timestamp.from(b.opensAt()),
              java.sql.Timestamp.from(b.closesAt()),
              a.accountId());
          s.db.update(
              "insert into assessment_roster(cycle_id,student_id,enrolment_id,department) select ?,e.student_id,e.id,e.department from enrolments e join students st on st.id=e.student_id where e.school_id=? and e.class_id=? and st.status='ACTIVE' and e.starts_on<=? and (e.ends_on is null or e.ends_on>=?) and not exists(select 1 from enrolments later where later.student_id=e.student_id and later.session_id=e.session_id and later.starts_on>e.starts_on and later.starts_on<=?)",
              id,
              school,
              b.classId(),
              term.get("ends_on"),
              term.get("starts_on"),
              term.get("ends_on"));
          s.db.update(
              "insert into assessment_sheets(cycle_id,assignment_id,subject_id,teacher_id,department) select ?,id,subject_id,teacher_id,department from teaching_assignments where school_id=? and class_id=? and active",
              id,
              school,
              b.classId());
          s.db.update(
              "insert into assessment_marks(cycle_id,assignment_id,student_id) select r.cycle_id,sh.assignment_id,r.student_id from assessment_roster r join assessment_sheets sh on sh.cycle_id=r.cycle_id join teaching_assignments ta on ta.id=sh.assignment_id where r.cycle_id=? and (sh.department is null or sh.department=r.department) and (ta.required or not exists(select 1 from student_subject_choices ch where ch.student_id=r.student_id and ch.session_id=?) or exists(select 1 from student_subject_choices ch where ch.student_id=r.student_id and ch.session_id=? and ch.subject_id=sh.subject_id))",
              id,
              term.get("session_id"),
              term.get("session_id"));
          if (s.db.queryForObject(
                      "select count(*) from assessment_roster where cycle_id=?", Integer.class, id)
                  == 0
              || s.db.queryForObject(
                      "select count(*) from assessment_sheets where cycle_id=?", Integer.class, id)
                  == 0)
            throw fail(
                422,
                "EMPTY_ASSESSMENT_SCOPE",
                "The class needs enrolled students and assigned subjects.");
          s.commands.audit(
              school,
              a.accountId(),
              "ASSESSMENT_PREPARED",
              "ASSESSMENT_CYCLE",
              id,
              null,
              map("policyVersion", 1));
          return map("id", id, "version", 1, "state", "DRAFT", "policy", policy);
        });
  }

  public Object list(Actor a, UUID school, UUID term, UUID classId) {
    var context = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    UUID teacher = "TEACHER".equals(context.role()) ? context.subjectId() : null;
    return map(
        "items",
        s.db.queryForList(
            "select distinct x.id,x.term_id as \"termId\",x.class_id as \"classId\",x.state,x.version,x.revision,x.opens_at as \"opensAt\",x.closes_at as \"closesAt\" from assessment_cycles x join classes c on c.id=x.class_id left join assessment_sheets sh on sh.cycle_id=x.id where x.school_id=? and (?::uuid is null or x.term_id=?::uuid) and (?::uuid is null or x.class_id=?::uuid) and (?::uuid is null or c.attendance_teacher_id=?::uuid or sh.teacher_id=?::uuid) order by x.id",
            school,
            term,
            term,
            classId,
            classId,
            teacher,
            teacher,
            teacher),
        "hasMore",
        false);
  }

  public record Window(Instant opensAt, Instant closesAt, String reason) {}

  public Object window(Actor a, UUID school, UUID id, Window b, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "assessment-cycles/" + id + "/open",
        key,
        map("input", b, "version", version),
        () -> {
          var c = cycle(a, school, id, true, true);
          expectVersion(version, ((Number) c.get("version")).longValue());
          if ("PUBLISHED".equals(c.get("state")))
            throw fail(
                409,
                "REOPEN_REQUIRED",
                "Use a reasoned report correction before reopening published results.");
          if (b.opensAt() == null || b.closesAt() == null || !b.opensAt().isBefore(b.closesAt()))
            throw fail(422, "INVALID_ENTRY_WINDOW", "Provide ordered mark-entry instants.");
          s.db.update(
              "update assessment_cycles set state='OPEN',opens_at=?,closes_at=?,version=version+1 where id=?",
              java.sql.Timestamp.from(b.opensAt()),
              java.sql.Timestamp.from(b.closesAt()),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "ASSESSMENT_OPENED",
              "ASSESSMENT_CYCLE",
              id,
              text(b.reason(), 5, 500, "Opening reason"),
              Map.of());
          return map("id", id, "version", version + 1, "state", "OPEN");
        });
  }

  public Object sheet(Actor a, UUID school, UUID cycle, UUID assignment) {
    var c = cycle(a, school, cycle, false, false);
    var sh =
        s.one(
            "select * from assessment_sheets where cycle_id=? and assignment_id=?",
            cycle,
            assignment);
    if ("TEACHER".equals(a.context().role())
        && !classTeacher(a, c)
        && !a.context().subjectId().equals(sh.get("teacher_id")))
      throw fail(404, "NOT_FOUND", "This subject sheet is unavailable.");
    return map(
        "cycleId",
        cycle,
        "assignmentId",
        assignment,
        "state",
        sh.get("state"),
        "version",
        sh.get("version"),
        "teacherId",
        sh.get("teacher_id"),
        "returnReason",
        sh.get("return_reason"),
        "cycleState",
        c.get("state"),
        "opensAt",
        c.get("opens_at"),
        "closesAt",
        c.get("closes_at"),
        "policy",
        s.json.value(c.get("policy").toString()),
        "rows",
        s.db.queryForList(
            "select m.student_id as \"studentId\",st.full_name as \"fullName\",st.admission_number as \"admissionNumber\",m.ca1,m.ca2,m.exam,m.exam_state as \"examState\",m.note from assessment_marks m join students st on st.id=m.student_id where m.cycle_id=? and m.assignment_id=? order by st.full_name,st.id",
            cycle,
            assignment));
  }

  public record Score(
      UUID studentId,
      BigDecimal ca1,
      BigDecimal ca2,
      BigDecimal exam,
      String examState,
      String note) {}

  public record Marks(List<Score> rows) {}

  private void component(BigDecimal n, int maximum) {
    if (n != null
        && (n.signum() < 0 || n.compareTo(BigDecimal.valueOf(maximum)) > 0 || n.scale() > 1))
      throw fail(
          422,
          "INVALID_SCORE",
          "Marks must fit their component maximum and use at most one decimal place.");
  }

  public Object marks(
      Actor a, UUID school, UUID id, UUID assignment, Marks b, long version, String key) {
    a.school(school, "TEACHER");
    cycle(a, school, id, false, false);
    if (!a.context()
        .subjectId()
        .equals(
            s.one(
                    "select teacher_id from assessment_sheets where cycle_id=? and assignment_id=?",
                    id,
                    assignment)
                .get("teacher_id")))
      throw fail(404, "NOT_FOUND", "This subject sheet is unavailable.");
    return command(
        a,
        school,
        "assessment-cycles/" + id + "/sheets/" + assignment + "/marks",
        key,
        map("input", b, "version", version),
        () -> {
          var c = cycle(a, school, id, true, false);
          editable(c, true);
          var sh =
              s.one(
                  "select * from assessment_sheets where cycle_id=? and assignment_id=? for update",
                  id,
                  assignment);
          if (!a.context().subjectId().equals(sh.get("teacher_id")))
            throw fail(404, "NOT_FOUND", "You can enter only your assigned subject marks.");
          expectVersion(version, ((Number) sh.get("version")).longValue());
          if ("SUBMITTED".equals(sh.get("state")))
            throw fail(
                409,
                "SHEET_SUBMITTED",
                "Request a reasoned return before changing submitted marks.");
          if (b.rows() == null || b.rows().isEmpty() || b.rows().size() > 200)
            throw fail(422, "INVALID_MARK_ROWS", "Provide one to 200 distinct student rows.");
          Set<UUID> seen = new HashSet<>();
          for (var row : b.rows()) {
            if (row.studentId() == null || !seen.add(row.studentId()))
              throw fail(422, "DUPLICATE_STUDENT", "Use each rostered student once.");
            s.one(
                "select student_id from assessment_marks where cycle_id=? and assignment_id=? and student_id=?",
                id,
                assignment,
                row.studentId());
            component(row.ca1(), 20);
            component(row.ca2(), 20);
            component(row.exam(), 60);
            String state = row.examState() == null ? "COMPLETE" : row.examState();
            if (!Set.of("COMPLETE", "MAKEUP_PENDING", "ABSENT_ZERO").contains(state))
              throw fail(
                  422, "INVALID_EXAM_STATE", "Choose COMPLETE, MAKEUP_PENDING or ABSENT_ZERO.");
            if ("ABSENT_ZERO".equals(state)
                && (row.exam() == null
                    || row.exam().signum() != 0
                    || row.note() == null
                    || row.note().strip().length() < 5))
              throw fail(
                  422,
                  "ABSENCE_EVIDENCE_REQUIRED",
                  "An approved absent zero needs an explicit zero and a meaningful note.");
            if (row.note() != null) text(row.note(), 1, 500, "Mark note");
            s.db.update(
                "update assessment_marks set ca1=?,ca2=?,exam=?,exam_state=?,note=?,marked_by=?,updated_at=now() where cycle_id=? and assignment_id=? and student_id=?",
                row.ca1(),
                row.ca2(),
                row.exam(),
                state,
                row.note(),
                a.accountId(),
                id,
                assignment,
                row.studentId());
          }
          s.db.update(
              "update assessment_sheets set state='DRAFT',version=version+1 where cycle_id=? and assignment_id=?",
              id,
              assignment);
          touch(id);
          s.commands.audit(
              school,
              a.accountId(),
              "MARK_DRAFT_SAVED",
              "ASSESSMENT_CYCLE",
              id,
              null,
              map("assignmentId", assignment, "rows", seen.size()));
          return sheet(a, school, id, assignment);
        });
  }

  public Object submit(Actor a, UUID school, UUID id, UUID assignment, long version, String key) {
    a.school(school, "TEACHER");
    cycle(a, school, id, false, false);
    if (!a.context()
        .subjectId()
        .equals(
            s.one(
                    "select teacher_id from assessment_sheets where cycle_id=? and assignment_id=?",
                    id,
                    assignment)
                .get("teacher_id")))
      throw fail(404, "NOT_FOUND", "This subject sheet is unavailable.");
    return command(
        a,
        school,
        "assessment-cycles/" + id + "/sheets/" + assignment + "/submit",
        key,
        map("version", version),
        () -> {
          var c = cycle(a, school, id, true, false);
          editable(c, true);
          var sh =
              s.one(
                  "select * from assessment_sheets where cycle_id=? and assignment_id=? for update",
                  id,
                  assignment);
          if (!a.context().subjectId().equals(sh.get("teacher_id")))
            throw fail(
                404, "NOT_FOUND", "Only the assigned subject teacher can submit this sheet.");
          expectVersion(version, ((Number) sh.get("version")).longValue());
          if ("SUBMITTED".equals(sh.get("state")))
            throw fail(409, "ALREADY_SUBMITTED", "The subject sheet is already submitted.");
          var rows =
              s.db.queryForList(
                  "select ca1,ca2,exam,exam_state from assessment_marks where cycle_id=? and assignment_id=?",
                  id,
                  assignment);
          if (rows.isEmpty())
            throw fail(422, "EMPTY_SUBJECT_ROSTER", "This subject has no eligible students.");
          for (var row : rows) {
            if ("MAKEUP_PENDING".equals(row.get("exam_state")))
              throw fail(
                  422,
                  "UNRESOLVED_MAKEUP",
                  "Resolve pending make-up assessments before submission.");
            SchoolRules.total(
                (BigDecimal) row.get("ca1"),
                (BigDecimal) row.get("ca2"),
                (BigDecimal) row.get("exam"));
          }
          s.db.update(
              "update assessment_sheets set state='SUBMITTED',submitted_by=?,submitted_at=now(),return_reason=null,version=version+1 where cycle_id=? and assignment_id=?",
              a.accountId(),
              id,
              assignment);
          touch(id);
          s.commands.audit(
              school,
              a.accountId(),
              "SUBJECT_MARKS_SUBMITTED",
              "ASSESSMENT_CYCLE",
              id,
              null,
              map("assignmentId", assignment));
          return sheet(a, school, id, assignment);
        });
  }

  public record Reason(String reason) {}

  public Object returnSheet(
      Actor a, UUID school, UUID id, UUID assignment, Reason b, long version, String key) {
    var permitted = cycle(a, school, id, false, true);
    return command(
        a,
        school,
        "assessment-cycles/" + id + "/sheets/" + assignment + "/return",
        key,
        map("input", b, "version", version),
        () -> {
          var c = cycle(a, school, id, true, true);
          if ("PUBLISHED".equals(c.get("state")))
            throw fail(
                409,
                "REOPEN_REQUIRED",
                "Reopen the published report revision before returning marks.");
          var sh =
              s.one(
                  "select version from assessment_sheets where cycle_id=? and assignment_id=? for update",
                  id,
                  assignment);
          expectVersion(version, ((Number) sh.get("version")).longValue());
          String why = text(b.reason(), 5, 500, "Return reason");
          s.db.update(
              "update assessment_sheets set state='RETURNED',return_reason=?,version=version+1 where cycle_id=? and assignment_id=?",
              why,
              id,
              assignment);
          touch(id);
          s.commands.audit(
              school,
              a.accountId(),
              "MARKS_RETURNED",
              "ASSESSMENT_CYCLE",
              id,
              why,
              map("assignmentId", assignment));
          return sheet(a, school, id, assignment);
        });
  }

  public record Remark(
      String remark,
      int conduct,
      int punctuality,
      int cooperation,
      int handwriting,
      int practicalSkills) {}

  public Object remark(
      Actor a, UUID school, UUID id, UUID student, Remark b, long expected, String key) {
    var before = cycle(a, school, id, false, true);
    if (!classTeacher(a, before))
      throw fail(
          403,
          "CLASS_TEACHER_REQUIRED",
          "The assigned class teacher supplies report remarks and ratings.");
    return command(
        a,
        school,
        "assessment-cycles/" + id + "/remarks/" + student,
        key,
        map("input", b, "version", expected),
        () -> {
          var c = cycle(a, school, id, true, true);
          editable(c, false);
          s.one(
              "select student_id from assessment_roster where cycle_id=? and student_id=?",
              id,
              student);
          var old =
              s.db.queryForList(
                  "select version from class_remarks where cycle_id=? and student_id=?",
                  id,
                  student);
          expectVersion(
              expected, old.isEmpty() ? 1 : ((Number) old.get(0).get("version")).longValue());
          for (int value :
              new int[] {
                b.conduct(), b.punctuality(), b.cooperation(), b.handwriting(), b.practicalSkills()
              })
            if (value < 1 || value > 5)
              throw fail(
                  422, "INVALID_RATING", "Each of the five ratings must be from one to five.");
          String remark = text(b.remark(), 5, 1500, "Class teacher remark");
          s.db.update(
              "insert into class_remarks(cycle_id,student_id,remark,conduct,punctuality,cooperation,handwriting,practical_skills,author_id,version) values (?,?,?,?,?,?,?,?,?,2) on conflict(cycle_id,student_id) do update set remark=excluded.remark,conduct=excluded.conduct,punctuality=excluded.punctuality,cooperation=excluded.cooperation,handwriting=excluded.handwriting,practical_skills=excluded.practical_skills,author_id=excluded.author_id,version=class_remarks.version+1",
              id,
              student,
              remark,
              b.conduct(),
              b.punctuality(),
              b.cooperation(),
              b.handwriting(),
              b.practicalSkills(),
              a.accountId());
          touch(id);
          s.commands.audit(
              school,
              a.accountId(),
              "CLASS_TEACHER_REMARK_SAVED",
              "ASSESSMENT_CYCLE",
              id,
              null,
              map("studentId", student));
          return map("studentId", student, "version", expected + 1, "remark", remark);
        });
  }

  public Object review(Actor a, UUID school, UUID id) {
    var c = cycle(a, school, id, false, true);
    var sheets =
        s.db.queryForList(
            "select sh.assignment_id as \"assignmentId\",sh.subject_id as \"subjectId\",su.name as \"subjectName\",sh.state,sh.version,sh.teacher_id as \"teacherId\" from assessment_sheets sh join subjects su on su.id=sh.subject_id where sh.cycle_id=? order by su.name",
            id);
    var students =
        s.db.queryForList(
            "select r.student_id as \"studentId\",st.full_name as \"fullName\",st.admission_number as \"admissionNumber\",r.department,co.version as \"remarkVersion\",co.remark,co.conduct,co.punctuality,co.cooperation,co.handwriting,co.practical_skills as \"practicalSkills\" from assessment_roster r join students st on st.id=r.student_id left join class_remarks co on co.cycle_id=r.cycle_id and co.student_id=r.student_id where r.cycle_id=? order by st.id",
            id);
    int missing =
        s.db.queryForObject(
            "select count(*) from assessment_marks where cycle_id=? and (ca1 is null or ca2 is null or exam is null or exam_state='MAKEUP_PENDING')",
            Integer.class,
            id);
    boolean ready =
        !students.isEmpty()
            && !sheets.isEmpty()
            && missing == 0
            && sheets.stream().allMatch(x -> "SUBMITTED".equals(x.get("state")))
            && students.stream().allMatch(x -> x.get("remark") != null);
    for (var student : students) {
      var marks = studentMarks(id, (UUID) student.get("studentId"));
      student.put("subjects", marks);
      if (marks.isEmpty()) ready = false;
    }
    return map(
        "id",
        id,
        "version",
        c.get("version"),
        "state",
        c.get("state"),
        "revision",
        c.get("revision"),
        "policy",
        s.json.value(c.get("policy").toString()),
        "sheets",
        sheets,
        "students",
        students,
        "missingMarkRows",
        missing,
        "readyToPublish",
        ready);
  }

  List<Map<String, Object>> studentMarks(UUID cycle, UUID student) {
    var rows =
        s.db.queryForList(
            "select sh.assignment_id as \"assignmentId\",su.id as \"subjectId\",su.code,su.name,m.ca1,m.ca2,m.exam,m.exam_state as \"examState\",m.note from assessment_marks m join assessment_sheets sh on sh.cycle_id=m.cycle_id and sh.assignment_id=m.assignment_id join subjects su on su.id=sh.subject_id where m.cycle_id=? and m.student_id=? order by su.code",
            cycle,
            student);
    for (var row : rows)
      if (row.get("ca1") != null
          && row.get("ca2") != null
          && row.get("exam") != null
          && !"MAKEUP_PENDING".equals(row.get("examState"))) {
        var total =
            SchoolRules.total(
                (BigDecimal) row.get("ca1"),
                (BigDecimal) row.get("ca2"),
                (BigDecimal) row.get("exam"));
        row.put("total", total);
        row.put("grade", SchoolRules.grade(total));
      }
    return rows;
  }

  public record Publication(String principalRemark, LocalDate nextResumptionOn) {}

  public Object publish(Actor a, UUID school, UUID id, Publication b, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "assessment-cycles/" + id + "/publish",
        key,
        map("input", b, "version", version),
        () -> {
          var c = cycle(a, school, id, true, true);
          expectVersion(version, ((Number) c.get("version")).longValue());
          if (!Set.of("OPEN", "REVIEW").contains(c.get("state")))
            throw fail(
                409, "INVALID_ASSESSMENT_STATE", "Open and review the cycle before publication.");
          @SuppressWarnings("unchecked")
          var review = (Map<String, Object>) review(a, school, id);
          if (!Boolean.TRUE.equals(review.get("readyToPublish")))
            throw fail(
                422,
                "RESULTS_INCOMPLETE",
                "Every required subject sheet, mark, class remark and rating must be complete.");
          String principal = text(b.principalRemark(), 5, 1500, "Principal remark");
          if (b.nextResumptionOn() == null
              || !b.nextResumptionOn().isAfter(((java.sql.Date) c.get("ends_on")).toLocalDate()))
            throw fail(
                422,
                "INVALID_RESUMPTION_DATE",
                "Provide the next resumption date after this term.");
          @SuppressWarnings("unchecked")
          var students = (List<Map<String, Object>>) review.get("students");
          List<Object> published = new ArrayList<>();
          for (var student : students) {
            UUID studentId = (UUID) student.get("studentId");
            s.one("select id from students where id=? for update", studentId);
            int revision =
                s.db.queryForObject(
                    "select coalesce(max(revision),0)+1 from report_versions where school_id=? and term_id=? and student_id=?",
                    Integer.class,
                    school,
                    c.get("term_id"),
                    studentId);
            @SuppressWarnings("unchecked")
            var subjects = (List<Map<String, Object>>) student.get("subjects");
            BigDecimal total =
                subjects.stream()
                    .map(x -> (BigDecimal) x.get("total"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            UUID report = UUID.randomUUID();
            Object snapshot =
                map(
                    "id",
                    report,
                    "schoolId",
                    school,
                    "schoolName",
                    s.school(school).get("name"),
                    "studentId",
                    studentId,
                    "studentName",
                    student.get("fullName"),
                    "admissionNumber",
                    student.get("admissionNumber"),
                    "classId",
                    c.get("class_id"),
                    "class",
                    c.get("level") + " " + c.get("arm"),
                    "department",
                    student.get("department"),
                    "termId",
                    c.get("term_id"),
                    "termNumber",
                    c.get("number"),
                    "sessionId",
                    c.get("session_id"),
                    "sessionName",
                    c.get("session_name"),
                    "policy",
                    review.get("policy"),
                    "subjects",
                    subjects,
                    "average",
                    total.divide(BigDecimal.valueOf(subjects.size()), 2, RoundingMode.HALF_UP),
                    "attendance",
                    attendanceSnapshot(
                        school,
                        studentId,
                        (java.sql.Date) c.get("starts_on"),
                        (java.sql.Date) c.get("ends_on")),
                    "classTeacherRemark",
                    student.get("remark"),
                    "ratings",
                    map(
                        "conduct",
                        student.get("conduct"),
                        "punctuality",
                        student.get("punctuality"),
                        "cooperation",
                        student.get("cooperation"),
                        "handwriting",
                        student.get("handwriting"),
                        "practicalSkills",
                        student.get("practicalSkills")),
                    "principalRemark",
                    principal,
                    "nextResumptionOn",
                    b.nextResumptionOn(),
                    "revision",
                    revision,
                    "publishedAt",
                    Instant.now());
            s.db.update(
                "insert into report_versions(id,school_id,cycle_id,term_id,student_id,revision,snapshot,published_by) values (?,?,?,?,?,?,?::jsonb,?)",
                report,
                school,
                id,
                c.get("term_id"),
                studentId,
                revision,
                s.json.write(snapshot),
                a.accountId());
            s.db.update(
                "update promotion_outcomes set requires_review=true where school_id=? and source_session_id=? and student_id=?",
                school,
                c.get("session_id"),
                studentId);
            s.outbox.add(
                "communication",
                "CHILD_NOTIFICATION",
                map(
                    "schoolId",
                    school,
                    "childId",
                    studentId,
                    "category",
                    "LEARNING",
                    "title",
                    "School report published",
                    "safePreview",
                    "Your school published a report. Open the app to view it securely.",
                    "target",
                    map(
                        "screenId",
                        "PA01",
                        "schoolId",
                        school,
                        "childId",
                        studentId,
                        "resourceId",
                        report)));
            published.add(map("id", report, "studentId", studentId, "revision", revision));
          }
          s.db.update(
              "update assessment_cycles set state='PUBLISHED',revision=revision+1,published_at=now(),version=version+1 where id=?",
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "CLASS_REPORTS_PUBLISHED",
              "ASSESSMENT_CYCLE",
              id,
              null,
              map(
                  "reports",
                  published.size(),
                  "revision",
                  ((Number) c.get("revision")).intValue() + 1));
          return map("id", id, "version", version + 1, "state", "PUBLISHED", "reports", published);
        });
  }

  Object attendanceSnapshot(UUID school, UUID student, java.sql.Date start, java.sql.Date end) {
    var counts =
        s.one(
            "select count(*) as submitted,count(*) filter(where ar.status='PRESENT') as present,count(*) filter(where ar.status='LATE') as late,count(*) filter(where ar.status='ABSENT') as absent,count(*) filter(where ar.status='EXCUSED') as excused from attendance_rows ar join attendance_registers r on r.id=ar.register_id where r.school_id=? and ar.student_id=? and r.state='SUBMITTED' and r.on_date between ? and least(?::date,current_date)",
            school,
            student,
            start,
            end);
    int expected =
        s.db.queryForObject(
            "select count(*) from generate_series(?::date,least(?::date,current_date),interval '1 day') d where coalesce((select teaching_day from calendar_exceptions where school_id=? and on_date=d::date),extract(isodow from d)<=5) and exists(select 1 from enrolments e where e.school_id=? and e.student_id=? and e.starts_on<=d::date and (e.ends_on is null or e.ends_on>=d::date))",
            Integer.class,
            start,
            end,
            school,
            school,
            student);
    long submitted = ((Number) counts.get("submitted")).longValue();
    boolean complete = submitted == expected;
    var result =
        map(
            "expectedRegisters",
            expected,
            "submittedRegisters",
            submitted,
            "coverageComplete",
            complete,
            "present",
            counts.get("present"),
            "late",
            counts.get("late"),
            "absent",
            counts.get("absent"),
            "excused",
            counts.get("excused"));
    if (complete && expected > 0)
      result.put(
          "presentPercent",
          BigDecimal.valueOf(
                  ((Number) counts.get("present")).longValue()
                      + ((Number) counts.get("late")).longValue())
              .multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(expected), 2, RoundingMode.HALF_UP));
    return result;
  }

  public record Reopen(UUID assignmentId, String reason, Instant closesAt) {}

  public Object reopen(Actor a, UUID school, UUID id, Reopen b, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "assessment-cycles/" + id + "/reopen",
        key,
        map("input", b, "version", version),
        () -> {
          var c = cycle(a, school, id, true, true);
          expectVersion(version, ((Number) c.get("version")).longValue());
          if (!"PUBLISHED".equals(c.get("state")))
            throw fail(409, "NOT_PUBLISHED", "Only published reports need a correction revision.");
          String reason = text(b.reason(), 5, 500, "Correction reason");
          if (b.closesAt() == null || !b.closesAt().isAfter(Instant.now()))
            throw fail(422, "INVALID_ENTRY_WINDOW", "Set a future correction deadline.");
          if (b.assignmentId() != null) {
            s.one(
                "select assignment_id from assessment_sheets where cycle_id=? and assignment_id=?",
                id,
                b.assignmentId());
            s.db.update(
                "update assessment_sheets set state='RETURNED',return_reason=?,version=version+1 where cycle_id=? and assignment_id=?",
                reason,
                id,
                b.assignmentId());
          }
          s.db.update(
              "update assessment_cycles set state='OPEN',opens_at=now()-interval '1 second',closes_at=?,version=version+1 where id=?",
              java.sql.Timestamp.from(b.closesAt()),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "PUBLISHED_REPORT_CORRECTION_OPENED",
              "ASSESSMENT_CYCLE",
              id,
              reason,
              map("assignmentId", b.assignmentId()));
          return map(
              "id",
              id,
              "version",
              version + 1,
              "state",
              "OPEN",
              "previousPublishedRevision",
              c.get("revision"));
        });
  }

  public Object reports(Actor a, UUID school, UUID student) {
    a.school(school);
    if ("TEACHER".equals(a.context().role())) {
      a.school(school, "TEACHER");
      if (s.db.queryForObject(
              "select count(*) from assessment_roster r join assessment_cycles x on x.id=r.cycle_id join classes c on c.id=x.class_id where x.school_id=? and r.student_id=? and c.attendance_teacher_id=?",
              Integer.class,
              school,
              student,
              a.context().subjectId())
          == 0) throw fail(404, "NOT_FOUND", "This report scope is unavailable.");
    } else s.learning(a, school, student);
    return map(
        "items",
        s.db.queryForList(
            "select id,cycle_id as \"cycleId\",term_id as \"termId\",revision,published_at as \"publishedAt\" from report_versions where school_id=? and student_id=? order by published_at desc,id",
            school,
            student),
        "hasMore",
        false);
  }

  public Object report(Actor a, UUID school, UUID report) {
    var row =
        s.one(
            "select student_id,snapshot from report_versions where school_id=? and id=?",
            school,
            report);
    reports(a, school, (UUID) row.get("student_id"));
    return s.json.value(row.get("snapshot").toString());
  }

  public byte[] pdf(Actor a, UUID school, UUID id) {
    var json = s.json.tree(s.json.write(report(a, school, id)));
    List<String> lines = new ArrayList<>();
    lines.add(json.path("schoolName").asText());
    lines.add(
        "Student: "
            + json.path("studentName").asText()
            + " | Admission: "
            + json.path("admissionNumber").asText());
    lines.add(
        "Class: "
            + json.path("class").asText()
            + " | Session: "
            + json.path("sessionName").asText()
            + " | Term: "
            + json.path("termNumber").asText());
    lines.add("Published revision: " + json.path("revision").asText() + " | Policy: 20 / 20 / 60");
    lines.add("");
    for (var subject : json.path("subjects"))
      lines.add(
          subject.path("name").asText()
              + ": CA1 "
              + subject.path("ca1").asText()
              + "/20; CA2 "
              + subject.path("ca2").asText()
              + "/20; Exam "
              + subject.path("exam").asText()
              + "/60; Total "
              + subject.path("total").asText()
              + "/100; Grade "
              + subject.path("grade").asText());
    lines.add("Average: " + json.path("average").asText());
    var attendance = json.path("attendance");
    lines.add(
        "Attendance: present "
            + attendance.path("present").asText()
            + ", late "
            + attendance.path("late").asText()
            + ", absent "
            + attendance.path("absent").asText()
            + ", excused "
            + attendance.path("excused").asText());
    lines.add(
        "Register coverage: "
            + attendance.path("submittedRegisters").asText()
            + " of "
            + attendance.path("expectedRegisters").asText()
            + "; "
            + (attendance.path("coverageComplete").asBoolean()
                ? "complete"
                : "incomplete — no full-term percentage stated"));
    lines.add("Class teacher: " + json.path("classTeacherRemark").asText());
    lines.add("Ratings (1 needs improvement; 5 excellent): " + json.path("ratings").toString());
    lines.add("Principal: " + json.path("principalRemark").asText());
    lines.add("Next resumption: " + json.path("nextResumptionOn").asText());
    return Documents.pdf("Student term report", lines);
  }

  public byte[] broadsheet(Actor a, UUID school, UUID id) {
    @SuppressWarnings("unchecked")
    var review = (Map<String, Object>) review(a, school, id);
    @SuppressWarnings("unchecked")
    var students = (List<Map<String, Object>>) review.get("students");
    List<List<?>> rows = new ArrayList<>();
    for (var student : students) {
      @SuppressWarnings("unchecked")
      var subjects = (List<Map<String, Object>>) student.get("subjects");
      for (var sub : subjects)
        rows.add(
            Arrays.asList(
                student.get("admissionNumber"),
                student.get("fullName"),
                sub.get("code"),
                sub.get("ca1"),
                sub.get("ca2"),
                sub.get("exam"),
                sub.get("total"),
                sub.get("grade"),
                sub.get("examState")));
    }
    return Documents.csv(
        List.of(
            "Admission number",
            "Student",
            "Subject",
            "CA1",
            "CA2",
            "Exam",
            "Total",
            "Grade",
            "Exam state"),
        rows);
  }
}
