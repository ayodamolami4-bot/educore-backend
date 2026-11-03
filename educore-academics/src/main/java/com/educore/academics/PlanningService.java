package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PlanningService {
  private final SchoolService s;
  private final TimetableService timetables;

  public PlanningService(SchoolService s, TimetableService timetables) {
    this.s = s;
    this.timetables = timetables;
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object input,
      java.util.function.Supplier<Object> action) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return s.commands.execute(
        a.accountId().toString(), school.toString(), path, key, input, action);
  }

  private Map<String, Object> cls(UUID school, UUID id) {
    return s.one("select * from classes where school_id=? and id=?", school, id);
  }

  void classRead(Actor a, UUID school, UUID id, LocalDate on) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "PARENT", "STUDENT");
    cls(school, id);
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(c.role())) return;
    if ("TEACHER".equals(c.role())) {
      if (s.db.queryForObject(
              "select count(*) from classes c where c.school_id=? and c.id=? and (c.attendance_teacher_id=? or exists(select 1 from teaching_assignments t where t.class_id=c.id and t.teacher_id=? and t.active))",
              Integer.class,
              school,
              id,
              c.subjectId(),
              c.subjectId())
          > 0) return;
    } else {
      UUID student = "PARENT".equals(c.role()) ? c.childId() : c.subjectId();
      s.learning(a, school, student);
      if (s.db.queryForObject(
              "select count(*) from enrolments where school_id=? and class_id=? and student_id=? and starts_on<=? and (ends_on is null or ends_on>=?)",
              Integer.class,
              school,
              id,
              student,
              on,
              on)
          > 0) return;
    }
    throw fail(404, "NOT_FOUND", "This class workspace is unavailable.");
  }

  public Object workspace(Actor a, UUID school, UUID id, LocalDate on) {
    if (on == null) on = LocalDate.now(ZoneId.of("Africa/Lagos"));
    classRead(a, school, id, on);
    var c = cls(school, id);
    var role = a.context().role();
    boolean staff = Set.of("PRINCIPAL", "SCHOOL_OFFICE", "TEACHER").contains(role);
    var rows =
        s.db.queryForList(
            "select st.id,st.full_name as \"fullName\",st.admission_number as \"admissionNumber\",e.department,e.version as \"enrolmentVersion\" from enrolments e join students st on st.id=e.student_id where e.school_id=? and e.class_id=? and e.starts_on<=? and (e.ends_on is null or e.ends_on>=?) order by st.full_name,st.id",
            school,
            id,
            on,
            on);
    if (!staff)
      rows.removeIf(
          r ->
              !r.get("id")
                  .equals("PARENT".equals(role) ? a.context().childId() : a.context().subjectId()));
    return map(
        "class",
        c,
        "on",
        on,
        "students",
        rows,
        "subjects",
        s.db.queryForList(
            "select t.id,t.subject_id as \"subjectId\",su.name as \"subjectName\",t.teacher_id as \"teacherId\",st.full_name as \"teacherName\",t.department,t.required,t.weekly_periods as \"weeklyPeriods\",t.version from teaching_assignments t join subjects su on su.id=t.subject_id join staff st on st.id=t.teacher_id where t.school_id=? and t.class_id=? and t.active order by su.name",
            school,
            id),
        "rosterIsComplete",
        staff);
  }

  public record ClassEdit(Integer capacity, String room, String reason) {}

  public Object editClass(Actor a, UUID school, UUID id, ClassEdit b, long version, String key) {
    return command(
        a,
        school,
        "classes/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          var c = s.one("select * from classes where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) c.get("version")).longValue());
          if (b.capacity() != null && (b.capacity() < 1 || b.capacity() > 200))
            throw fail(422, "INVALID_CAPACITY", "Use a class capacity from 1 to 200.");
          if (b.capacity() != null
              && s.db.queryForObject(
                      "select count(*) from enrolments where class_id=? and starts_on<=current_date and (ends_on is null or ends_on>=current_date)",
                      Integer.class,
                      id)
                  > b.capacity())
            throw fail(
                409,
                "CLASS_CAPACITY_BELOW_ROSTER",
                "Capacity cannot be below the current class roster.");
          s.db.update(
              "update classes set capacity=?,room=?,version=version+1 where id=?",
              b.capacity(),
              b.room() == null ? null : text(b.room(), 1, 40, "Room"),
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "CLASS_DETAILS_UPDATED",
              "CLASS",
              id,
              text(b.reason(), 5, 500, "Reason"),
              map("beforeVersion", version));
          return cls(school, id);
        });
  }

  public record SubjectRule(
      UUID subjectId, boolean required, String choiceGroup, int weeklyPeriods) {}

  public record Curriculum(
      UUID sessionId, String level, String department, String name, List<SubjectRule> subjects) {}

  public Object addCurriculum(Actor a, UUID school, Curriculum b, String key) {
    return command(
        a,
        school,
        "curricula",
        key,
        b,
        () -> {
          s.one(
              "select id from academic_sessions where school_id=? and id=? for update",
              school,
              b.sessionId());
          if (b.level() == null
              || !Set.of("JSS1", "JSS2", "JSS3", "SSS1", "SSS2", "SSS3").contains(b.level()))
            throw fail(422, "INVALID_LEVEL", "Choose a secondary-school level.");
          if (b.department() != null) SchoolRules.department(b.level(), b.department());
          if (b.subjects() == null || b.subjects().isEmpty() || b.subjects().size() > 25)
            throw fail(422, "SUBJECTS_REQUIRED", "Choose one to 25 curriculum subjects.");
          Set<UUID> seen = new HashSet<>();
          Map<String, Integer> groups = new HashMap<>();
          for (var r : b.subjects()) {
            s.one("select id from subjects where school_id=? and id=?", school, r.subjectId());
            if (!seen.add(r.subjectId())
                || r.weeklyPeriods() < 1
                || r.weeklyPeriods() > 10
                || (r.required() && r.choiceGroup() != null)
                || (!r.required()
                    && (r.choiceGroup() == null
                        || !r.choiceGroup().matches("[A-Z][A-Z0-9_]{1,29}"))))
              throw fail(
                  422,
                  "INVALID_CURRICULUM_SUBJECT",
                  "Use distinct subjects, one to ten periods, and an uppercase choice-group code for each optional subject.");
            if (r.choiceGroup() != null) groups.merge(r.choiceGroup(), 1, Integer::sum);
          }
          if (groups.values().stream().anyMatch(n -> n < 2))
            throw fail(
                422,
                "INCOMPLETE_CHOICE_GROUP",
                "Each choice group needs at least two alternative subjects.");
          int revision =
              s.db.queryForObject(
                  "select coalesce(max(revision),0)+1 from curriculum_templates where session_id=? and level=? and department is not distinct from ?::text",
                  Integer.class,
                  b.sessionId(),
                  b.level(),
                  b.department());
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into curriculum_templates(id,school_id,session_id,level,department,name,revision,definition,created_by) values (?,?,?,?,?,?,?,?::jsonb,?)",
              id,
              school,
              b.sessionId(),
              b.level(),
              b.department(),
              text(b.name(), 3, 120, "Curriculum name"),
              revision,
              s.json.write(b.subjects()),
              a.accountId());
          return curriculum(a, school, id);
        });
  }

  public Object curricula(Actor a, UUID school, UUID session) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    return map(
        "items",
        s.db.queryForList(
            "select id,session_id as \"sessionId\",level,department,name,revision,state,version from curriculum_templates where school_id=? and (?::uuid is null or session_id=?::uuid) order by level,name,revision desc",
            school,
            session,
            session),
        "hasMore",
        false);
  }

  public Object curriculum(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    var c = s.one("select * from curriculum_templates where school_id=? and id=?", school, id);
    c.put("subjects", s.json.value(c.remove("definition").toString()));
    return c;
  }

  public Object publishCurriculum(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "curricula/" + id + "/publish",
        key,
        map("version", version),
        () -> {
          var c =
              s.one(
                  "select * from curriculum_templates where school_id=? and id=? for update",
                  school,
                  id);
          expectVersion(version, ((Number) c.get("version")).longValue());
          if (!"DRAFT".equals(c.get("state")))
            throw fail(409, "ALREADY_PUBLISHED", "Create a new curriculum revision for changes.");
          s.db.update(
              "update curriculum_templates set state='PUBLISHED',published_at=now(),version=version+1 where id=?",
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "CURRICULUM_PUBLISHED",
              "CURRICULUM",
              id,
              null,
              map("revision", c.get("revision")));
          return curriculum(a, school, id);
        });
  }

  public record CurriculumApply(UUID curriculumId, Map<UUID, UUID> teachers, String reason) {}

  public Object applyCurriculum(
      Actor a, UUID school, UUID classId, CurriculumApply b, long version, String key) {
    return command(
        a,
        school,
        "classes/" + classId + "/curriculum",
        key,
        map("input", b, "version", version),
        () -> {
          var c =
              s.one("select * from classes where school_id=? and id=? for update", school, classId);
          expectVersion(version, ((Number) c.get("version")).longValue());
          var template =
              s.one(
                  "select * from curriculum_templates where school_id=? and id=? and state='PUBLISHED'",
                  school,
                  b.curriculumId());
          if (!c.get("session_id").equals(template.get("session_id"))
              || !c.get("level").equals(template.get("level")))
            throw fail(
                422,
                "CURRICULUM_CLASS_MISMATCH",
                "Use a published template for this session and level.");
          if (s.db.queryForObject(
                  "select count(*) from assessment_cycles where class_id=?", Integer.class, classId)
              > 0)
            throw fail(
                409,
                "ASSESSMENT_SCOPE_EXISTS",
                "Review curriculum changes before preparing assessment cycles.");
          var rules = s.json.tree(template.get("definition").toString());
          Set<UUID> subjects = new HashSet<>();
          for (var r : rules) {
            UUID subject = UUID.fromString(r.path("subjectId").asText());
            subjects.add(subject);
            if (s.db.queryForObject(
                    "select count(*) from teaching_assignments where class_id=? and subject_id=? and active and department is distinct from ?::text and (department is null or ?::text is null)",
                    Integer.class,
                    classId,
                    subject,
                    template.get("department"),
                    template.get("department"))
                > 0)
              throw fail(
                  409,
                  "SUBJECT_SCOPE_OVERLAP",
                  "A common subject cannot also have a separate department assignment. Review its existing scope first.");
            UUID teacher = b.teachers() == null ? null : b.teachers().get(subject);
            var existing =
                s.db.queryForList(
                    "select * from teaching_assignments where class_id=? and subject_id=? and department is not distinct from ?::text",
                    classId,
                    subject,
                    template.get("department"));
            if (teacher == null && !existing.isEmpty())
              teacher = (UUID) existing.get(0).get("teacher_id");
            if (teacher == null) {
              var options =
                  s.db.queryForList(
                      "select st.id from staff st join staff_qualifications q on q.staff_id=st.id where st.school_id=? and st.active and st.staff_role='TEACHER' and q.subject_id=? and q.level=?",
                      school,
                      subject,
                      c.get("level"));
              if (options.size() != 1)
                throw fail(
                    422,
                    "TEACHER_SELECTION_REQUIRED",
                    "Select a qualified teacher where automatic matching has zero or multiple candidates.");
              teacher = (UUID) options.get(0).get("id");
            }
            if (s.db.queryForObject(
                    "select count(*) from staff st join staff_qualifications q on q.staff_id=st.id where st.school_id=? and st.id=? and st.active and st.staff_role='TEACHER' and q.subject_id=? and q.level=?",
                    Integer.class,
                    school,
                    teacher,
                    subject,
                    c.get("level"))
                == 0)
              throw fail(
                  422,
                  "TEACHER_NOT_ELIGIBLE",
                  "Choose an active teacher qualified for this subject and level.");
            if (existing.isEmpty())
              s.db.update(
                  "insert into teaching_assignments(id,school_id,class_id,subject_id,teacher_id,department,weekly_periods,required) values (?,?,?,?,?,?,?,?)",
                  UUID.randomUUID(),
                  school,
                  classId,
                  subject,
                  teacher,
                  template.get("department"),
                  r.path("weeklyPeriods").asInt(),
                  r.path("required").asBoolean());
            else {
              var old = existing.get(0);
              if (!old.get("teacher_id").equals(teacher))
                throw fail(
                    409,
                    "TEACHER_HANDOVER_REQUIRED",
                    "Use the reasoned teaching-handover action to replace an existing subject teacher.");
              s.db.update(
                  "update teaching_assignments set weekly_periods=?,required=?,active=true,version=version+1 where id=?",
                  r.path("weeklyPeriods").asInt(),
                  r.path("required").asBoolean(),
                  old.get("id"));
            }
          }
          for (var r : rules)
            s.db.update(
                "update teaching_assignments set choice_group=? where class_id=? and subject_id=? and department is not distinct from ?::text",
                r.path("choiceGroup").isTextual() ? r.path("choiceGroup").asText() : null,
                classId,
                UUID.fromString(r.path("subjectId").asText()),
                template.get("department"));
          var old =
              s.db.queryForList(
                  "select id,subject_id from teaching_assignments where class_id=? and department is not distinct from ?::text and active",
                  classId,
                  template.get("department"));
          for (var t : old)
            if (!subjects.contains(t.get("subject_id")))
              s.db.update(
                  "update teaching_assignments set active=false,version=version+1 where id=?",
                  t.get("id"));
          s.db.update(
              "insert into class_curricula(school_id,class_id,curriculum_id,department) values (?,?,?,?) on conflict(class_id,department) do update set curriculum_id=excluded.curriculum_id",
              school,
              classId,
              b.curriculumId(),
              Objects.toString(template.get("department"), ""));
          s.db.update(
              "update classes set curriculum_id=case when ?::text is null then ? else curriculum_id end,version=version+1 where id=?",
              template.get("department"),
              b.curriculumId(),
              classId);
          s.commands.audit(
              school,
              a.accountId(),
              "CLASS_CURRICULUM_APPLIED",
              "CLASS",
              classId,
              text(b.reason(), 5, 500, "Reason"),
              map("curriculumId", b.curriculumId(), "timetableRevisionRequired", true));
          return map("classId", classId, "version", version + 1, "timetableRevisionRequired", true);
        });
  }

  public record Choices(UUID sessionId, List<UUID> subjectIds, String reason) {}

  public Object choices(Actor a, UUID school, UUID student, UUID session) {
    s.learning(a, school, student);
    var e =
        s.one(
            "select e.*,coalesce((select cc.curriculum_id from class_curricula cc where cc.class_id=c.id and cc.department=coalesce(e.department,'')),c.curriculum_id) as curriculum_id from enrolments e join classes c on c.id=e.class_id where e.school_id=? and e.student_id=? and e.session_id=? order by e.starts_on desc limit 1",
            school,
            student,
            session);
    var versions =
        s.db.queryForList(
            "select version from student_choice_versions where student_id=? and session_id=?",
            student,
            session);
    return map(
        "studentId",
        student,
        "sessionId",
        session,
        "version",
        versions.isEmpty() ? 1 : versions.get(0).get("version"),
        "curriculumId",
        e.get("curriculum_id"),
        "subjectIds",
        s.db.queryForList(
            "select subject_id as id from student_subject_choices where student_id=? and session_id=?",
            student,
            session));
  }

  public Object choose(Actor a, UUID school, UUID student, Choices b, long version, String key) {
    return command(
        a,
        school,
        "students/" + student + "/subject-choices",
        key,
        map("input", b, "version", version),
        () -> {
          var e =
              s.one(
                  "select e.*,coalesce((select cc.curriculum_id from class_curricula cc where cc.class_id=c.id and cc.department=coalesce(e.department,'')),c.curriculum_id) as curriculum_id from enrolments e join classes c on c.id=e.class_id where e.school_id=? and e.student_id=? and e.session_id=? order by e.starts_on desc limit 1 for update of e",
                  school,
                  student,
                  b.sessionId());
          s.db.update(
              "insert into student_choice_versions(school_id,student_id,session_id) values (?,?,?) on conflict do nothing",
              school,
              student,
              b.sessionId());
          var v =
              s.one(
                  "select version from student_choice_versions where student_id=? and session_id=? for update",
                  student,
                  b.sessionId());
          expectVersion(version, ((Number) v.get("version")).longValue());
          if (s.db.queryForObject(
                  "select count(*) from assessment_roster ar join assessment_cycles ac on ac.id=ar.cycle_id join terms t on t.id=ac.term_id where ar.student_id=? and t.session_id=?",
                  Integer.class,
                  student,
                  b.sessionId())
              > 0)
            throw fail(
                409,
                "ASSESSMENT_SCOPE_EXISTS",
                "Choices are frozen after assessment rosters are prepared; resolve the academic record with the principal.");
          if (e.get("curriculum_id") == null)
            throw fail(
                422,
                "CURRICULUM_REQUIRED",
                "Apply a published class curriculum before choosing alternatives.");
          var selected = new HashSet<>(b.subjectIds() == null ? List.<UUID>of() : b.subjectIds());
          s.db.update(
              "delete from student_subject_choices where student_id=? and session_id=?",
              student,
              b.sessionId());
          s.enrolChoices(
              school,
              student,
              b.sessionId(),
              (UUID) e.get("class_id"),
              (String) e.get("department"),
              b.subjectIds());
          s.db.update(
              "update student_choice_versions set version=version+1 where student_id=? and session_id=?",
              student,
              b.sessionId());
          s.commands.audit(
              school,
              a.accountId(),
              "STUDENT_SUBJECT_CHOICES_UPDATED",
              "STUDENT",
              student,
              text(b.reason(), 5, 500, "Reason"),
              map("subjectIds", selected, "sessionId", b.sessionId()));
          return choices(a, school, student, b.sessionId());
        });
  }

  public record Availability(int weekday, int startMinute, int endMinute, String reason) {}

  public record TeacherScope(
      List<String> eligibleLevels,
      List<UUID> subjectIds,
      List<Availability> unavailable,
      String reason) {}

  public Object teacherScope(
      Actor a, UUID school, UUID teacher, TeacherScope b, long version, String key) {
    return command(
        a,
        school,
        "staff/" + teacher + "/teaching-scope",
        key,
        map("input", b, "version", version),
        () -> {
          var st =
              s.one(
                  "select * from staff where school_id=? and id=? and staff_role='TEACHER' for update",
                  school,
                  teacher);
          expectVersion(version, ((Number) st.get("version")).longValue());
          if (b.eligibleLevels() == null
              || b.eligibleLevels().isEmpty()
              || !Set.of("JSS1", "JSS2", "JSS3", "SSS1", "SSS2", "SSS3")
                  .containsAll(b.eligibleLevels())
              || b.subjectIds() == null
              || b.subjectIds().isEmpty()
              || b.subjectIds().size() > 25
              || (b.unavailable() != null && b.unavailable().size() > 25))
            throw fail(
                422,
                "INVALID_TEACHING_SCOPE",
                "Provide eligible secondary levels, subjects and at most 25 unavailable periods.");
          var work =
              s.db.queryForList(
                  "select t.subject_id,c.level from teaching_assignments t join classes c on c.id=t.class_id join academic_sessions y on y.id=c.session_id where t.school_id=? and t.teacher_id=? and t.active and y.ends_on>=current_date",
                  school,
                  teacher);
          for (var t : work)
            if (!b.subjectIds().contains(t.get("subject_id"))
                || !b.eligibleLevels().contains(t.get("level")))
              throw fail(
                  409,
                  "ACTIVE_WORK_HANDOVER_REQUIRED",
                  "Reassign active work before removing its subject or level eligibility.");
          s.db.update("delete from staff_qualifications where staff_id=?", teacher);
          for (UUID sub : new HashSet<>(b.subjectIds())) {
            s.one("select id from subjects where school_id=? and id=?", school, sub);
            for (String level : new HashSet<>(b.eligibleLevels()))
              s.db.update(
                  "insert into staff_qualifications(school_id,staff_id,subject_id,level) values (?,?,?,?)",
                  school,
                  teacher,
                  sub,
                  level);
          }
          s.db.update("delete from teacher_availability where teacher_id=?", teacher);
          for (var t : b.unavailable() == null ? List.<Availability>of() : b.unavailable()) {
            if (t.weekday() < 1
                || t.weekday() > 5
                || t.startMinute() < 480
                || t.endMinute() > 780
                || t.startMinute() >= t.endMinute())
              throw fail(422, "INVALID_AVAILABILITY", "Use weekday periods within 08:00–13:00.");
            s.db.update(
                "insert into teacher_availability(school_id,teacher_id,weekday,start_min,end_min,reason) values (?,?,?,?,?,?)",
                school,
                teacher,
                t.weekday(),
                t.startMinute(),
                t.endMinute(),
                text(t.reason(), 5, 500, "Unavailability reason"));
          }
          s.db.update("update staff set version=version+1 where id=?", teacher);
          s.commands.audit(
              school,
              a.accountId(),
              "TEACHING_SCOPE_UPDATED",
              "STAFF",
              teacher,
              text(b.reason(), 5, 500, "Reason"),
              map("beforeVersion", version));
          return map("id", teacher, "version", version + 1, "timetableReviewRequired", true);
        });
  }

  public record CalendarChange(
      LocalDate onDate, String title, boolean teachingDay, String reason) {}

  public record CalendarInput(UUID sessionId, List<CalendarChange> changes) {}

  public Object calendar(Actor a, UUID school, UUID session) {
    a.school(school);
    s.one("select id from academic_sessions where school_id=? and id=?", school, session);
    return map(
        "sessionId",
        session,
        "revision",
        s.school(school).get("calendar_revision"),
        "terms",
        s.db.queryForList(
            "select id,number,starts_on as \"startsOn\",ends_on as \"endsOn\",version from terms where school_id=? and session_id=? order by number",
            school,
            session),
        "exceptions",
        s.db.queryForList(
            "select e.id,e.on_date as \"onDate\",e.title,e.teaching_day as \"teachingDay\" from calendar_exceptions e join academic_sessions y on y.id=? and y.school_id=e.school_id where e.school_id=? and e.on_date between y.starts_on and y.ends_on order by e.on_date",
            session,
            school),
        "timezone",
        "Africa/Lagos",
        "dailyBreak",
        map("start", "11:30", "end", "12:00"),
        "schoolEndsAt",
        "13:00");
  }

  public Object draftCalendar(Actor a, UUID school, CalendarInput b, String key) {
    return command(
        a,
        school,
        "calendar-plans",
        key,
        b,
        () -> {
          var y =
              s.one(
                  "select * from academic_sessions where school_id=? and id=?",
                  school,
                  b.sessionId());
          if (b.changes() == null || b.changes().isEmpty() || b.changes().size() > 80)
            throw fail(
                422,
                "INVALID_CALENDAR_CHANGES",
                "Supply one to 80 dated holidays, reopening days or special school days.");
          Set<LocalDate> dates = new HashSet<>();
          for (var d : b.changes()) {
            if (d.onDate() == null
                || !dates.add(d.onDate())
                || d.onDate().isBefore(((java.sql.Date) y.get("starts_on")).toLocalDate())
                || d.onDate().isAfter(((java.sql.Date) y.get("ends_on")).toLocalDate()))
              throw fail(
                  422, "INVALID_CALENDAR_DATE", "Use distinct dates within this school session.");
            text(d.title(), 2, 120, "Calendar title");
            text(d.reason(), 5, 500, "Reason");
          }
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into calendar_plans(id,school_id,session_id,changes,created_by) values (?,?,?,?::jsonb,?)",
              id,
              school,
              b.sessionId(),
              s.json.write(b.changes()),
              a.accountId());
          return map("id", id, "state", "DRAFT", "version", 1, "changes", b.changes());
        });
  }

  public Object calendarPlans(Actor a, UUID school, UUID session) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var rows =
        s.db.queryForList(
            "select * from calendar_plans where school_id=? and session_id=? order by published_at desc nulls first,id",
            school,
            session);
    for (var r : rows) r.put("changes", s.json.value(r.get("changes").toString()));
    return map("items", rows, "hasMore", false);
  }

  public Object publishCalendar(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "calendar-plans/" + id + "/publish",
        key,
        map("version", version),
        () -> {
          var p =
              s.one(
                  "select * from calendar_plans where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) p.get("version")).longValue());
          if (!"DRAFT".equals(p.get("state")))
            throw fail(409, "ALREADY_PUBLISHED", "Create a new calendar revision for corrections.");
          s.one("select id from schools where id=? for update", school);
          for (var n : s.json.tree(p.get("changes").toString())) {
            LocalDate d = LocalDate.parse(n.path("onDate").asText());
            boolean teaching = n.path("teachingDay").asBoolean();
            if (!teaching
                && s.db.queryForObject(
                        "select count(*) from attendance_registers where school_id=? and on_date=? and state='SUBMITTED'",
                        Integer.class,
                        school,
                        d)
                    > 0)
              throw fail(
                  409,
                  "SUBMITTED_REGISTER_ON_DATE",
                  "Correct submitted attendance before closing this teaching day.");
            if (!teaching
                && s.db.queryForObject(
                        "select count(*) from exam_events e join exam_schedules x on x.id=e.schedule_id where e.school_id=? and e.on_date=? and x.state='PUBLISHED' and not exists(select 1 from exam_schedules newer where newer.term_id=x.term_id and newer.state='PUBLISHED' and newer.revision>x.revision)",
                        Integer.class,
                        school,
                        d)
                    > 0)
              throw fail(
                  409,
                  "PUBLISHED_EXAM_ON_DATE",
                  "Revise published examinations before closing this day.");
            s.db.update(
                "insert into calendar_exceptions(id,school_id,on_date,title,teaching_day,reason) values (?,?,?,?,?,?) on conflict(school_id,on_date) do update set title=excluded.title,teaching_day=excluded.teaching_day,reason=excluded.reason",
                UUID.randomUUID(),
                school,
                d,
                n.path("title").asText(),
                teaching,
                n.path("reason").asText());
          }
          s.db.update(
              "update schools set calendar_revision=calendar_revision+1 where id=?", school);
          s.db.update(
              "update calendar_plans set state='PUBLISHED',published_at=now(),version=version+1 where id=?",
              id);
          s.commands.audit(
              school,
              a.accountId(),
              "SCHOOL_CALENDAR_PUBLISHED",
              "CALENDAR",
              id,
              null,
              map("sessionId", p.get("session_id")));
          s.outbox.add(
              "communication",
              "SCHOOL_NOTIFICATION",
              map(
                  "schoolId",
                  school,
                  "title",
                  "School calendar updated",
                  "safePreview",
                  "Your school published a calendar update. Open the app to view it."));
          return map(
              "id",
              id,
              "state",
              "PUBLISHED",
              "version",
              version + 1,
              "calendarRevision",
              s.school(school).get("calendar_revision"));
        });
  }
}
