package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TimetableService {
  private final SchoolService s;
  private final ExamService exams;

  public TimetableService(SchoolService s, ExamService exams) {
    this.exams = exams;
    this.s = s;
  }

  public record PlanInput(UUID termId, LocalDate effectiveFrom, UUID sourcePlanId) {}

  public record LessonInput(
      UUID assignmentId, int weekday, String start, int durationMinutes, String room) {}

  public Object create(Actor a, UUID school, PlanInput b, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "timetable-plans",
        key,
        b,
        () -> {
          var term =
              s.one(
                  "select * from terms where school_id=? and id=? for update", school, b.termId());
          LocalDate start = ((java.sql.Date) term.get("starts_on")).toLocalDate(),
              end = ((java.sql.Date) term.get("ends_on")).toLocalDate();
          if (b.effectiveFrom() == null
              || b.effectiveFrom().isBefore(start)
              || b.effectiveFrom().isAfter(end))
            throw fail(
                422,
                "INVALID_EFFECTIVE_DATE",
                "The timetable must become effective within its term.");
          UUID id = UUID.randomUUID();
          long revision =
              s.db.queryForObject(
                  "select coalesce(max(revision),0)+1 from timetable_plans where term_id=? and effective_from=?",
                  Long.class,
                  b.termId(),
                  b.effectiveFrom());
          s.db.update(
              "insert into timetable_plans(id,school_id,term_id,effective_from,wednesday_pt,revision,source_plan_id) values (?,?,?,?,?,?,?)",
              id,
              school,
              b.termId(),
              b.effectiveFrom(),
              s.school(school).get("wednesday_pt"),
              revision,
              b.sourcePlanId());
          if (b.sourcePlanId() != null) {
            s.one(
                "select p.id from timetable_plans p join terms t on t.id=p.term_id where p.school_id=? and p.id=? and p.status='PUBLISHED' and t.session_id=?",
                school,
                b.sourcePlanId(),
                term.get("session_id"));
            s.db.update(
                "insert into lessons(id,school_id,plan_id,assignment_id,class_id,teacher_id,subject_id,department,room,weekday,start_min,end_min,required,choice_group) select gen_random_uuid(),l.school_id,?,l.assignment_id,l.class_id,t.teacher_id,l.subject_id,l.department,l.room,l.weekday,l.start_min,l.end_min,t.required,t.choice_group from lessons l join teaching_assignments t on t.id=l.assignment_id where l.plan_id=? and t.active",
                id,
                b.sourcePlanId());
          }
          return map(
              "id",
              id,
              "version",
              1,
              "status",
              "DRAFT",
              "effectiveFrom",
              b.effectiveFrom(),
              "revision",
              revision);
        });
  }

  public Object addLesson(
      Actor a, UUID school, UUID plan, LessonInput b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "timetable-plans/" + plan + "/lessons",
        key,
        map("lesson", b, "version", version),
        () -> {
          var p =
              s.one(
                  "select p.*,t.session_id from timetable_plans p join terms t on t.id=p.term_id where p.school_id=? and p.id=? for update of p",
                  school,
                  plan);
          expectVersion(version, ((Number) p.get("version")).longValue());
          if (!"DRAFT".equals(p.get("status")))
            throw fail(
                409,
                "TIMETABLE_PUBLISHED",
                "Create a new timetable revision before changing published lessons.");
          return insertLesson(school, plan, b, p, null);
        });
  }

  private Object insertLesson(
      UUID school, UUID plan, LessonInput b, Map<String, Object> p, UUID requestedId) {
    var assignment =
        s.one(
            "select ta.*,c.session_id,st.active as teacher_active,ta.active as teaching_active from teaching_assignments ta join classes c on c.id=ta.class_id join staff st on st.id=ta.teacher_id where ta.school_id=? and ta.id=?",
            school,
            b.assignmentId());
    if (!p.get("session_id").equals(assignment.get("session_id"))
        || !Boolean.TRUE.equals(assignment.get("teacher_active"))
        || !Boolean.TRUE.equals(assignment.get("teaching_active")))
      throw fail(
          422,
          "INVALID_ASSIGNMENT",
          "The teaching assignment must be active in the timetable's academic session.");
    LocalTime start;
    try {
      start = LocalTime.parse(b.start());
    } catch (RuntimeException ex) {
      throw fail(422, "INVALID_TIME", "Use a time such as 08:30.");
    }
    int minute = start.getHour() * 60 + start.getMinute();
    if (start.getSecond() != 0 || start.getNano() != 0)
      throw fail(422, "INVALID_TIME", "Use whole-minute lesson times.");
    SchoolRules.lesson(
        b.weekday(), minute, b.durationMinutes(), Boolean.TRUE.equals(p.get("wednesday_pt")));
    if (s.db.queryForObject(
            "select count(*) from teacher_availability where school_id=? and teacher_id=? and weekday=? and start_min<? and end_min>?",
            Integer.class,
            school,
            assignment.get("teacher_id"),
            b.weekday(),
            minute + b.durationMinutes(),
            minute)
        > 0)
      throw fail(
          409, "TEACHER_UNAVAILABLE", "This teacher is unavailable during the proposed lesson.");
    String room = s.room(school, b.room());
    // The locked plan serialises common versus department class-overlap checks.
    var conflicts =
        s.db.queryForList(
            "select l.id from lessons l where l.plan_id=? and l.weekday=? and l.start_min<? and l.end_min>? and (l.teacher_id=? or (?::text is not null and l.room=?::text) or (l.class_id=? and (l.department is null or ?::text is null or l.department=?::text) and not (l.choice_group is not null and l.choice_group is not distinct from ?::text and l.subject_id<>?)))",
            plan,
            b.weekday(),
            minute + b.durationMinutes(),
            minute,
            assignment.get("teacher_id"),
            room,
            room,
            assignment.get("class_id"),
            assignment.get("department"),
            assignment.get("department"),
            assignment.get("choice_group"),
            assignment.get("subject_id"));
    if (!conflicts.isEmpty())
      throw fail(
          409, "TIMETABLE_CONFLICT", "The class, teacher or room overlaps an existing lesson.");
    UUID id = requestedId == null ? UUID.randomUUID() : requestedId;
    s.db.update(
        "insert into lessons(id,school_id,plan_id,assignment_id,class_id,teacher_id,subject_id,department,room,weekday,start_min,end_min,required,choice_group) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        school,
        plan,
        b.assignmentId(),
        assignment.get("class_id"),
        assignment.get("teacher_id"),
        assignment.get("subject_id"),
        assignment.get("department"),
        room,
        b.weekday(),
        minute,
        minute + b.durationMinutes(),
        assignment.get("required"),
        assignment.get("choice_group"));
    s.db.update("update timetable_plans set version=version+1 where id=?", plan);
    return map("id", id, "version", 1, "planVersion", ((Number) p.get("version")).longValue() + 1);
  }

  private Map<String, Object> draft(UUID school, UUID plan, long version) {
    var p =
        s.one(
            "select p.*,t.session_id from timetable_plans p join terms t on t.id=p.term_id where p.school_id=? and p.id=? for update of p",
            school,
            plan);
    expectVersion(version, ((Number) p.get("version")).longValue());
    if (!"DRAFT".equals(p.get("status")))
      throw fail(
          409,
          "TIMETABLE_PUBLISHED",
          "Create a new timetable revision before changing published lessons.");
    return p;
  }

  public Object plans(Actor a, UUID school, UUID term) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return map(
        "items",
        s.db.queryForList(
            "select id,term_id as \"termId\",status,effective_from as \"effectiveFrom\",revision,version,source_plan_id as \"sourcePlanId\" from timetable_plans where school_id=? and (?::uuid is null or term_id=?::uuid) order by effective_from desc,revision desc",
            school,
            term,
            term),
        "hasMore",
        false);
  }

  public Object removeLesson(
      Actor a, UUID school, UUID plan, UUID lesson, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "timetable-plans/" + plan + "/lessons/" + lesson + "/remove",
        key,
        map("version", version),
        () -> {
          draft(school, plan, version);
          s.one(
              "select id from lessons where school_id=? and plan_id=? and id=?",
              school,
              plan,
              lesson);
          s.db.update("delete from lessons where id=?", lesson);
          s.db.update("update timetable_plans set version=version+1 where id=?", plan);
          s.commands.audit(
              school,
              a.accountId(),
              "DRAFT_LESSON_REMOVED",
              "TIMETABLE",
              plan,
              null,
              map("lessonId", lesson));
          return map("planId", plan, "version", version + 1);
        });
  }

  public Object replaceLesson(
      Actor a, UUID school, UUID plan, UUID lesson, LessonInput b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "timetable-plans/" + plan + "/lessons/" + lesson,
        key,
        map("input", b, "version", version),
        () -> {
          var p = draft(school, plan, version);
          var old =
              s.one(
                  "select version from lessons where school_id=? and plan_id=? and id=?",
                  school,
                  plan,
                  lesson);
          s.db.update("delete from lessons where id=?", lesson);
          insertLesson(school, plan, b, p, lesson);
          s.db.update(
              "update lessons set version=? where id=?",
              ((Number) old.get("version")).longValue() + 1,
              lesson);
          s.commands.audit(
              school,
              a.accountId(),
              "DRAFT_LESSON_UPDATED",
              "TIMETABLE",
              plan,
              null,
              map("lessonId", lesson));
          return map(
              "id",
              lesson,
              "version",
              ((Number) old.get("version")).longValue() + 1,
              "planVersion",
              version + 1);
        });
  }

  public Object review(Actor a, UUID school, UUID plan) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var p =
        s.one(
            "select p.*,t.session_id from timetable_plans p join terms t on t.id=p.term_id where p.school_id=? and p.id=?",
            school,
            plan);
    var targets =
        s.db.queryForList(
            "select ta.id as \"assignmentId\",ta.class_id as \"classId\",ta.weekly_periods as \"requiredPeriods\",coalesce(sum(l.end_min-l.start_min)/30,0) as \"scheduledPeriods\" from teaching_assignments ta join classes c on c.id=ta.class_id left join lessons l on l.assignment_id=ta.id and l.plan_id=? where ta.school_id=? and c.session_id=? and ta.active group by ta.id",
            plan,
            school,
            p.get("session_id"));
    var invalid =
        s.db.queryForList(
            "select l.id as \"lessonId\",case when not t.active or not st.active or l.teacher_id<>t.teacher_id or l.required<>t.required or l.choice_group is distinct from t.choice_group then 'TEACHER_OR_ASSIGNMENT_CHANGED' else 'TEACHER_UNAVAILABLE' end as issue from lessons l join teaching_assignments t on t.id=l.assignment_id join staff st on st.id=t.teacher_id where l.plan_id=? and (not t.active or not st.active or l.teacher_id<>t.teacher_id or l.required<>t.required or l.choice_group is distinct from t.choice_group or exists(select 1 from teacher_availability av where av.teacher_id=l.teacher_id and av.weekday=l.weekday and av.start_min<l.end_min and av.end_min>l.start_min))",
            plan);
    for (var lesson : lessons(plan, null, null, null)) {
      try {
        SchoolRules.lesson(
            ((Number) lesson.get("weekday")).intValue(),
            ((Number) lesson.get("startMinute")).intValue(),
            ((Number) lesson.get("endMinute")).intValue()
                - ((Number) lesson.get("startMinute")).intValue(),
            Boolean.TRUE.equals(p.get("wednesday_pt")));
      } catch (Api.Failure ex) {
        invalid.add(
            map(
                "lessonId",
                lesson.get("id"),
                "issue",
                "FIXED_ACTIVITY_CONFLICT",
                "detail",
                ex.getMessage()));
      }
    }
    invalid.addAll(exams.conflictsForTimetable(school, p));
    boolean complete =
        invalid.isEmpty()
            && !targets.isEmpty()
            && targets.stream()
                .allMatch(
                    t ->
                        ((Number) t.get("requiredPeriods")).longValue()
                            == ((Number) t.get("scheduledPeriods")).longValue());
    return map(
        "id",
        plan,
        "version",
        p.get("version"),
        "status",
        p.get("status"),
        "effectiveFrom",
        p.get("effective_from"),
        "weeklyTargets",
        targets,
        "conflicts",
        invalid,
        "readyToPublish",
        complete,
        "lessons",
        lessons(plan, null, null, null));
  }

  public Object publish(Actor a, UUID school, UUID plan, long version, String key) {
    a.school(school, "PRINCIPAL");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "timetable-plans/" + plan + "/publish",
        key,
        map("version", version),
        () -> {
          var p =
              s.one(
                  "select * from timetable_plans where school_id=? and id=? for update",
                  school,
                  plan);
          s.one("select id from terms where id=? for update", p.get("term_id"));
          expectVersion(version, ((Number) p.get("version")).longValue());
          if (!"DRAFT".equals(p.get("status")))
            throw fail(409, "ALREADY_PUBLISHED", "This timetable is already published.");
          if (s.db.queryForObject(
                  "select count(*) from timetable_plans where term_id=? and effective_from=? and status='PUBLISHED' and revision>?",
                  Integer.class,
                  p.get("term_id"),
                  p.get("effective_from"),
                  p.get("revision"))
              > 0)
            throw fail(
                409,
                "SUPERSEDED_DRAFT",
                "A newer revision for this effective date is already published. Clone that revision instead.");
          @SuppressWarnings("unchecked")
          var review = (Map<String, Object>) review(a, school, plan);
          if (!Boolean.TRUE.equals(review.get("readyToPublish")))
            throw fail(
                422,
                "WEEKLY_TARGETS_INCOMPLETE",
                "Review and complete each subject's required weekly periods before publication.");
          s.db.update(
              "update timetable_plans set status='PUBLISHED',published_at=now(),version=version+1 where id=?",
              plan);
          s.commands.audit(
              school,
              a.accountId(),
              "TIMETABLE_PUBLISHED",
              "TIMETABLE",
              plan,
              null,
              map("effectiveFrom", p.get("effective_from")));
          s.outbox.add(
              "communication",
              "SCHOOL_NOTIFICATION",
              map(
                  "schoolId",
                  school,
                  "title",
                  "School timetable published",
                  "safePreview",
                  "Your school published an updated timetable. Open your workspace to view it."));
          return map(
              "id",
              plan,
              "version",
              version + 1,
              "status",
              "PUBLISHED",
              "effectiveFrom",
              p.get("effective_from"));
        });
  }

  private List<Map<String, Object>> lessons(
      UUID plan, UUID classId, UUID teacher, String department) {
    return lessons(plan, classId, teacher, department, null);
  }

  private List<Map<String, Object>> lessons(
      UUID plan, UUID classId, UUID teacher, String department, UUID student) {
    return s.db.queryForList(
        "select l.id,l.class_id as \"classId\",l.subject_id as \"subjectId\",su.name as \"subjectName\",l.teacher_id as \"teacherId\",st.full_name as \"teacherName\",l.weekday,l.start_min as \"startMinute\",l.end_min as \"endMinute\",l.department,l.required,l.choice_group as \"choiceGroup\",l.room,l.version from lessons l join subjects su on su.id=l.subject_id join staff st on st.id=l.teacher_id where l.plan_id=? and (?::uuid is null or l.class_id=?::uuid) and (?::uuid is null or l.teacher_id=?::uuid) and (?::text is null or l.department is null or l.department=?::text) and (?::uuid is null or l.required or exists(select 1 from student_subject_choices ch join terms term on term.session_id=ch.session_id join timetable_plans p on p.term_id=term.id where ch.student_id=?::uuid and ch.subject_id=l.subject_id and p.id=l.plan_id)) order by l.weekday,l.start_min,l.id",
        plan,
        classId,
        classId,
        teacher,
        teacher,
        department,
        department,
        student,
        student);
  }

  public Object published(Actor a, UUID school, UUID requestedClass, LocalDate on) {
    var context = a.school(school);
    if (on == null) on = LocalDate.now(ZoneId.of("Africa/Lagos"));
    UUID classId = requestedClass, teacher = null, learnerId = null;
    String department = null;
    switch (context.role()) {
      case "PARENT", "STUDENT" -> {
        UUID student = "PARENT".equals(context.role()) ? context.childId() : context.subjectId();
        s.learning(a, school, student);
        learnerId = student;
        var e =
            s.one(
                "select * from enrolments where school_id=? and student_id=? and starts_on<=? and (ends_on is null or ends_on>=?) order by starts_on desc limit 1",
                school,
                student,
                on,
                on);
        classId = (UUID) e.get("class_id");
        department = (String) e.get("department");
        if (requestedClass != null && !requestedClass.equals(classId))
          throw fail(404, "NOT_FOUND", "This class timetable is unavailable.");
      }
      case "TEACHER" -> teacher = context.subjectId();
      case "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS" -> {}
      default -> throw fail(403, "FORBIDDEN", "This role cannot view a timetable.");
    }
    var plans =
        s.db.queryForList(
            "select p.id,p.version,p.effective_from,p.wednesday_pt from timetable_plans p join terms t on t.id=p.term_id where p.school_id=? and p.status='PUBLISHED' and p.effective_from<=? and t.starts_on<=? and t.ends_on>=? order by p.effective_from desc,p.revision desc limit 1",
            school,
            on,
            on,
            on);
    if (plans.isEmpty()) return map("items", List.of(), "published", false, "on", on);
    var p = plans.get(0);
    return map(
        "items",
        lessons((UUID) p.get("id"), classId, teacher, department, learnerId),
        "published",
        true,
        "version",
        p.get("version"),
        "effectiveFrom",
        p.get("effective_from"),
        "timezone",
        "Africa/Lagos",
        "activities",
        List.of(
            map(
                "type",
                "BREAK",
                "weekdays",
                List.of(1, 2, 3, 4, 5),
                "start",
                "11:30",
                "end",
                "12:00"),
            map("type", "SPORTS", "weekdays", List.of(5), "start", "12:00", "end", "13:00")),
        "wednesdayPtEnabled",
        p.get("wednesday_pt"));
  }
}
