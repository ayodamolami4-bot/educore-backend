package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AssignmentService {
  private final SchoolService s;
  private final FileService files;

  public AssignmentService(SchoolService s, FileService files) {
    this.s = s;
    this.files = files;
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

  private Map<String, Object> work(UUID school, UUID id, boolean lock) {
    return s.one(
        "select w.*,t.class_id,t.subject_id,t.teacher_id,t.department,t.required,c.attendance_teacher_id,t.active as teaching_active from learning_assignments w join teaching_assignments t on t.id=w.teaching_assignment_id join classes c on c.id=t.class_id where w.school_id=? and w.id=?"
            + (lock ? " for update of w" : ""),
        school,
        id);
  }

  private void owner(Actor a, UUID school, Map<String, Object> w) {
    a.school(school, "TEACHER");
    if (!a.context().subjectId().equals(w.get("teacher_id"))
        || !Boolean.TRUE.equals(w.get("teaching_active"))
        || s.db.queryForObject(
                "select count(*) from staff where school_id=? and id=? and active and staff_role='TEACHER'",
                Integer.class,
                school,
                a.context().subjectId())
            == 0) throw fail(404, "NOT_FOUND", "This teaching assignment is unavailable.");
  }

  private UUID learner(Actor a) {
    return "PARENT".equals(a.context().role()) ? a.context().childId() : a.context().subjectId();
  }

  private void eligible(UUID school, Map<String, Object> w, UUID student, boolean writing) {
    String date = "DRAFT".equals(w.get("state")) ? "current_date" : "?::date";
    List<Object> args = new ArrayList<>(Arrays.asList(school, student, w.get("class_id")));
    if (!"DRAFT".equals(w.get("state"))) args.add(w.get("published_at"));
    args.add(w.get("department"));
    args.add(w.get("department"));
    int enrolled =
        s.db.queryForObject(
            "select count(*) from enrolments e join students st on st.id=e.student_id where e.school_id=? and e.student_id=? and e.class_id=? and e.starts_on<=current_date and (e.ends_on is null or e.ends_on>="
                + date
                + ") and (?::text is null or e.department=?::text)"
                + (writing
                    ? " and st.status='ACTIVE' and (e.ends_on is null or e.ends_on>=current_date)"
                    : ""),
            Integer.class,
            args.toArray());
    if (enrolled == 0)
      throw fail(404, "NOT_FOUND", "This class assignment is unavailable to the student.");
    if (!Boolean.TRUE.equals(w.get("required"))
        && s.db.queryForObject(
                "select count(*) from student_subject_choices where student_id=? and session_id=(select session_id from terms where id=?) and subject_id=?",
                Integer.class,
                student,
                w.get("term_id"),
                w.get("subject_id"))
            == 0) throw fail(404, "NOT_FOUND", "This subject choice is unavailable.");
  }

  private void read(Actor a, UUID school, Map<String, Object> w) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "STUDENT", "PARENT");
    if (Set.of("PRINCIPAL", "SCHOOL_OFFICE").contains(c.role())) return;
    if ("TEACHER".equals(c.role())) {
      if (c.subjectId().equals(w.get("teacher_id"))
          || c.subjectId().equals(w.get("attendance_teacher_id"))) return;
      throw fail(404, "NOT_FOUND", "This assignment is unavailable.");
    }
    if (w.get("published_at") == null)
      throw fail(404, "NOT_FOUND", "This assignment has not been published.");
    UUID student = learner(a);
    s.learning(a, school, student);
    eligible(school, w, student, false);
  }

  public record Input(
      UUID teachingAssignmentId,
      UUID termId,
      String title,
      String instructions,
      Instant dueAt,
      BigDecimal maximumScore,
      List<UUID> fileIds) {}

  private void validate(Input b) {
    text(b.title(), 3, 160, "Assignment title");
    text(b.instructions(), 5, 8000, "Instructions");
    if (b.dueAt() == null
        || b.maximumScore() == null
        || b.maximumScore().signum() <= 0
        || b.maximumScore().compareTo(new BigDecimal("1000")) > 0
        || b.maximumScore().stripTrailingZeros().scale() > 1)
      throw fail(
          422,
          "INVALID_ASSIGNMENT",
          "Choose a future deadline and a maximum score from 0.1 to 1,000 with at most one decimal place.");
  }

  public Object create(Actor a, UUID school, Input b, String key) {
    a.school(school, "TEACHER");
    validate(b);
    var t =
        s.one(
            "select t.*,c.session_id from teaching_assignments t join classes c on c.id=t.class_id join staff st on st.id=t.teacher_id where t.school_id=? and t.id=? and t.active and st.active and st.staff_role='TEACHER'",
            school,
            b.teachingAssignmentId());
    if (!a.context().subjectId().equals(t.get("teacher_id")))
      throw fail(404, "NOT_FOUND", "This teaching scope is unavailable.");
    var term = s.one("select session_id from terms where school_id=? and id=?", school, b.termId());
    if (!term.get("session_id").equals(t.get("session_id")))
      throw fail(422, "INVALID_TERM", "Choose a term from the assignment's academic session.");
    return command(
        a,
        school,
        "assignments",
        key,
        b,
        () -> {
          futureDeadline(b.dueAt());
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into learning_assignments(id,school_id,teaching_assignment_id,term_id,title,instructions,due_at,maximum_score,created_by) values (?,?,?,?,?,?,?,?,?)",
              id,
              school,
              b.teachingAssignmentId(),
              b.termId(),
              b.title().strip(),
              b.instructions().strip(),
              java.sql.Timestamp.from(b.dueAt()),
              b.maximumScore(),
              a.accountId());
          files.attach(a, school, b.fileIds(), "ASSIGNMENT", "ASSIGNMENT", id, null);
          s.commands.audit(
              school, a.accountId(), "ASSIGNMENT_DRAFT_CREATED", "ASSIGNMENT", id, null, Map.of());
          return detail(a, school, id);
        });
  }

  public Object edit(Actor a, UUID school, UUID id, Input b, long version, String key) {
    var old = work(school, id, false);
    owner(a, school, old);
    validate(b);
    return command(
        a,
        school,
        "assignments/" + id,
        key,
        map("input", b, "version", version),
        () -> {
          futureDeadline(b.dueAt());
          var w = work(school, id, true);
          expectVersion(version, ((Number) w.get("version")).longValue());
          if (!"DRAFT".equals(w.get("state")))
            throw fail(
                409,
                "ASSIGNMENT_PUBLISHED",
                "Published instructions are preserved. Use a reasoned deadline extension.");
          if (!b.teachingAssignmentId().equals(w.get("teaching_assignment_id"))
              || !b.termId().equals(w.get("term_id")))
            throw fail(
                422,
                "ASSIGNMENT_SCOPE_IMMUTABLE",
                "Create a new assignment to change its class, subject or term.");
          s.db.update(
              "update learning_assignments set title=?,instructions=?,due_at=?,maximum_score=?,version=version+1 where id=?",
              b.title().strip(),
              b.instructions().strip(),
              java.sql.Timestamp.from(b.dueAt()),
              b.maximumScore(),
              id);
          s.db.update(
              "update file_links set active=false where resource_type='ASSIGNMENT' and resource_id=?",
              id);
          files.attach(a, school, b.fileIds(), "ASSIGNMENT", "ASSIGNMENT", id, null);
          return detail(a, school, id);
        });
  }

  public Object publish(Actor a, UUID school, UUID id, long version, String key) {
    owner(a, school, work(school, id, false));
    return command(
        a,
        school,
        "assignments/" + id + "/publish",
        key,
        map("version", version),
        () -> {
          var w = work(school, id, true);
          expectVersion(version, ((Number) w.get("version")).longValue());
          if (!"DRAFT".equals(w.get("state"))
              || !((java.sql.Timestamp) w.get("due_at")).toInstant().isAfter(Instant.now()))
            throw fail(
                409,
                "ASSIGNMENT_NOT_PUBLISHABLE",
                "Review a draft with a future deadline before publishing.");
          s.db.update(
              "update learning_assignments set state='PUBLISHED',published_at=now(),version=version+1 where id=?",
              id);
          s.commands.audit(
              school, a.accountId(), "ASSIGNMENT_PUBLISHED", "ASSIGNMENT", id, null, Map.of());
          s.outbox.add(
              "communication",
              "SCHOOL_NOTIFICATION",
              map(
                  "schoolId",
                  school,
                  "title",
                  "School learning update",
                  "safePreview",
                  "Your school published a learning update. Open your class workspace for details."));
          return detail(a, school, id);
        });
  }

  public Object detail(Actor a, UUID school, UUID id) {
    var w = work(school, id, false);
    read(a, school, w);
    var result =
        map(
            "id",
            id,
            "teachingAssignmentId",
            w.get("teaching_assignment_id"),
            "classId",
            w.get("class_id"),
            "subjectId",
            w.get("subject_id"),
            "department",
            w.get("department"),
            "termId",
            w.get("term_id"),
            "title",
            w.get("title"),
            "instructions",
            w.get("instructions"),
            "dueAt",
            w.get("due_at"),
            "maximumScore",
            w.get("maximum_score"),
            "state",
            w.get("state"),
            "version",
            w.get("version"),
            "files",
            s.db.queryForList(
                "select f.id,f.filename,f.media_type as \"mediaType\",f.expected_size as size from file_links l join school_files f on f.id=l.file_id where l.resource_type='ASSIGNMENT' and l.resource_id=? and l.active",
                id));
    if (Set.of("PARENT", "STUDENT").contains(a.context().role()))
      result.put("submission", mySubmission(a, school, id, learner(a), false));
    return result;
  }

  private void futureDeadline(Instant deadline) {
    if (deadline == null || !deadline.isAfter(Instant.now()))
      throw fail(422, "FUTURE_DEADLINE_REQUIRED", "Choose a future submission deadline.");
  }

  public record Change(String action, Instant dueAt, String reason) {}

  public Object change(Actor a, UUID school, UUID id, Change b, long version, String key) {
    owner(a, school, work(school, id, false));
    String reason = text(b.reason(), 5, 500, "Change reason");
    return command(
        a,
        school,
        "assignments/" + id + "/changes",
        key,
        map("input", b, "version", version),
        () -> {
          var w = work(school, id, true);
          expectVersion(version, ((Number) w.get("version")).longValue());
          String action = Objects.toString(b.action(), "");
          if ("EXTEND".equals(action)) {
            futureDeadline(b.dueAt());
            if (!Set.of("PUBLISHED", "CLOSED").contains(w.get("state"))
                || !b.dueAt().isAfter(((java.sql.Timestamp) w.get("due_at")).toInstant()))
              throw fail(
                  409,
                  "DEADLINE_NOT_EXTENDED",
                  "Extend the deadline of published work to a later time.");
            s.db.update(
                "update learning_assignments set due_at=?,state='PUBLISHED',version=version+1 where id=?",
                java.sql.Timestamp.from(b.dueAt()),
                id);
          } else if ("CLOSE".equals(action)) {
            if (!"PUBLISHED".equals(w.get("state")))
              throw fail(409, "ASSIGNMENT_NOT_OPEN", "Only published work can be closed.");
            s.db.update(
                "update learning_assignments set state='CLOSED',version=version+1 where id=?", id);
          } else if ("ARCHIVE".equals(action)) {
            if (!Set.of("DRAFT", "CLOSED").contains(w.get("state")))
              throw fail(409, "ASSIGNMENT_NOT_CLOSED", "Close published work before archiving it.");
            s.db.update(
                "update learning_assignments set state='ARCHIVED',version=version+1 where id=?",
                id);
          } else throw fail(422, "INVALID_ASSIGNMENT_CHANGE", "Choose EXTEND, CLOSE or ARCHIVE.");
          s.commands.audit(
              school,
              a.accountId(),
              "ASSIGNMENT_" + action,
              "ASSIGNMENT",
              id,
              reason,
              map(
                  "beforeVersion",
                  version,
                  "previousDeadline",
                  w.get("due_at"),
                  "newDeadline",
                  b.dueAt()));
          return detail(a, school, id);
        });
  }

  public Object list(Actor a, UUID school, UUID classId, UUID term, UUID cursor, int limit) {
    var c = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER", "STUDENT", "PARENT");
    UUID teacher = "TEACHER".equals(c.role()) ? c.subjectId() : null;
    UUID student = Set.of("PARENT", "STUDENT").contains(c.role()) ? learner(a) : null;
    if (student != null) s.learning(a, school, student);
    var rows =
        s.db.queryForList(
            "select w.id from learning_assignments w join teaching_assignments t on t.id=w.teaching_assignment_id join classes c on c.id=t.class_id join terms term on term.id=w.term_id where w.school_id=? and (?::uuid is null or t.class_id=?::uuid) and (?::uuid is null or w.term_id=?::uuid) and (?::uuid is null or t.teacher_id=?::uuid or c.attendance_teacher_id=?::uuid) and (?::uuid is null or (w.published_at is not null and exists(select 1 from enrolments e where e.student_id=?::uuid and e.class_id=t.class_id and e.starts_on<=current_date and (e.ends_on is null or e.ends_on>=w.published_at::date) and (t.department is null or t.department=e.department)) and (t.required or exists(select 1 from student_subject_choices sc where sc.student_id=?::uuid and sc.session_id=term.session_id and sc.subject_id=t.subject_id)))) and (?::uuid is null or (w.due_at,w.id)>(select due_at,id from learning_assignments where id=?::uuid and school_id=?)) order by w.due_at,w.id limit ?",
            school,
            classId,
            classId,
            term,
            term,
            teacher,
            teacher,
            teacher,
            student,
            student,
            student,
            cursor,
            cursor,
            school,
            limit + 1);
    List<Map<String, Object>> items = new ArrayList<>();
    for (var row : rows) {
      @SuppressWarnings("unchecked")
      var item = (Map<String, Object>) detail(a, school, (UUID) row.get("id"));
      items.add(item);
    }
    return page(items, limit, item -> item.get("id").toString());
  }

  private Map<String, Object> submission(
      UUID school, UUID assignment, UUID student, boolean create) {
    var rows =
        s.db.queryForList(
            "select * from assignment_submissions where school_id=? and assignment_id=? and student_id=?",
            school,
            assignment,
            student);
    if (rows.isEmpty() && create) {
      UUID id = UUID.randomUUID();
      s.db.update(
          "insert into assignment_submissions(id,school_id,assignment_id,student_id) values (?,?,?,?) on conflict(assignment_id,student_id) do nothing",
          id,
          school,
          assignment,
          student);
      return s.one(
          "select * from assignment_submissions where school_id=? and assignment_id=? and student_id=?",
          school,
          assignment,
          student);
    }
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Object mySubmission(Actor a, UUID school, UUID assignment, UUID student, boolean create) {
    var sub = submission(school, assignment, student, create);
    if (sub == null) return map("state", "NOT_STARTED", "version", 1);
    if (!"STUDENT".equals(a.context().role())
        && Set.of("DRAFT", "RETURNED").contains(sub.get("state"))) {
      var accepted =
          s.db.queryForList(
              "select revision,snapshot from submission_revisions where submission_id=? order by revision desc limit 1",
              sub.get("id"));
      return map(
          "id",
          sub.get("id"),
          "studentId",
          student,
          "state",
          sub.get("state"),
          "version",
          sub.get("version"),
          "feedback",
          sub.get("feedback"),
          "extraDeadline",
          sub.get("extra_deadline"),
          "draftContentVisible",
          false,
          "acceptedRevision",
          accepted.isEmpty() ? null : accepted.get(0).get("revision"),
          "acceptedResponse",
          accepted.isEmpty() ? null : s.json.value(accepted.get(0).get("snapshot").toString()));
    }
    return map(
        "id",
        sub.get("id"),
        "studentId",
        student,
        "responseText",
        sub.get("response_text"),
        "state",
        sub.get("state"),
        "version",
        sub.get("version"),
        "submittedAt",
        sub.get("submitted_at"),
        "extraDeadline",
        sub.get("extra_deadline"),
        "score",
        sub.get("score"),
        "feedback",
        sub.get("feedback"),
        "files",
        s.db.queryForList(
            "select file_id as id from file_links where resource_type='SUBMISSION' and resource_id=? and active",
            sub.get("id")));
  }

  private void submissionScope(Actor a, UUID school, Map<String, Object> w, UUID student) {
    read(a, school, w);
    if (Set.of("PARENT", "STUDENT").contains(a.context().role()) && !student.equals(learner(a)))
      throw fail(404, "NOT_FOUND", "This submission is unavailable.");
    eligible(school, w, student, false);
  }

  public Object getSubmission(Actor a, UUID school, UUID id, UUID student) {
    var w = work(school, id, false);
    submissionScope(a, school, w, student);
    return mySubmission(a, school, id, student, false);
  }

  public record Draft(String responseText, List<UUID> fileIds) {}

  private void writable(Map<String, Object> w, Map<String, Object> sub) {
    if (!Set.of("PUBLISHED", "CLOSED").contains(w.get("state")))
      throw fail(409, "ASSIGNMENT_NOT_OPEN", "Only published work accepts responses.");
    if ("CLOSED".equals(w.get("state")) && sub.get("extra_deadline") == null)
      throw fail(409, "ASSIGNMENT_CLOSED", "Your teacher has closed this assignment.");
    Instant deadline =
        ((java.sql.Timestamp)
                (sub.get("extra_deadline") != null ? sub.get("extra_deadline") : w.get("due_at")))
            .toInstant();
    if (!deadline.isAfter(Instant.now()))
      throw fail(
          409,
          "SUBMISSION_DEADLINE_PASSED",
          "Ask your teacher for an explicit extension before changing this response.");
    if (!Set.of("DRAFT", "RETURNED").contains(sub.get("state")))
      throw fail(
          409, "SUBMISSION_LOCKED", "Submitted work is preserved until the teacher returns it.");
  }

  public Object save(Actor a, UUID school, UUID id, Draft b, long version, String key) {
    a.school(school, "STUDENT");
    UUID student = a.context().subjectId();
    var before = work(school, id, false);
    read(a, school, before);
    eligible(school, before, student, true);
    return command(
        a,
        school,
        "assignments/" + id + "/my-submission",
        key,
        map("input", b, "version", version),
        () -> {
          var w = work(school, id, true);
          var sub = submission(school, id, student, true);
          expectVersion(version, ((Number) sub.get("version")).longValue());
          writable(w, sub);
          String response = b.responseText() == null ? "" : b.responseText();
          if (response.length() > 8000)
            throw fail(422, "RESPONSE_TOO_LONG", "Use at most 8,000 characters.");
          UUID subId = (UUID) sub.get("id");
          s.db.update(
              "update file_links set active=false where resource_type='SUBMISSION' and resource_id=?",
              subId);
          files.attach(a, school, b.fileIds(), "SUBMISSION", "SUBMISSION", subId, student);
          s.db.update(
              "update assignment_submissions set response_text=?,state='DRAFT',version=version+1 where id=?",
              response,
              subId);
          return mySubmission(a, school, id, student, false);
        });
  }

  public Object submit(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "STUDENT");
    UUID student = a.context().subjectId();
    var before = work(school, id, false);
    read(a, school, before);
    eligible(school, before, student, true);
    return command(
        a,
        school,
        "assignments/" + id + "/submit",
        key,
        map("version", version),
        () -> {
          var w = work(school, id, true);
          var sub = submission(school, id, student, false);
          if (sub == null)
            throw fail(422, "SUBMISSION_EMPTY", "Save a response or attachment first.");
          expectVersion(version, ((Number) sub.get("version")).longValue());
          writable(w, sub);
          if (sub.get("response_text").toString().isBlank()
              && s.db.queryForObject(
                      "select count(*) from file_links where resource_type='SUBMISSION' and resource_id=? and active",
                      Integer.class,
                      sub.get("id"))
                  == 0)
            throw fail(422, "SUBMISSION_EMPTY", "Provide a response or an accepted attachment.");
          int revision =
              s.db.queryForObject(
                  "select coalesce(max(revision),0)+1 from submission_revisions where submission_id=?",
                  Integer.class,
                  sub.get("id"));
          s.db.update(
              "insert into submission_revisions(id,submission_id,revision,snapshot,submitted_by) values (?,?,?,?::jsonb,?)",
              UUID.randomUUID(),
              sub.get("id"),
              revision,
              s.json.write(mySubmission(a, school, id, student, false)),
              a.accountId());
          s.db.update(
              "update assignment_submissions set state='SUBMITTED',submitted_at=now(),score=null,feedback=null,version=version+1 where id=?",
              sub.get("id"));
          s.commands.audit(
              school,
              a.accountId(),
              "STUDENT_WORK_SUBMITTED",
              "SUBMISSION",
              (UUID) sub.get("id"),
              null,
              map("revision", revision, "assignmentId", id));
          return mySubmission(a, school, id, student, false);
        });
  }

  public Object submissions(Actor a, UUID school, UUID id) {
    var w = work(school, id, false);
    read(a, school, w);
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    return map(
        "items",
        s.db.queryForList(
            "select sub.id,sub.student_id as \"studentId\",st.full_name as \"studentName\",sub.state,sub.score,sub.version,sub.submitted_at as \"submittedAt\" from assignment_submissions sub join students st on st.id=sub.student_id where sub.school_id=? and sub.assignment_id=? order by st.full_name",
            school,
            id),
        "hasMore",
        false);
  }

  public record Feedback(
      String action, BigDecimal score, String feedback, String reason, Instant extraDeadline) {}

  public Object feedback(
      Actor a, UUID school, UUID id, UUID student, Feedback b, long version, String key) {
    var before = work(school, id, false);
    owner(a, school, before);
    eligible(school, before, student, false);
    return command(
        a,
        school,
        "assignments/" + id + "/submissions/" + student + "/feedback",
        key,
        map("input", b, "version", version),
        () -> {
          var w = work(school, id, true);
          var sub = submission(school, id, student, false);
          if (sub == null) throw fail(404, "NOT_FOUND", "The submission is unavailable.");
          expectVersion(version, ((Number) sub.get("version")).longValue());
          if (!Set.of("SUBMITTED", "GRADED").contains(sub.get("state")))
            throw fail(
                409,
                "WORK_NOT_SUBMITTED",
                "Review accepted student work before returning or grading it.");
          String action = Objects.toString(b.action(), "");
          if (!Set.of("GRADE", "RETURN").contains(action))
            throw fail(422, "INVALID_REVIEW_ACTION", "Choose GRADE or RETURN.");
          String feedback = text(b.feedback(), 2, 3000, "Feedback");
          String reason = b.reason() == null ? null : text(b.reason(), 5, 500, "Review reason");
          BigDecimal score = b.score();
          if ("GRADE".equals(action)) {
            if (score == null
                || score.signum() < 0
                || score.compareTo((BigDecimal) w.get("maximum_score")) > 0
                || score.stripTrailingZeros().scale() > 1)
              throw fail(
                  422,
                  "INVALID_SCORE",
                  "Use a score within the assignment maximum, with at most one decimal place.");
            if ("GRADED".equals(sub.get("state")) && reason == null)
              throw fail(
                  422, "GRADE_CORRECTION_REASON_REQUIRED", "A grade correction needs a reason.");
          } else {
            score = null;
            if (reason == null
                || b.extraDeadline() == null
                || !b.extraDeadline().isAfter(Instant.now()))
              throw fail(
                  422,
                  "RETURN_DEADLINE_REQUIRED",
                  "A returned submission needs a reason and a future resubmission deadline.");
          }
          int revision =
              s.db.queryForObject(
                  "select max(revision) from submission_revisions where submission_id=?",
                  Integer.class,
                  sub.get("id"));
          s.db.update(
              "insert into submission_reviews(id,submission_id,submission_revision,action,score,feedback,reason,author_id) values (?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              sub.get("id"),
              revision,
              action,
              score,
              feedback,
              reason,
              a.accountId());
          s.db.update(
              "update assignment_submissions set state=?,score=?,feedback=?,feedback_by=?,feedback_at=now(),extra_deadline=coalesce(?,extra_deadline),version=version+1 where id=?",
              "GRADE".equals(action) ? "GRADED" : "RETURNED",
              score,
              feedback,
              a.accountId(),
              b.extraDeadline() == null ? null : java.sql.Timestamp.from(b.extraDeadline()),
              sub.get("id"));
          s.commands.audit(
              school,
              a.accountId(),
              "ASSIGNMENT_FEEDBACK_RECORDED",
              "SUBMISSION",
              (UUID) sub.get("id"),
              reason,
              map("action", action, "revision", revision));
          s.outbox.add(
              "communication",
              "CHILD_NOTIFICATION",
              map(
                  "schoolId",
                  school,
                  "childId",
                  student,
                  "category",
                  "LEARNING",
                  "title",
                  "Learning feedback available",
                  "safePreview",
                  "Your school posted learning feedback. Open the app to view it.",
                  "target",
                  map(
                      "screenId",
                      "PA01",
                      "schoolId",
                      school,
                      "childId",
                      student,
                      "resourceId",
                      id)));
          return mySubmission(a, school, id, student, false);
        });
  }

  public Object history(Actor a, UUID school, UUID id, UUID student) {
    var w = work(school, id, false);
    submissionScope(a, school, w, student);
    var sub = submission(school, id, student, false);
    if (sub == null) throw fail(404, "NOT_FOUND", "The submission is unavailable.");
    var revisions =
        s.db.queryForList(
            "select revision,snapshot,submitted_at as \"submittedAt\" from submission_revisions where submission_id=? order by revision",
            sub.get("id"));
    for (var r : revisions) r.put("snapshot", s.json.value(r.get("snapshot").toString()));
    return map(
        "revisions",
        revisions,
        "reviews",
        s.db.queryForList(
            "select submission_revision as \"submissionRevision\",action,score,feedback,reason,created_at as \"createdAt\" from submission_reviews where submission_id=? order by created_at",
            sub.get("id")));
  }
}
