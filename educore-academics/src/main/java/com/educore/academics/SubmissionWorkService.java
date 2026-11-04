package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SubmissionWorkService {
  private final SchoolService s;
  private final AssignmentService work;
  private final LearningScope scope;
  private final FileService files;

  public SubmissionWorkService(
      SchoolService s, AssignmentService work, LearningScope scope, FileService files) {
    this.s = s;
    this.work = work;
    this.scope = scope;
    this.files = files;
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object b,
      java.util.function.Supplier<Object> action) {
    return s.commands.execute(a.accountId().toString(), school.toString(), path, key, b, action);
  }

  public record Physical(LocalDate receivedOn, String note, String reason, List<UUID> fileIds) {}

  public Object physical(
      Actor a, UUID school, UUID assignment, UUID student, Physical b, long version, String key) {
    work.owner(a, school, work.work(school, assignment, false));
    return command(
        a,
        school,
        "assignments/" + assignment + "/submissions/" + student + "/physical-receipt",
        key,
        map("input", b, "version", version),
        () -> {
          s.lockPlacements(school);
          var w = work.work(school, assignment, true);
          work.owner(a, school, w);
          if (!"PHYSICAL".equals(w.get("submission_mode"))
              || !Set.of("PUBLISHED", "CLOSED").contains(w.get("state")))
            throw fail(
                409,
                "NOT_PHYSICAL_ASSIGNMENT",
                "Only published physical assignments accept teacher receipt records.");
          LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos")),
              issued =
                  ((java.sql.Timestamp) w.get("published_at"))
                      .toInstant()
                      .atZone(ZoneId.of("Africa/Lagos"))
                      .toLocalDate();
          if (b.receivedOn() == null
              || b.receivedOn().isAfter(today)
              || b.receivedOn().isBefore(issued))
            throw fail(
                422,
                "INVALID_RECEIPT_DATE",
                "Record an actual receipt date from assignment publication through today.");
          scope.pupil(school, w, student, b.receivedOn());
          String reason = text(b.reason(), 5, 500, "Physical receipt evidence");
          String note = b.note() == null ? "" : b.note().strip();
          if (note.length() > 3000)
            throw fail(422, "RECEIPT_NOTE_TOO_LONG", "Use at most 3,000 characters.");
          var sub = work.submission(school, assignment, student, true);
          expectVersion(version, ((Number) sub.get("version")).longValue());
          if (!Set.of("DRAFT", "RETURNED").contains(sub.get("state")))
            throw fail(
                409,
                "PHYSICAL_RECEIPT_EXISTS",
                "This accepted receipt is retained. Return the work with a reason before recording a new receipt revision.");
          UUID id = (UUID) sub.get("id");
          s.db.update(
              "update file_links set active=false where resource_type='SUBMISSION' and resource_id=?",
              id);
          files.attach(a, school, b.fileIds(), "PHYSICAL_EVIDENCE", "SUBMISSION", id, student);
          s.db.update(
              "update assignment_submissions set channel='PHYSICAL',received_on=?,response_text=?,state='SUBMITTED',submitted_at=now(),score=null,feedback=null,version=version+1 where id=?",
              b.receivedOn(),
              note,
              id);
          int revision =
              s.db.queryForObject(
                  "select coalesce(max(revision),0)+1 from submission_revisions where submission_id=?",
                  Integer.class,
                  id);
          @SuppressWarnings("unchecked")
          var snapshot =
              new LinkedHashMap<>(
                  (Map<String, Object>) work.mySubmission(a, school, assignment, student, false));
          snapshot.put("receiptReason", reason);
          snapshot.put("recordedByTeacherId", a.context().subjectId());
          snapshot.put(
              "lateOnSchoolDate",
              b.receivedOn()
                  .isAfter(
                      ((java.sql.Timestamp)
                              (sub.get("extra_deadline") == null
                                  ? w.get("due_at")
                                  : sub.get("extra_deadline")))
                          .toInstant()
                          .atZone(ZoneId.of("Africa/Lagos"))
                          .toLocalDate()));
          s.db.update(
              "insert into submission_revisions(id,submission_id,revision,snapshot,submitted_by) values (?,?,?,?::jsonb,?)",
              UUID.randomUUID(),
              id,
              revision,
              s.json.write(snapshot),
              a.accountId());
          s.commands.audit(
              school,
              a.accountId(),
              "PHYSICAL_WORK_RECEIVED",
              "SUBMISSION",
              id,
              reason,
              map("receivedOn", b.receivedOn(), "revision", revision));
          return work.mySubmission(a, school, assignment, student, false);
        });
  }

  private Map<String, Object> draft(UUID submission, boolean lock) {
    var rows =
        s.db.queryForList(
            "select * from submission_review_drafts where submission_id=?"
                + (lock ? " for update" : ""),
            submission);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Object wire(Map<String, Object> row) {
    return row == null
        ? map("state", "NOT_STARTED", "version", 1)
        : map(
            "state",
            row.get("state"),
            "version",
            row.get("version"),
            "submissionVersion",
            row.get("source_version"),
            "submissionRevision",
            row.get("source_revision"),
            "review",
            s.json.value(row.get("payload").toString()),
            "updatedAt",
            row.get("updated_at"),
            "releasedAt",
            row.get("released_at"));
  }

  public Object getDraft(Actor a, UUID school, UUID assignment, UUID student) {
    var w = work.work(school, assignment, false);
    work.owner(a, school, w);
    var sub = work.submission(school, assignment, student, false);
    if (sub == null) throw fail(404, "NOT_FOUND", "There is no accepted work to review.");
    return wire(draft((UUID) sub.get("id"), false));
  }

  public Object saveDraft(
      Actor a,
      UUID school,
      UUID assignment,
      UUID student,
      AssignmentService.Feedback b,
      long version,
      String key) {
    work.owner(a, school, work.work(school, assignment, false));
    return command(
        a,
        school,
        "assignments/" + assignment + "/submissions/" + student + "/review-draft",
        key,
        map("input", b, "version", version),
        () -> {
          s.lockPlacements(school);
          var w = work.work(school, assignment, true);
          work.owner(a, school, w);
          var sub = work.submission(school, assignment, student, false);
          if (sub == null) throw fail(404, "NOT_FOUND", "There is no accepted work to review.");
          work.validateFeedback(w, sub, b);
          UUID id = (UUID) sub.get("id");
          var old = draft(id, true);
          expectVersion(version, old == null ? 1 : ((Number) old.get("version")).longValue());
          int revision =
              s.db.queryForObject(
                  "select max(revision) from submission_revisions where submission_id=?",
                  Integer.class,
                  id);
          s.db.update(
              "insert into submission_review_drafts(submission_id,source_version,source_revision,payload,version,author_id) values (?,?,?,?::jsonb,2,?) on conflict(submission_id) do update set source_version=excluded.source_version,source_revision=excluded.source_revision,payload=excluded.payload,state='DRAFT',version=submission_review_drafts.version+1,author_id=excluded.author_id,updated_at=now(),released_at=null",
              id,
              sub.get("version"),
              revision,
              s.json.write(b),
              a.accountId());
          s.commands.audit(
              school,
              a.accountId(),
              "ASSIGNMENT_REVIEW_DRAFT_SAVED",
              "SUBMISSION",
              id,
              null,
              map("sourceRevision", revision));
          return wire(draft(id, false));
        });
  }

  public Object release(
      Actor a, UUID school, UUID assignment, UUID student, long version, String key) {
    work.owner(a, school, work.work(school, assignment, false));
    return command(
        a,
        school,
        "assignments/" + assignment + "/submissions/" + student + "/review-draft/release",
        key,
        map("version", version),
        () -> {
          s.lockPlacements(school);
          var w = work.work(school, assignment, true);
          work.owner(a, school, w);
          var sub = work.submission(school, assignment, student, false);
          if (sub == null) throw fail(404, "NOT_FOUND", "The work is unavailable.");
          UUID id = (UUID) sub.get("id");
          var d = draft(id, true);
          if (d == null || !"DRAFT".equals(d.get("state")))
            throw fail(
                409, "REVIEW_DRAFT_REQUIRED", "Save a review draft before releasing feedback.");
          expectVersion(version, ((Number) d.get("version")).longValue());
          if (((Number) d.get("source_version")).longValue()
              != ((Number) sub.get("version")).longValue())
            throw fail(
                412,
                "REVIEW_SOURCE_CHANGED",
                "The accepted response or released grade changed. Recheck the work and save a fresh review.");
          var b = s.json.read(d.get("payload").toString(), AssignmentService.Feedback.class);
          Object result =
              work.applyFeedback(
                  a, school, assignment, student, b, ((Number) sub.get("version")).longValue());
          s.db.update(
              "update submission_review_drafts set state='RELEASED',released_at=now(),version=version+1 where submission_id=?",
              id);
          return map("reviewVersion", version + 1, "state", "RELEASED", "submission", result);
        });
  }
}
