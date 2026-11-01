package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PromotionService {
  private final SchoolService s;
  private final AdministrationService admin;

  public PromotionService(SchoolService s, AdministrationService admin) {
    this.s = s;
    this.admin = admin;
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

  private Map<String, Object> batch(UUID school, UUID id, boolean lock) {
    return s.one(
        "select b.*,c.level from promotion_batches b join classes c on c.id=b.source_class_id where b.school_id=? and b.id=?"
            + (lock ? " for update of b" : ""),
        school,
        id);
  }

  private record Evidence(
      String signature, List<String> reports, SchoolRules.PromotionDecision decision) {}

  private Evidence evidence(UUID school, UUID session, UUID student) {
    var placement =
        s.one(
            "select st.status,st.version,e.id,e.class_id,e.version as enrolment_version from students st join enrolments e on e.student_id=st.id where st.school_id=? and st.id=? and e.session_id=? order by e.starts_on desc limit 1",
            school,
            student,
            session);
    var reports =
        s.db.queryForList(
            "select distinct on(t.number) r.id,r.snapshot,t.number from report_versions r join terms t on t.id=r.term_id where r.school_id=? and r.student_id=? and t.session_id=? order by t.number,r.revision desc,r.published_at desc",
            school,
            student,
            session);
    List<String> ids = reports.stream().map(r -> r.get("id").toString()).toList();
    String signature = Crypto.hash(s.json.write(map("placement", placement, "reports", ids)));
    if (reports.size() != 3)
      return new Evidence(
          signature,
          ids,
          new SchoolRules.PromotionDecision(
              "REVIEW", null, null, List.of("All three published term reports are required.")));
    Map<String, List<BigDecimal>> totals = new TreeMap<>();
    Set<String> previous = null;
    for (var r : reports) {
      var json = s.json.tree(r.get("snapshot").toString());
      Set<String> codes = new TreeSet<>();
      for (var subject : json.path("subjects")) {
        String code = subject.path("code").asText();
        codes.add(code);
        totals
            .computeIfAbsent(code, k -> new ArrayList<>())
            .add(new BigDecimal(subject.path("total").asText()));
      }
      if (previous != null && !previous.equals(codes))
        return new Evidence(
            signature,
            ids,
            new SchoolRules.PromotionDecision(
                "REVIEW",
                null,
                null,
                List.of("Subject combinations changed between published terms.")));
      previous = codes;
    }
    return new Evidence(
        signature,
        ids,
        SchoolRules.promotion(
            totals.entrySet().stream()
                .map(e -> new SchoolRules.AnnualSubject(e.getKey(), e.getValue()))
                .toList()));
  }

  public record Preview(UUID sourceSessionId, UUID targetSessionId, UUID sourceClassId) {}

  public Object prepare(Actor a, UUID school, Preview b, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "promotion-batches",
        key,
        b,
        () -> {
          var source =
              s.one(
                  "select * from academic_sessions where school_id=? and id=?",
                  school,
                  b.sourceSessionId());
          var c =
              s.one("select * from classes where school_id=? and id=?", school, b.sourceClassId());
          if (!c.get("session_id").equals(b.sourceSessionId()))
            throw fail(
                422,
                "INVALID_SOURCE_CLASS",
                "The source class must belong to the selected session.");
          if (b.targetSessionId() != null) {
            var target =
                s.one(
                    "select * from academic_sessions where school_id=? and id=?",
                    school,
                    b.targetSessionId());
            if (!((java.sql.Date) target.get("starts_on"))
                .after((java.sql.Date) source.get("ends_on")))
              throw fail(
                  422,
                  "INVALID_TARGET_SESSION",
                  "The target session must start after the source session.");
          }
          if (!"SSS3".equals(c.get("level")) && b.targetSessionId() == null)
            throw fail(
                422, "TARGET_SESSION_REQUIRED", "Select the next session for continuing students.");
          UUID id = UUID.randomUUID();
          s.db.update(
              "insert into promotion_batches(id,school_id,source_session_id,target_session_id,source_class_id,prepared_by) values (?,?,?,?,?,?)",
              id,
              school,
              b.sourceSessionId(),
              b.targetSessionId(),
              b.sourceClassId(),
              a.accountId());
          var rows =
              s.db.queryForList(
                  "select distinct st.id from students st join enrolments e on e.student_id=st.id where st.school_id=? and e.session_id=? and e.class_id=? and st.status='ACTIVE' and not exists(select 1 from enrolments later where later.student_id=e.student_id and later.session_id=e.session_id and later.starts_on>e.starts_on) order by st.id",
                  school,
                  b.sourceSessionId(),
                  b.sourceClassId());
          if (rows.isEmpty() || rows.size() > 200)
            throw fail(
                422, "INVALID_PROMOTION_SCOPE", "Use a non-empty class of at most 200 students.");
          for (var row : rows) {
            UUID student = (UUID) row.get("id");
            var evidence = evidence(school, b.sourceSessionId(), student);
            s.db.update(
                "insert into promotion_rows(batch_id,student_id,recommendation,source_signature,source_report_ids) values (?,?,?::jsonb,?,?::jsonb)",
                id,
                student,
                s.json.write(evidence.decision()),
                evidence.signature(),
                s.json.write(evidence.reports()));
          }
          s.commands.audit(
              school,
              a.accountId(),
              "PROMOTION_PREVIEW_PREPARED",
              "PROMOTION_BATCH",
              id,
              null,
              map("students", rows.size()));
          return detail(a, school, id);
        });
  }

  public Object list(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return map(
        "items",
        s.db.queryForList(
            "select id,source_session_id as \"sourceSessionId\",target_session_id as \"targetSessionId\",source_class_id as \"sourceClassId\",state,version,created_at as \"createdAt\" from promotion_batches where school_id=? order by created_at desc",
            school),
        "hasMore",
        false);
  }

  public Object detail(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var b = batch(school, id, false);
    var rows =
        s.db.queryForList(
            "select r.*,st.full_name as \"studentName\",st.admission_number as \"admissionNumber\" from promotion_rows r join students st on st.id=r.student_id where r.batch_id=? order by r.student_id",
            id);
    List<Object> items = new ArrayList<>();
    for (var r : rows)
      items.add(
          map(
              "studentId",
              r.get("student_id"),
              "studentName",
              r.get("studentName"),
              "admissionNumber",
              r.get("admissionNumber"),
              "recommendation",
              s.json.value(r.get("recommendation").toString()),
              "decision",
              r.get("decision"),
              "targetClassId",
              r.get("target_class_id"),
              "department",
              r.get("department"),
              "reason",
              r.get("reason"),
              "evidenceReference",
              r.get("evidence_reference"),
              "approvedException",
              r.get("approved_exception"),
              "version",
              r.get("version"),
              "resultId",
              r.get("result_id"),
              "state",
              r.get("result_id") == null ? "PREVIEW" : "COMPLETED"));
    return map(
        "id",
        id,
        "sourceSessionId",
        b.get("source_session_id"),
        "targetSessionId",
        b.get("target_session_id"),
        "sourceClassId",
        b.get("source_class_id"),
        "sourceLevel",
        b.get("level"),
        "state",
        b.get("state"),
        "version",
        b.get("version"),
        "rows",
        items);
  }

  public record Decision(
      String decision,
      UUID targetClassId,
      String department,
      String reason,
      String evidenceReference) {}

  public Object decide(
      Actor a, UUID school, UUID id, UUID student, Decision b, long version, String key) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    return command(
        a,
        school,
        "promotion-batches/" + id + "/students/" + student,
        key,
        map("input", b, "version", version),
        () -> {
          var batch = batch(school, id, true);
          if (!"PREVIEW".equals(batch.get("state")))
            throw fail(409, "BATCH_COMPLETED", "This batch is already confirmed.");
          var row =
              s.one(
                  "select * from promotion_rows where batch_id=? and student_id=? for update",
                  id,
                  student);
          expectVersion(version, ((Number) row.get("version")).longValue());
          if (b.decision() == null
              || !Set.of("PROMOTE", "REPEAT", "JSS_COMPLETE", "GRADUATE", "REVIEW")
                  .contains(b.decision()))
            throw fail(422, "INVALID_PROMOTION_DECISION", "Choose a supported reviewed outcome.");
          var recommendation = s.json.tree(row.get("recommendation").toString());
          boolean override =
              !"PROMOTE".equals(recommendation.path("recommendation").asText())
                  && Set.of("PROMOTE", "JSS_COMPLETE", "GRADUATE").contains(b.decision());
          if (override) {
            a.school(school, "PRINCIPAL");
            text(b.evidenceReference(), 5, 240, "Documented exception evidence");
          }
          String level = (String) batch.get("level");
          if ("JSS3".equals(level) && "PROMOTE".equals(b.decision()))
            throw fail(
                422,
                "JSS_PATHWAY_REVIEW_REQUIRED",
                "Use JSS_COMPLETE and explicitly choose an SSS1 pathway.");
          if ("SSS3".equals(level) && Set.of("PROMOTE", "JSS_COMPLETE").contains(b.decision())
              || !"SSS3".equals(level) && "GRADUATE".equals(b.decision())
              || !"JSS3".equals(level) && "JSS_COMPLETE".equals(b.decision()))
            throw fail(
                422,
                "INVALID_STAGE_DECISION",
                "This outcome does not match the student's source stage.");
          if ("GRADUATE".equals(b.decision())) a.school(school, "PRINCIPAL");
          String department = null;
          if (!Set.of("GRADUATE", "REVIEW").contains(b.decision())) {
            var target =
                s.one(
                    "select * from classes where school_id=? and id=?", school, b.targetClassId());
            String expected =
                "REPEAT".equals(b.decision())
                    ? level
                    : "JSS_COMPLETE".equals(b.decision()) ? "SSS1" : nextLevel(level);
            if (!Objects.equals(target.get("session_id"), batch.get("target_session_id"))
                || !expected.equals(target.get("level")))
              throw fail(
                  422, "INVALID_TARGET_CLASS", "Choose the reviewed level in the target session.");
            department = SchoolRules.department(expected, b.department());
          }
          String reason = text(b.reason(), override ? 10 : 5, 1000, "Reviewed decision reason");
          s.db.update(
              "update promotion_rows set decision=?,target_class_id=?,department=?,reason=?,evidence_reference=?,approved_exception=?,version=version+1 where batch_id=? and student_id=?",
              b.decision(),
              Set.of("GRADUATE", "REVIEW").contains(b.decision()) ? null : b.targetClassId(),
              department,
              reason,
              b.evidenceReference(),
              override,
              id,
              student);
          s.db.update("update promotion_batches set version=version+1 where id=?", id);
          s.commands.audit(
              school,
              a.accountId(),
              "PROMOTION_DECISION_REVIEWED",
              "PROMOTION_BATCH",
              id,
              reason,
              map("studentId", student, "decision", b.decision(), "approvedException", override));
          return detail(a, school, id);
        });
  }

  private String nextLevel(String level) {
    return switch (level) {
      case "JSS1" -> "JSS2";
      case "JSS2" -> "JSS3";
      case "SSS1" -> "SSS2";
      case "SSS2" -> "SSS3";
      default ->
          throw fail(
              422, "STAGE_REVIEW_REQUIRED", "This stage requires explicit completion review.");
    };
  }

  public Object confirm(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "promotion-batches/" + id + "/confirm",
        key,
        map("version", version),
        () -> {
          var b = batch(school, id, true);
          expectVersion(version, ((Number) b.get("version")).longValue());
          if (!"PREVIEW".equals(b.get("state")))
            throw fail(409, "BATCH_COMPLETED", "This batch is already confirmed.");
          var source =
              s.one("select * from academic_sessions where id=?", b.get("source_session_id"));
          var rows =
              s.db.queryForList(
                  "select * from promotion_rows where batch_id=? order by student_id for update",
                  id);
          s.db.queryForList(
              "select id from classes where id in (select target_class_id from promotion_rows where batch_id=?) order by id for update",
              id);
          // Validate the entire class before changing any placement. One class transaction has no
          // hidden partial successes.
          for (var row : rows) {
            UUID student = (UUID) row.get("student_id");
            var st = s.one("select id,status from students where id=? for update", student);
            if (!"ACTIVE".equals(st.get("status")))
              throw fail(
                  409,
                  "STUDENT_STATUS_CHANGED",
                  "A student's status changed; prepare a fresh preview.");
            var current = evidence(school, (UUID) b.get("source_session_id"), student);
            if (!Crypto.equal(current.signature(), (String) row.get("source_signature")))
              throw fail(
                  412,
                  "PROMOTION_SOURCE_CHANGED",
                  "Published results or source enrolments changed. Prepare and review a fresh preview.");
            if ("REVIEW".equals(row.get("decision")))
              throw fail(
                  422,
                  "UNRESOLVED_PROMOTION_ROW",
                  "Every student requires an explicit reviewed outcome.");
            if (s.db.queryForObject(
                    "select count(*) from promotion_outcomes where source_session_id=? and student_id=?",
                    Integer.class,
                    b.get("source_session_id"),
                    student)
                > 0)
              throw fail(
                  409,
                  "OUTCOME_ALREADY_CONFIRMED",
                  "A source-year outcome was already confirmed for this student.");
            if (!"GRADUATE".equals(row.get("decision"))
                && s.db.queryForObject(
                        "select count(*) from enrolments where student_id=? and session_id=?",
                        Integer.class,
                        student,
                        b.get("target_session_id"))
                    > 0)
              throw fail(
                  409,
                  "TARGET_ENROLMENT_EXISTS",
                  "Resolve an existing target-session placement before confirming.");
          }
          List<Object> results = new ArrayList<>();
          for (var row : rows) {
            UUID student = (UUID) row.get("student_id"),
                placement = null,
                result = UUID.randomUUID();
            String decision = (String) row.get("decision");
            s.db.update(
                "update enrolments set ends_on=least(coalesce(ends_on,?::date),?::date),version=version+1 where student_id=? and session_id=?",
                source.get("ends_on"),
                source.get("ends_on"),
                student,
                b.get("source_session_id"));
            if ("GRADUATE".equals(decision))
              s.db.update(
                  "update students set status='GRADUATED',version=version+1 where id=?", student);
            else {
              var target =
                  s.one(
                      "select c.*,se.starts_on from classes c join academic_sessions se on se.id=c.session_id where c.id=?",
                      row.get("target_class_id"));
              LocalDate starts = ((java.sql.Date) target.get("starts_on")).toLocalDate();
              admin.capacity(school, (UUID) target.get("id"), starts);
              placement = UUID.randomUUID();
              s.db.update(
                  "insert into enrolments(id,school_id,student_id,session_id,class_id,department,starts_on) values (?,?,?,?,?,?,?)",
                  placement,
                  school,
                  student,
                  target.get("session_id"),
                  target.get("id"),
                  row.get("department"),
                  starts);
              s.db.update("update students set version=version+1 where id=?", student);
            }
            s.db.update(
                "insert into promotion_outcomes(id,school_id,source_session_id,student_id,batch_id,decision,target_enrolment_id,reason,evidence_reference,confirmed_by) values (?,?,?,?,?,?,?,?,?,?)",
                result,
                school,
                b.get("source_session_id"),
                student,
                id,
                decision,
                placement,
                row.get("reason"),
                row.get("evidence_reference"),
                a.accountId());
            s.db.update(
                "update promotion_rows set result_id=? where batch_id=? and student_id=?",
                result,
                id,
                student);
            s.commands.audit(
                school,
                a.accountId(),
                "ACADEMIC_YEAR_OUTCOME_CONFIRMED",
                "STUDENT",
                student,
                (String) row.get("reason"),
                map("decision", decision, "batchId", id, "targetEnrolmentId", placement));
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
                    "School year update",
                    "safePreview",
                    "Your school recorded an academic year update. Open the app for details.",
                    "target",
                    map(
                        "screenId",
                        "PA01",
                        "schoolId",
                        school,
                        "childId",
                        student,
                        "resourceId",
                        result)));
            results.add(
                map(
                    "studentId",
                    student,
                    "resultId",
                    result,
                    "decision",
                    decision,
                    "enrolmentId",
                    placement,
                    "state",
                    "COMPLETED"));
          }
          s.db.update(
              "update promotion_batches set state='COMPLETED',version=version+1,confirmed_by=?,confirmed_at=now() where id=?",
              a.accountId(),
              id);
          return map("id", id, "version", version + 1, "state", "COMPLETED", "results", results);
        });
  }

  public Object history(Actor a, UUID school, UUID student) {
    s.learning(a, school, student);
    return map(
        "items",
        s.db.queryForList(
            "select id,source_session_id as \"sourceSessionId\",decision,target_enrolment_id as \"targetEnrolmentId\",reason,confirmed_at as \"confirmedAt\" from promotion_outcomes where school_id=? and student_id=? order by confirmed_at",
            school,
            student),
        "hasMore",
        false);
  }

  public byte[] csv(Actor a, UUID school, UUID id) {
    @SuppressWarnings("unchecked")
    var d = (Map<String, Object>) detail(a, school, id);
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) d.get("rows");
    return Documents.csv(
        List.of("Admission", "Student", "Decision", "Target class", "Pathway", "Reason", "State"),
        items.stream()
            .map(
                r ->
                    Arrays.asList(
                        r.get("admissionNumber"),
                        r.get("studentName"),
                        r.get("decision"),
                        r.get("targetClassId"),
                        r.get("department"),
                        r.get("reason"),
                        r.get("state")))
            .toList());
  }
}
