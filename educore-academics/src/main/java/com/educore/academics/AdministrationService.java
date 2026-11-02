package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdministrationService {
  private final SchoolService s;

  public AdministrationService(SchoolService s) {
    this.s = s;
  }

  private Object command(
      Actor a,
      UUID school,
      String route,
      String key,
      Object body,
      java.util.function.Supplier<Object> action) {
    return s.commands.execute(
        a.accountId().toString(), school.toString(), route, key, body, action);
  }

  private Map<String, Object> staff(UUID school, UUID id, boolean lock) {
    return s.one(
        "select * from staff where school_id=? and id=?" + (lock ? " for update" : ""), school, id);
  }

  public record StaffInput(
      String fullName,
      String phone,
      String email,
      String staffNumber,
      String role,
      LocalDate joinedOn,
      String existingLoginId,
      boolean relationshipVerified) {}

  public Object addStaff(Actor a, UUID school, StaffInput b, String key) {
    a.school(school, "PRINCIPAL");
    if (b.role() == null || !Set.of("PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS").contains(b.role()))
      throw fail(
          422,
          "INVALID_STAFF_PRESET",
          "Choose Principal, School Office or Accounts. Register subject teachers through teacher registration.");
    return command(
        a,
        school,
        "staff",
        key,
        b,
        () -> {
          UUID id = UUID.randomUUID();
          String name = personName(b.fullName()), phone = phone(b.phone());
          String number =
              b.staffNumber() == null
                  ? "ST-" + id.toString().replace("-", "").toUpperCase(Locale.ROOT)
                  : text(b.staffNumber(), 2, 40, "Staff number");
          LocalDate joined =
              b.joinedOn() == null ? LocalDate.now(ZoneId.of("Africa/Lagos")) : b.joinedOn();
          s.db.update(
              "insert into staff(id,school_id,full_name,phone,email,staff_number,staff_role,joined_on,existing_login_id,relationship_verified) values (?,?,?,?,?,?,?,?,?,?)",
              id,
              school,
              name,
              phone,
              b.email() == null ? null : email(b.email()),
              number,
              b.role(),
              joined,
              b.existingLoginId(),
              b.relationshipVerified());
          UUID event = staffEvent(school, id);
          s.commands.audit(
              school, a.accountId(), "STAFF_REGISTERED", "STAFF", id, null, map("role", b.role()));
          return map(
              "id",
              id,
              "version",
              1,
              "staffNumber",
              number,
              "accessStatus",
              "PENDING",
              "eventId",
              event);
        });
  }

  private UUID staffEvent(UUID school, UUID id) {
    var p = staff(school, id, false);
    UUID event =
        s.outbox.add(
            "identity",
            "STAFF_ACCESS_CHANGED",
            map(
                "schoolId",
                school,
                "schoolName",
                s.school(school).get("name"),
                "staff",
                map(
                    "id",
                    id,
                    "fullName",
                    p.get("full_name"),
                    "phone",
                    p.get("phone"),
                    "existingLoginId",
                    p.get("existing_login_id"),
                    "relationshipVerified",
                    p.get("relationship_verified"),
                    "role",
                    p.get("staff_role"),
                    "active",
                    p.get("active"),
                    "version",
                    p.get("version"))));
    s.db.update("update staff set access_event_id=? where id=?", event, id);
    return event;
  }

  public Object staffList(Actor a, UUID school, UUID cursor, int limit) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return page(
        s.db.queryForList(
            "select st.id,st.full_name as \"fullName\",st.staff_number as \"staffNumber\",st.staff_role as role,st.active,st.version,case when o.id is null then 'PROVISIONED' when o.delivered_at is null then 'PENDING' else 'PROVISIONED' end as \"accessStatus\" from staff st left join outbox o on o.id=st.access_event_id where st.school_id=? and (?::uuid is null or st.id>?::uuid) order by st.id limit ?",
            school,
            cursor,
            cursor,
            limit + 1),
        limit,
        r -> r.get("id").toString());
  }

  public Object staffDetail(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    if ("TEACHER".equals(a.context().role()) && !id.equals(a.context().subjectId()))
      throw fail(404, "NOT_FOUND", "The staff record is unavailable.");
    var p = staff(school, id, false);
    return map(
        "id",
        id,
        "fullName",
        p.get("full_name"),
        "phone",
        p.get("phone"),
        "email",
        p.get("email"),
        "staffNumber",
        p.get("staff_number"),
        "role",
        p.get("staff_role"),
        "active",
        p.get("active"),
        "version",
        p.get("version"),
        "joinedOn",
        p.get("joined_on"),
        "qualifications",
        s.db.queryForList(
            "select subject_id as \"subjectId\",level from staff_qualifications where school_id=? and staff_id=? order by level,subject_id",
            school,
            id),
        "assignments",
        s.db.queryForList(
            "select id,class_id as \"classId\",subject_id as \"subjectId\",department,weekly_periods as \"weeklyPeriods\",version from teaching_assignments where school_id=? and teacher_id=?",
            school,
            id),
        "attendanceClasses",
        s.db.queryForList(
            "select id,level,arm from classes where school_id=? and attendance_teacher_id=?",
            school,
            id));
  }

  public record StaffEdit(
      String fullName,
      String phone,
      String email,
      String staffNumber,
      LocalDate joinedOn,
      String reason) {}

  public Object editStaff(Actor a, UUID school, UUID id, StaffEdit b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    if ("SCHOOL_OFFICE".equals(a.context().role())
        && !"TEACHER".equals(staff(school, id, false).get("staff_role")))
      throw fail(403, "FORBIDDEN", "Only the principal maintains administrator records.");
    return command(
        a,
        school,
        "staff/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          var old = staff(school, id, true);
          expectVersion(version, ((Number) old.get("version")).longValue());
          String why = text(b.reason(), 5, 500, "Change reason");
          s.db.update(
              "update staff set full_name=?,phone=?,email=?,staff_number=?,joined_on=?,version=version+1 where id=?",
              personName(b.fullName()),
              phone(b.phone()),
              b.email() == null ? null : email(b.email()),
              text(b.staffNumber(), 2, 40, "Staff number"),
              b.joinedOn(),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "STAFF_UPDATED",
              "STAFF",
              id,
              why,
              map("previousName", old.get("full_name"), "fullName", b.fullName()));
          return staffDetail(a, school, id);
        });
  }

  public record AccessInput(String role, boolean active, String reason) {}

  public Object access(Actor a, UUID school, UUID id, AccessInput b, long version, String key) {
    a.school(school, "PRINCIPAL");
    if (b.role() == null
        || !Set.of("PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS", "TEACHER").contains(b.role()))
      throw fail(422, "INVALID_PRESET", "Choose a supported school staff preset.");
    return command(
        a,
        school,
        "staff/" + id + "/access",
        key,
        map("input", b, "version", version),
        () -> {
          s.one("select id from schools where id=? for update", school);
          var p = staff(school, id, true);
          expectVersion(version, ((Number) p.get("version")).longValue());
          if ("PRINCIPAL".equals(p.get("staff_role"))
              && Boolean.TRUE.equals(p.get("active"))
              && (!b.active() || !"PRINCIPAL".equals(b.role()))
              && s.db.queryForObject(
                      "select count(*) from staff where school_id=? and active and staff_role='PRINCIPAL'",
                      Integer.class,
                      school)
                  <= 1)
            throw fail(
                409,
                "LAST_PRINCIPAL",
                "Add and provision another active principal before removing this access.");
          if (!b.active() || !"TEACHER".equals(b.role())) {
            int work =
                s.db.queryForObject(
                        "select count(*) from teaching_assignments t join classes c on c.id=t.class_id join academic_sessions x on x.id=c.session_id where t.teacher_id=? and x.ends_on>=current_date",
                        Integer.class,
                        id)
                    + s.db.queryForObject(
                        "select count(*) from classes c join academic_sessions x on x.id=c.session_id where c.attendance_teacher_id=? and x.ends_on>=current_date",
                        Integer.class,
                        id);
            if (work > 0)
              throw fail(
                  409,
                  "HANDOVER_REQUIRED",
                  "Reassign current teaching and attendance duties before removing teacher access.");
          }
          if ("TEACHER".equals(b.role())
              && s.db.queryForObject(
                      "select count(*) from staff_qualifications where staff_id=?",
                      Integer.class,
                      id)
                  == 0)
            throw fail(
                422,
                "QUALIFICATIONS_REQUIRED",
                "Save teaching qualifications before assigning teacher access.");
          String why = text(b.reason(), 5, 500, "Access change reason");
          s.db.update(
              "update staff set staff_role=?,active=?,version=version+1 where id=?",
              b.role(),
              b.active(),
              id);
          UUID event = staffEvent(school, id);
          s.commands.audit(
              school,
              a.accountId(),
              "STAFF_ACCESS_CHANGED",
              "STAFF",
              id,
              why,
              map("previousRole", p.get("staff_role"), "role", b.role(), "active", b.active()));
          return map("id", id, "version", version + 1, "accessStatus", "PENDING", "eventId", event);
        });
  }

  public record AssignmentEdit(UUID teacherId, int weeklyPeriods, String reason) {}

  public Object assignment(
      Actor a, UUID school, UUID id, AssignmentEdit b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "teaching-assignments/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          var old =
              s.one(
                  "select t.*,c.level from teaching_assignments t join classes c on c.id=t.class_id where t.school_id=? and t.id=? for update of t",
                  school,
                  id);
          expectVersion(version, ((Number) old.get("version")).longValue());
          if (b.weeklyPeriods() < 1 || b.weeklyPeriods() > 10)
            throw fail(422, "INVALID_PERIOD_TARGET", "Use one to ten weekly periods.");
          var teacher = staff(school, b.teacherId(), false);
          if (!Boolean.TRUE.equals(teacher.get("active"))
              || !"TEACHER".equals(teacher.get("staff_role"))
              || s.db.queryForObject(
                      "select count(*) from staff_qualifications where staff_id=? and subject_id=? and level=?",
                      Integer.class,
                      b.teacherId(),
                      old.get("subject_id"),
                      old.get("level"))
                  == 0)
            throw fail(
                422,
                "TEACHER_NOT_ELIGIBLE",
                "Choose an active teacher qualified for this subject and level.");
          String why = text(b.reason(), 5, 500, "Handover reason");
          s.db.update(
              "update teaching_assignments set teacher_id=?,weekly_periods=?,version=version+1 where id=?",
              b.teacherId(),
              b.weeklyPeriods(),
              id);
          s.db.update(
              "update assessment_sheets sh set teacher_id=?,state=case when sh.state='SUBMITTED' then 'RETURNED' else sh.state end,return_reason=?,version=sh.version+1 from assessment_cycles ac where ac.id=sh.cycle_id and ac.state<>'PUBLISHED' and sh.assignment_id=?",
              b.teacherId(),
              why,
              id);
          s.db.update(
              "update assessment_cycles set version=version+1 where state<>'PUBLISHED' and id in (select cycle_id from assessment_sheets where assignment_id=?)",
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "TEACHING_HANDOVER",
              "TEACHING_ASSIGNMENT",
              id,
              why,
              map("previousTeacherId", old.get("teacher_id"), "teacherId", b.teacherId()));
          return map(
              "id",
              id,
              "version",
              version + 1,
              "teacherId",
              b.teacherId(),
              "timetableRevisionRequired",
              true);
        });
  }

  public record StudentEdit(
      String fullName,
      LocalDate dateOfBirth,
      String sex,
      Map<String, Object> fields,
      long metadataVersion,
      String reason) {}

  public Object editStudent(
      Actor a, UUID school, UUID id, StudentEdit b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "students/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          var old =
              s.one("select * from students where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) old.get("version")).longValue());
          if (b.dateOfBirth() == null
              || b.dateOfBirth().isAfter(LocalDate.now(ZoneId.of("Africa/Lagos")))
              || b.dateOfBirth().isBefore(LocalDate.of(1900, 1, 1))
              || !Set.of("MALE", "FEMALE").contains(Objects.toString(b.sex(), "")))
            throw fail(
                422, "INVALID_STUDENT_IDENTITY", "Provide a valid date of birth and recorded sex.");
          var meta =
              s.one(
                  "select version,definition from metadata_versions where school_id=? order by version desc limit 1",
                  school);
          expectVersion(b.metadataVersion(), ((Number) meta.get("version")).longValue());
          s.validateFields(
              s.json.tree(meta.get("definition").toString()),
              b.fields() == null ? Map.of() : b.fields());
          String why = text(b.reason(), 5, 500, "Change reason");
          s.db.update(
              "update students set full_name=?,date_of_birth=?,sex=?,metadata=?::jsonb,version=version+1 where id=?",
              personName(b.fullName()),
              b.dateOfBirth(),
              b.sex(),
              s.json.write(b.fields() == null ? Map.of() : b.fields()),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "STUDENT_UPDATED",
              "STUDENT",
              id,
              why,
              map("previousName", old.get("full_name"), "fullName", b.fullName()));
          return s.student(a, school, id);
        });
  }

  public record Transfer(UUID classId, LocalDate effectiveOn, String department, String reason) {}

  public Object transfer(Actor a, UUID school, UUID student, Transfer b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "students/" + student + "/transfers",
        key,
        map("input", b, "version", version),
        () -> {
          var st =
              s.one(
                  "select version,status from students where school_id=? and id=? for update",
                  school,
                  student);
          expectVersion(version, ((Number) st.get("version")).longValue());
          if (!"ACTIVE".equals(st.get("status")) || b.effectiveOn() == null)
            throw fail(422, "INVALID_TRANSFER", "Choose an active student and an effective date.");
          var target =
              s.one(
                  "select c.*,x.starts_on,x.ends_on from classes c join academic_sessions x on x.id=c.session_id where c.school_id=? and c.id=? for update of c",
                  school,
                  b.classId());
          var old =
              s.one(
                  "select e.*,c.level from enrolments e join classes c on c.id=e.class_id where e.school_id=? and e.student_id=? and e.session_id=? and e.starts_on<? and (e.ends_on is null or e.ends_on>=?) for update of e",
                  school,
                  student,
                  target.get("session_id"),
                  b.effectiveOn(),
                  b.effectiveOn());
          if (!old.get("level").equals(target.get("level")))
            throw fail(
                422, "PROMOTION_REQUIRED", "Use reviewed promotion for a change of school level.");
          if (b.effectiveOn().isAfter(((java.sql.Date) target.get("ends_on")).toLocalDate()))
            throw fail(
                422, "INVALID_EFFECTIVE_DATE", "Transfer must take effect within its session.");
          String why = text(b.reason(), 5, 500, "Transfer reason"),
              department = SchoolRules.department((String) target.get("level"), b.department());
          capacity(school, b.classId(), b.effectiveOn());
          s.db.update(
              "update enrolments set ends_on=?,version=version+1 where id=?",
              b.effectiveOn().minusDays(1),
              old.get("id"));
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into enrolments(id,school_id,student_id,session_id,class_id,department,starts_on) values (?,?,?,?,?,?,?)",
              id,
              school,
              student,
              target.get("session_id"),
              b.classId(),
              department,
              b.effectiveOn());
          s.db.update("update students set version=version+1 where id=?", student);
          s.commands.audit(
              school,
              a.accountId(),
              "STUDENT_CLASS_TRANSFER",
              "STUDENT",
              student,
              why,
              map(
                  "previousClassId",
                  old.get("class_id"),
                  "classId",
                  b.classId(),
                  "effectiveOn",
                  b.effectiveOn()));
          return map(
              "id",
              id,
              "studentId",
              student,
              "version",
              version + 1,
              "effectiveOn",
              b.effectiveOn());
        });
  }

  void capacity(UUID school, UUID classId, LocalDate date) {
    var c =
        s.one(
            "select capacity from classes where school_id=? and id=? for update", school, classId);
    if (c.get("capacity") != null
        && s.db.queryForObject(
                "select count(*) from enrolments where class_id=? and starts_on<=? and (ends_on is null or ends_on>=?)",
                Integer.class,
                classId,
                date,
                date)
            >= ((Number) c.get("capacity")).intValue())
      throw fail(409, "CLASS_FULL", "The selected class has reached its capacity.");
  }

  public record Withdrawal(LocalDate effectiveOn, String reason) {}

  public Object withdraw(
      Actor a, UUID school, UUID student, Withdrawal b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "students/" + student + "/withdrawal",
        key,
        map("input", b, "version", version),
        () -> {
          var st =
              s.one(
                  "select * from students where school_id=? and id=? for update", school, student);
          expectVersion(version, ((Number) st.get("version")).longValue());
          if (b.effectiveOn() == null
              || b.effectiveOn().isAfter(LocalDate.now(ZoneId.of("Africa/Lagos"))))
            throw fail(
                422,
                "INVALID_EFFECTIVE_DATE",
                "Record an effective withdrawal date no later than today.");
          if (s.db.queryForObject(
                  "select count(*) from enrolments where student_id=? and starts_on>?",
                  Integer.class,
                  student,
                  b.effectiveOn())
              > 0)
            throw fail(
                409, "FUTURE_ENROLMENT_EXISTS", "Resolve future placements before withdrawal.");
          String why = text(b.reason(), 5, 500, "Withdrawal reason");
          s.db.update(
              "update enrolments set ends_on=?,version=version+1 where student_id=? and starts_on<=? and (ends_on is null or ends_on>?)",
              b.effectiveOn(),
              student,
              b.effectiveOn(),
              b.effectiveOn());
          s.db.update(
              "update students set status='WITHDRAWN',version=version+1 where id=?", student);
          s.commands.audit(
              school,
              a.accountId(),
              "STUDENT_WITHDRAWN",
              "STUDENT",
              student,
              why,
              map("effectiveOn", b.effectiveOn()));
          return map("id", student, "version", version + 1, "status", "WITHDRAWN");
        });
  }

  public record GuardianLink(
      UUID guardianId,
      SchoolService.GuardianInput guardian,
      String relationship,
      boolean learningAccess,
      boolean payerAccess,
      boolean primaryContact,
      String reason) {}

  public Object guardian(Actor a, UUID school, UUID student, GuardianLink b, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "students/" + student + "/guardians",
        key,
        b,
        () -> {
          var st = s.one("select * from students where school_id=? and id=?", school, student);
          UUID id = b.guardianId();
          if (id == null) {
            var p = b.guardian();
            if (p == null || !p.relationshipVerified())
              throw fail(
                  422,
                  "RELATIONSHIP_NOT_VERIFIED",
                  "Verify the guardian relationship before linking.");
            id = UUID.randomUUID();
            s.db.update(
                "insert into guardians(id,school_id,full_name,phone,email,existing_login_id,relationship_verified,verification_reference) values (?,?,?,?,?,?,true,?)",
                id,
                school,
                personName(p.fullName()),
                phone(p.phone()),
                p.email() == null ? null : email(p.email()),
                p.existingLoginId(),
                text(p.verificationReference(), 4, 160, "Verification reference"));
          }
          var parent = s.one("select * from guardians where school_id=? and id=?", school, id);
          String why = text(b.reason(), 5, 500, "Link reason");
          s.db.update(
              "insert into guardian_links(school_id,student_id,guardian_id,relationship,learning_access,payer_access,primary_contact) values (?,?,?,?,?,?,?) on conflict(school_id,student_id,guardian_id) do update set relationship=excluded.relationship,learning_access=excluded.learning_access,payer_access=excluded.payer_access,primary_contact=excluded.primary_contact,active=true,version=guardian_links.version+1",
              school,
              student,
              id,
              text(b.relationship(), 2, 40, "Relationship"),
              b.learningAccess(),
              b.payerAccess(),
              b.primaryContact());
          var link =
              s.one(
                  "select version from guardian_links where school_id=? and student_id=? and guardian_id=?",
                  school,
                  student,
                  id);
          UUID event =
              s.outbox.add(
                  "identity",
                  "GUARDIAN_LINK_UPDATED",
                  map(
                      "schoolId",
                      school,
                      "schoolName",
                      s.school(school).get("name"),
                      "studentId",
                      student,
                      "studentName",
                      st.get("full_name"),
                      "guardian",
                      map(
                          "id",
                          id,
                          "fullName",
                          parent.get("full_name"),
                          "phone",
                          parent.get("phone"),
                          "existingLoginId",
                          parent.get("existing_login_id"),
                          "relationshipVerified",
                          true),
                      "learningAccess",
                      b.learningAccess(),
                      "payerAccess",
                      b.payerAccess(),
                      "version",
                      link.get("version")));
          s.commands.audit(
              school,
              a.accountId(),
              "GUARDIAN_LINK_UPDATED",
              "STUDENT",
              student,
              why,
              map(
                  "guardianId",
                  id,
                  "learningAccess",
                  b.learningAccess(),
                  "payerAccess",
                  b.payerAccess()));
          return map(
              "guardianId",
              id,
              "studentId",
              student,
              "version",
              link.get("version"),
              "accessStatus",
              "PENDING",
              "eventId",
              event);
        });
  }
}
