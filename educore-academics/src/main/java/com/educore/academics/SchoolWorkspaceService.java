package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SchoolWorkspaceService {
  private final SchoolService s;

  public SchoolWorkspaceService(SchoolService s) {
    this.s = s;
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

  public Object settings(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    var p = s.school(school);
    return map(
        "schoolId",
        school,
        "name",
        p.get("name"),
        "code",
        p.get("code"),
        "phone",
        p.get("contact_phone"),
        "email",
        p.get("contact_email"),
        "address",
        p.get("address"),
        "wednesdayPtEnabled",
        p.get("wednesday_pt"),
        "timezone",
        "Africa/Lagos",
        "currency",
        "NGN",
        "version",
        p.get("version"));
  }

  public record Settings(
      String phone, String email, String address, boolean wednesdayPtEnabled, String reason) {}

  public Object settings(Actor a, UUID school, Settings b, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "settings",
        key,
        map("input", b, "version", version),
        () -> {
          var p = s.one("select * from schools where id=? for update", school);
          expectVersion(version, ((Number) p.get("version")).longValue());
          s.db.update(
              "update schools set contact_phone=?,contact_email=?,address=?,wednesday_pt=?,version=version+1 where id=?",
              b.phone() == null ? null : phone(b.phone()),
              b.email() == null ? null : email(b.email()),
              b.address() == null ? null : text(b.address(), 5, 240, "Address"),
              b.wednesdayPtEnabled(),
              school);
          s.commands.audit(
              school,
              a.accountId(),
              "SCHOOL_SETTINGS_UPDATED",
              "SCHOOL",
              school,
              text(b.reason(), 5, 500, "Reason"),
              map(
                  "beforeVersion",
                  version,
                  "timetableReviewRequired",
                  p.get("wednesday_pt").equals(b.wednesdayPtEnabled()) ? false : true));
          return map(
              "settings",
              settings(a, school),
              "timetableReviewRequired",
              !p.get("wednesday_pt").equals(b.wednesdayPtEnabled()));
        });
  }

  public record FormField(String key, String label, int maxLength, boolean required) {}

  public record Metadata(List<FormField> studentFields, String reason) {}

  public Object metadata(Actor a, UUID school, Metadata b, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "metadata-versions",
        key,
        map("input", b, "version", version),
        () -> {
          s.one("select id from schools where id=? for update", school);
          long latest =
              s.db.queryForObject(
                  "select max(version) from metadata_versions where school_id=?",
                  Long.class,
                  school);
          expectVersion(version, latest);
          if (b.studentFields() == null || b.studentFields().size() > 20)
            throw fail(
                422, "INVALID_METADATA_FORM", "Use at most 20 additional student text fields.");
          Set<String> keys = new HashSet<>();
          for (var f : b.studentFields()) {
            if (f.key() == null
                || !f.key().matches("[a-z][a-zA-Z0-9]{1,39}")
                || !keys.add(f.key())
                || Set.of(
                        "id",
                        "schoolId",
                        "fullName",
                        "dateOfBirth",
                        "sex",
                        "classId",
                        "department",
                        "guardian",
                        "admissionNumber",
                        "status")
                    .contains(f.key())
                || f.maxLength() < 1
                || f.maxLength() > 500)
              throw fail(
                  422,
                  "INVALID_METADATA_FIELD",
                  "Use distinct additional field keys, a label and a text limit from one to 500.");
            text(f.label(), 2, 80, "Field label");
          }
          String reason = text(b.reason(), 5, 500, "Form revision reason");
          s.db.update(
              "insert into metadata_versions(school_id,version,definition) values (?,?,?::jsonb)",
              school,
              latest + 1,
              s.json.write(map("studentFields", b.studentFields())));
          s.commands.audit(
              school,
              a.accountId(),
              "SCHOOL_FORM_VERSION_PUBLISHED",
              "SCHOOL",
              school,
              reason,
              map("previousVersion", latest, "version", latest + 1));
          return s.metadata(a, school);
        });
  }

  public Object guardian(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var g =
        s.one(
            "select id,full_name as \"fullName\",phone,email,version from guardians where school_id=? and id=?",
            school,
            id);
    g.put(
        "children",
        s.db.queryForList(
            "select gl.student_id as \"studentId\",st.full_name as \"studentName\",gl.relationship,gl.active,gl.learning_access as \"learningAccess\",gl.payer_access as \"payerAccess\",gl.primary_contact as \"primaryContact\",gl.version from guardian_links gl join students st on st.id=gl.student_id where gl.school_id=? and gl.guardian_id=? order by st.full_name",
            school,
            id));
    return g;
  }

  public record GuardianEdit(String fullName, String phone, String email, String reason) {}

  public Object guardian(Actor a, UUID school, UUID id, GuardianEdit b, long version, String key) {
    return command(
        a,
        school,
        "guardians/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          var g =
              s.one("select * from guardians where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) g.get("version")).longValue());
          s.db.update(
              "update guardians set full_name=?,phone=?,email=?,version=version+1 where id=?",
              personName(b.fullName()),
              phone(b.phone()),
              b.email() == null ? null : email(b.email()),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "SCHOOL_GUARDIAN_CONTACT_UPDATED",
              "GUARDIAN",
              id,
              text(b.reason(), 5, 500, "Reason"),
              map("beforeVersion", version, "globalSignInChanged", false));
          return guardian(a, school, id);
        });
  }

  public record Teaching(UUID teacherId, SchoolService.AssignmentInput assignment, String reason) {}

  public Object teaching(Actor a, UUID school, Teaching b, String key) {
    return command(
        a,
        school,
        "teaching-assignments",
        key,
        b,
        () -> {
          UUID id = s.assignment(school, b.teacherId(), b.assignment());
          s.commands.audit(
              school,
              a.accountId(),
              "TEACHING_ASSIGNMENT_ADDED",
              "TEACHING_ASSIGNMENT",
              id,
              text(b.reason(), 5, 500, "Reason"),
              map("teacherId", b.teacherId(), "classId", b.assignment().classId()));
          return map("id", id, "version", 1, "timetableRevisionRequired", true);
        });
  }

  public record Activity(String activity, List<UUID> teacherIds, String reason) {}

  public Object activity(Actor a, UUID school, UUID term, Activity b, long version, String key) {
    return command(
        a,
        school,
        "terms/" + term + "/activities",
        key,
        map("input", b, "version", version),
        () -> {
          var t = s.one("select * from terms where school_id=? and id=? for update", school, term);
          expectVersion(version, ((Number) t.get("version")).longValue());
          if (b.activity() == null
              || !Set.of("WEDNESDAY_PT", "FRIDAY_SPORTS").contains(b.activity())
              || b.teacherIds() == null
              || b.teacherIds().isEmpty()
              || b.teacherIds().size() > 20
              || new HashSet<>(b.teacherIds()).size() != b.teacherIds().size())
            throw fail(
                422,
                "INVALID_ACTIVITY_DUTY",
                "Choose an activity and one to 20 distinct teacher supervisors.");
          int day = "WEDNESDAY_PT".equals(b.activity()) ? 3 : 5,
              start = day == 3 ? 480 : 720,
              end = day == 3 ? 510 : 780;
          for (UUID teacher : b.teacherIds()) {
            s.one(
                "select id from staff where school_id=? and id=? and active and staff_role='TEACHER'",
                school,
                teacher);
            if (s.db.queryForObject(
                    "select count(*) from teacher_availability where teacher_id=? and weekday=? and start_min<? and end_min>?",
                    Integer.class,
                    teacher,
                    day,
                    end,
                    start)
                > 0)
              throw fail(
                  409,
                  "SUPERVISOR_UNAVAILABLE",
                  "Choose a supervisor available during the activity period.");
            if (s.db.queryForObject(
                    "select count(*) from exam_events e join exam_schedules x on x.id=e.schedule_id where e.school_id=? and x.term_id=? and x.state='PUBLISHED' and e.invigilator_id=? and extract(isodow from e.on_date)=? and e.start_min<? and e.end_min>? and not exists(select 1 from exam_schedules newer where newer.term_id=x.term_id and newer.state='PUBLISHED' and newer.revision>x.revision)",
                    Integer.class,
                    school,
                    term,
                    teacher,
                    day,
                    end,
                    start)
                > 0)
              throw fail(
                  409,
                  "SUPERVISOR_EXAM_CONFLICT",
                  "This supervisor invigilates a published examination during the activity. Choose another teacher.");
          }
          s.db.update(
              "delete from activity_supervisors where term_id=? and activity=?",
              term,
              b.activity());
          for (UUID teacher : b.teacherIds())
            s.db.update(
                "insert into activity_supervisors(school_id,term_id,activity,teacher_id) values (?,?,?,?)",
                school,
                term,
                b.activity(),
                teacher);
          s.db.update("update terms set version=version+1 where id=?", term);
          s.commands.audit(
              school,
              a.accountId(),
              "ACTIVITY_SUPERVISORS_UPDATED",
              "TERM",
              term,
              text(b.reason(), 5, 500, "Reason"),
              map("activity", b.activity(), "teacherIds", b.teacherIds()));
          return map(
              "termId",
              term,
              "version",
              version + 1,
              "activity",
              b.activity(),
              "teacherIds",
              b.teacherIds());
        });
  }

  public Object activities(Actor a, UUID school, UUID term) {
    a.school(school);
    s.one("select id from terms where school_id=? and id=?", school, term);
    return map(
        "items",
        s.db.queryForList(
            "select x.activity,x.teacher_id as \"teacherId\",st.full_name as \"teacherName\" from activity_supervisors x join staff st on st.id=x.teacher_id where x.school_id=? and x.term_id=? order by x.activity,st.full_name",
            school,
            term),
        "hasMore",
        false);
  }

  public Object myClasses(Actor a, UUID school, UUID session) {
    var c = a.school(school, "TEACHER", "PARENT", "STUDENT");
    if ("TEACHER".equals(c.role()))
      return map(
          "items",
          s.db.queryForList(
              "select cl.id,cl.level,cl.arm,cl.session_id as \"sessionId\",cl.version,cl.attendance_teacher_id=? as \"classTeacher\" from classes cl where cl.school_id=? and (?::uuid is null or cl.session_id=?::uuid) and (cl.attendance_teacher_id=? or exists(select 1 from teaching_assignments t where t.class_id=cl.id and t.teacher_id=? and t.active)) order by cl.level,cl.arm",
              c.subjectId(),
              school,
              session,
              session,
              c.subjectId(),
              c.subjectId()),
          "hasMore",
          false);
    UUID child = "PARENT".equals(c.role()) ? c.childId() : c.subjectId();
    s.learning(a, school, child);
    return map(
        "items",
        s.db.queryForList(
            "select cl.id,cl.level,cl.arm,cl.session_id as \"sessionId\",e.department,e.starts_on as \"startsOn\",e.ends_on as \"endsOn\" from enrolments e join classes cl on cl.id=e.class_id where e.school_id=? and e.student_id=? and (?::uuid is null or e.session_id=?::uuid) order by e.starts_on desc",
            school,
            child,
            session,
            session),
        "hasMore",
        false);
  }

  public Object dashboard(Actor a, UUID school) {
    var c = a.school(school);
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(c.role()))
      return map(
          "role",
          c.role(),
          "asOf",
          LocalDate.now(ZoneId.of("Africa/Lagos")),
          "activeStudentCount",
          s.db.queryForObject(
              "select count(distinct e.student_id) from enrolments e join students st on st.id=e.student_id where e.school_id=? and st.status='ACTIVE' and e.starts_on<=current_date and (e.ends_on is null or e.ends_on>=current_date)",
              Integer.class,
              school),
          "teacherCount",
          s.db.queryForObject(
              "select count(*) from staff where school_id=? and staff_role='TEACHER' and active",
              Integer.class,
              school),
          "assessmentCyclesAwaitingPublication",
          s.db.queryForObject(
              "select count(*) from assessment_cycles where school_id=? and state in ('OPEN','REVIEW')",
              Integer.class,
              school),
          "importsAwaitingReview",
          s.db.queryForObject(
              "select count(*) from academic_imports where school_id=? and state in ('PREVIEW','PARTIAL','PAUSED')",
              Integer.class,
              school));
    if ("ACCOUNTS".equals(c.role())) return map("role", "ACCOUNTS", "schoolId", school);
    if ("TEACHER".equals(c.role()))
      return map(
          "role",
          "TEACHER",
          "classes",
          myClasses(a, school, null),
          "sheetsAwaitingSubmission",
          s.db.queryForObject(
              "select count(*) from assessment_sheets sh join assessment_cycles x on x.id=sh.cycle_id where x.school_id=? and sh.teacher_id=? and x.state in ('OPEN','REVIEW') and sh.state<>'SUBMITTED'",
              Integer.class,
              school,
              c.subjectId()));
    UUID child = "PARENT".equals(c.role()) ? c.childId() : c.subjectId();
    s.learning(a, school, child);
    return map(
        "role",
        c.role(),
        "studentId",
        child,
        "classes",
        myClasses(a, school, null),
        "publishedReportCount",
        s.db.queryForObject(
            "select count(distinct term_id) from report_versions where school_id=? and student_id=?",
            Integer.class,
            school,
            child));
  }

  public Object followUps(Actor a, UUID school, LocalDate on) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    if (on == null) on = LocalDate.now(ZoneId.of("Africa/Lagos"));
    if (on.isAfter(LocalDate.now(ZoneId.of("Africa/Lagos"))))
      throw fail(
          422,
          "FUTURE_ATTENDANCE_WINDOW",
          "Review submitted attendance through today or an earlier date.");
    var rows =
        s.db.queryForList(
            "with days as (select d::date as day from generate_series(?::date-366,?::date,interval '1 day') d where exists(select 1 from terms t where t.school_id=? and d::date between t.starts_on and t.ends_on) and coalesce((select teaching_day from calendar_exceptions ex where ex.school_id=? and ex.on_date=d::date),extract(isodow from d)<=5) order by d desc limit 10) select st.id as \"studentId\",st.full_name as \"studentName\",count(*) filter(where ar.status='ABSENT') as absences,count(*) filter(where ar.status is not null) as \"submittedRegisters\",count(*) as \"expectedRegisters\",min(days.day) as \"windowFrom\",max(days.day) as \"windowTo\" from students st join days on true join enrolments e on e.student_id=st.id and e.starts_on<=days.day and (e.ends_on is null or e.ends_on>=days.day) left join attendance_registers reg on reg.class_id=e.class_id and reg.on_date=days.day and reg.state='SUBMITTED' left join attendance_rows ar on ar.register_id=reg.id and ar.student_id=st.id where st.school_id=? and st.status='ACTIVE' group by st.id having count(*) filter(where ar.status='ABSENT')>=3 order by absences desc,st.full_name",
            on,
            on,
            school,
            school,
            school);
    for (var row : rows)
      row.put(
          "coverageComplete",
          ((Number) row.get("submittedRegisters")).longValue()
              == ((Number) row.get("expectedRegisters")).longValue());
    return map(
        "items",
        rows,
        "rule",
        map("confirmedAbsences", 3, "teachingDays", 10),
        "on",
        on,
        "hasMore",
        false);
  }

  public byte[] studentsCsv(Actor a, UUID school, UUID classId) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    int n =
        s.db.queryForObject(
            "select count(*) from students st where st.school_id=? and (?::uuid is null or exists(select 1 from enrolments e where e.student_id=st.id and e.class_id=?::uuid))",
            Integer.class,
            school,
            classId,
            classId);
    if (n > 10000)
      throw fail(422, "EXPORT_TOO_LARGE", "Filter this export to a class before downloading.");
    var rows =
        s.db.queryForList(
            "select st.admission_number,st.full_name,st.date_of_birth,st.sex,st.status,cl.level,cl.arm,e.department,g.full_name as guardian_name,g.phone as guardian_phone from students st left join lateral(select * from enrolments en where en.student_id=st.id order by starts_on desc limit 1)e on true left join classes cl on cl.id=e.class_id left join lateral(select gu.* from guardian_links gl join guardians gu on gu.id=gl.guardian_id where gl.student_id=st.id and gl.active order by gl.primary_contact desc,gu.id limit 1)g on true where st.school_id=? and (?::uuid is null or e.class_id=?::uuid) order by st.admission_number",
            school,
            classId,
            classId);
    var result = new ArrayList<List<?>>();
    for (var r : rows)
      result.add(
          Arrays.asList(
              r.get("admission_number"),
              r.get("full_name"),
              r.get("date_of_birth"),
              r.get("sex"),
              r.get("status"),
              r.get("level"),
              r.get("arm"),
              r.get("department"),
              r.get("guardian_name"),
              r.get("guardian_phone")));
    s.commands.audit(
        school,
        a.accountId(),
        "STUDENT_DIRECTORY_EXPORTED",
        "SCHOOL",
        school,
        null,
        map("rows", rows.size(), "classId", classId));
    return Documents.csv(
        List.of(
            "admission_number",
            "full_name",
            "date_of_birth",
            "sex",
            "status",
            "level",
            "arm",
            "department",
            "guardian_name",
            "guardian_phone"),
        result);
  }

  public byte[] staffCsv(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var rows =
        s.db.queryForList(
            "select staff_number,full_name,phone,email,staff_role,active from staff where school_id=? and staff_role='TEACHER' order by staff_number limit 10000",
            school);
    var data = new ArrayList<List<?>>();
    for (var r : rows)
      data.add(
          Arrays.asList(
              r.get("staff_number"),
              r.get("full_name"),
              r.get("phone"),
              r.get("email"),
              r.get("staff_role"),
              r.get("active")));
    return Documents.csv(
        List.of("staff_number", "full_name", "phone", "email", "role", "active"), data);
  }
}
