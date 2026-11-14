package com.educore.academics;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalAcademicController {
  private final SchoolService s;

  public InternalAcademicController(SchoolService s) {
    this.s = s;
  }

  public record FinanceContext(UUID schoolId, Actor actor, UUID studentId) {}

  @PostMapping("/internal/finance-context")
  Object financeContext(@RequestBody FinanceContext b) {
    Actor.internalOnly("finance");
    var a = b.actor();
    var c = a.school(b.schoolId(), "PRINCIPAL", "ACCOUNTS", "PARENT");
    var student =
        s.one(
            "select full_name,admission_number from students where school_id=? and id=?",
            b.schoolId(),
            b.studentId());
    String payerName;
    if ("PARENT".equals(c.role())) {
      a.child(b.studentId(), true);
      s.one(
          "select student_id from guardian_links where school_id=? and student_id=? and guardian_id=? and active and payer_access",
          b.schoolId(),
          b.studentId(),
          c.subjectId());
      payerName =
          (String)
              s.one(
                      "select full_name from guardians where school_id=? and id=?",
                      b.schoolId(),
                      c.subjectId())
                  .get("full_name");
    } else {
      payerName =
          (String)
              s.one(
                      "select full_name from staff where school_id=? and id=? and active and staff_role=?",
                      b.schoolId(),
                      c.subjectId(),
                      c.role())
                  .get("full_name");
    }
    return Api.map(
        "studentName",
        student.get("full_name"),
        "admissionNumber",
        student.get("admission_number"),
        "payer",
        Api.map("accountId", a.accountId(), "role", c.role(), "displayName", payerName),
        "source",
        "CHECKOUT_INITIALIZATION");
  }

  public record CommunicationContext(UUID schoolId, Actor actor, UUID studentId, String category) {}

  @PostMapping("/internal/communication-context")
  Object communicationContext(@RequestBody CommunicationContext b) {
    Actor.internalOnly("communication");
    var a = b.actor();
    var c = a.school(b.schoolId());
    UUID student = b.studentId();
    String role = c.role();
    if (student == null && Set.of("PARENT", "STUDENT").contains(role))
      student = "PARENT".equals(role) ? c.childId() : c.subjectId();
    var classes = new ArrayList<Object>();
    var result = Api.map("role", role, "classIds", classes);
    if (student != null) {
      if ("PARENT".equals(role)) {
        a.child(student, "FEES".equals(b.category()));
        if (s.db.queryForObject(
                "select count(*) from guardian_links where school_id=? and student_id=? and guardian_id=? and active and (case when ?='FEES' then payer_access when ? in ('GENERAL','STAFF_CONDUCT','SCHOOL') then (learning_access or payer_access) else learning_access end)",
                Integer.class,
                b.schoolId(),
                student,
                c.subjectId(),
                Objects.toString(b.category(), "LEARNING"),
                Objects.toString(b.category(), "LEARNING"))
            == 0)
          throw Api.fail(404, "NOT_FOUND", "The current child relationship is unavailable.");
      } else if (!Set.of("PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS").contains(role))
        s.learning(a, b.schoolId(), student);
      s.one("select id from students where school_id=? and id=?", b.schoolId(), student);
      var rows =
          s.db.queryForList(
              "select e.class_id,e.department,cl.attendance_teacher_id from enrolments e join classes cl on cl.id=e.class_id where e.school_id=? and e.student_id=? and e.starts_on<=current_date and (e.ends_on is null or e.ends_on>=current_date) order by e.starts_on desc limit 1",
              b.schoolId(),
              student);
      result.put("studentId", student);
      if (!rows.isEmpty()) {
        var e = rows.get(0);
        classes.add(e.get("class_id"));
        result.putAll(
            Api.map(
                "classId",
                e.get("class_id"),
                "department",
                e.get("department"),
                "classTeacherId",
                e.get("attendance_teacher_id")));
      }
      result.put(
          "guardians",
          s.db.queryForList(
              "select guardian_id as id,learning_access as \"learningAccess\",payer_access as \"payerAccess\",primary_contact as \"primaryContact\" from guardian_links where school_id=? and student_id=? and active order by primary_contact desc,guardian_id",
              b.schoolId(),
              student));
    } else if ("TEACHER".equals(role))
      classes.addAll(
          s
              .db
              .queryForList(
                  "select distinct cl.id from classes cl join academic_sessions y on y.id=cl.session_id where cl.school_id=? and current_date between y.starts_on and y.ends_on and (cl.attendance_teacher_id=? or exists(select 1 from teaching_assignments t where t.class_id=cl.id and t.teacher_id=? and t.active))",
                  b.schoolId(),
                  c.subjectId(),
                  c.subjectId())
              .stream()
              .map(r -> r.get("id"))
              .toList());
    return result;
  }

  public record NoticeAudience(UUID schoolId, UUID classId, String department) {}

  @PostMapping("/internal/notice-subjects")
  Object noticeSubjects(@RequestBody NoticeAudience b) {
    Actor.internalOnly("communication");
    var chosen =
        s.one("select id,level from classes where school_id=? and id=?", b.schoolId(), b.classId());
    if (b.department() != null)
      SchoolRules.department(chosen.get("level").toString(), b.department());
    return Api.map(
        "studentIds",
        s
            .db
            .queryForList(
                "select distinct student_id as id from enrolments where school_id=? and class_id=? and starts_on<=current_date and (ends_on is null or ends_on>=current_date) and (?::text is null or department=?::text)",
                b.schoolId(),
                b.classId(),
                b.department(),
                b.department())
            .stream()
            .map(r -> r.get("id"))
            .toList(),
        "teacherIds",
        s
            .db
            .queryForList(
                "select distinct st.id from staff st where st.school_id=? and st.active and (st.id=(select attendance_teacher_id from classes where id=?) or exists(select 1 from teaching_assignments t where t.class_id=? and t.teacher_id=st.id and t.active and (?::text is null or t.department is null or t.department=?::text)))",
                b.schoolId(),
                b.classId(),
                b.classId(),
                b.department(),
                b.department())
            .stream()
            .map(r -> r.get("id"))
            .toList());
  }

  public record StaffContext(UUID schoolId, UUID staffId, String preferredRole) {}

  @PostMapping("/internal/staff-context")
  Object staffContext(@RequestBody StaffContext b) {
    Actor.internalOnly("communication");
    if (b.staffId() != null)
      return s.one(
          "select id,staff_role as role,full_name as \"fullName\" from staff where school_id=? and id=? and active",
          b.schoolId(),
          b.staffId());
    if (b.preferredRole() == null
        || !Set.of("PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS").contains(b.preferredRole()))
      throw Api.fail(422, "INVALID_STAFF_PRESET", "Choose a school staff preset.");
    return s.one(
        "select id,staff_role as role,full_name as "fullName" from staff where school_id=? and active and staff_role in (?,'PRINCIPAL') order by case when staff_role=? then 0 else 1 end,id limit 1",
        b.schoolId(),
        b.preferredRole(),
        b.preferredRole());
  }

  public record Roster(UUID schoolId, UUID termId, List<UUID> classIds) {}

  public record StudentReferences(UUID schoolId, UUID termId, List<String> admissionNumbers) {}

  @PostMapping("/internal/student-references")
  Object references(@RequestBody StudentReferences b) {
    Actor.internalOnly("finance");
    s.one("select id from terms where school_id=? and id=?", b.schoolId(), b.termId());
    if (b.admissionNumbers() == null
        || b.admissionNumbers().isEmpty()
        || b.admissionNumbers().size() > 200)
      throw Api.fail(
          422, "INVALID_STUDENT_REFERENCES", "Review one to 200 school admission references.");
    return Api.map(
        "items",
        s.db.queryForList(
            "select id,admission_number as \"admissionNumber\",full_name as \"fullName\",version from students where school_id=? and admission_number in(select value from jsonb_array_elements_text(?::jsonb)) order by admission_number",
            b.schoolId(),
            s.json.write(b.admissionNumbers())));
  }

  @PostMapping("/internal/class-roster")
  Object roster(@RequestBody Roster b) {
    Actor.internalOnly("finance", "communication");
    var t = s.one("select * from terms where school_id=? and id=?", b.schoolId(), b.termId());
    if (b.classIds() == null
        || b.classIds().isEmpty()
        || b.classIds().size() > 10
        || new HashSet<>(b.classIds()).size() != b.classIds().size())
      throw Api.fail(422, "INVALID_CLASS_SELECTION", "Choose one to ten distinct classes.");
    LocalDate start = ((java.sql.Date) t.get("starts_on")).toLocalDate(),
        end = ((java.sql.Date) t.get("ends_on")).toLocalDate(),
        on = LocalDate.now(ZoneId.of("Africa/Lagos"));
    if (on.isBefore(start)) on = start;
    if (on.isAfter(end)) on = end;
    List<Object> classes = new ArrayList<>(), students = new ArrayList<>();
    for (UUID id : new TreeSet<>(b.classIds())) {
      var c =
          s.one(
              "select id,level,arm,version from classes where school_id=? and id=? and session_id=?",
              b.schoolId(),
              id,
              t.get("session_id"));
      classes.add(c);
      students.addAll(
          s.db.queryForList(
              "select st.id as \"studentId\",st.full_name as \"fullName\",st.admission_number as \"admissionNumber\",st.version as \"studentVersion\",e.id as \"enrolmentId\",e.version as \"enrolmentVersion\",e.class_id as \"classId\",e.department from enrolments e join students st on st.id=e.student_id where e.school_id=? and e.class_id=? and st.status='ACTIVE' and e.starts_on<=? and (e.ends_on is null or e.ends_on>=?) order by st.id",
              b.schoolId(),
              id,
              on,
              on));
    }
    if (students.size() > 200)
      throw Api.fail(
          422,
          "BATCH_TOO_LARGE",
          "Select fewer classes; each reviewable batch supports at most 200 students.");
    return Api.map(
        "termId",
        b.termId(),
        "startsOn",
        start,
        "endsOn",
        end,
        "on",
        on,
        "classes",
        classes,
        "students",
        students);
  }
}
