package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SchoolReportService {
  private final SchoolService s;
  private final AssessmentService assessment;

  public SchoolReportService(SchoolService s, AssessmentService assessment) {
    this.s = s;
    this.assessment = assessment;
  }

  public Object catalogue(Actor a, UUID school) {
    String role = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER").role();
    List<Object> items = new ArrayList<>();
    if (!"TEACHER".equals(role)) {
      items.add(
          map(
              "kind",
              "ENROLMENTS",
              "filters",
              List.of("sessionId", "termId", "classId", "studentId", "department", "from", "to")));
      items.add(
          map(
              "kind",
              "ATTENDANCE",
              "filters",
              List.of("sessionId", "termId", "classId", "studentId", "department", "from", "to")));
      items.add(
          map(
              "kind",
              "TEACHING_ASSIGNMENTS",
              "filters",
              List.of("sessionId", "termId", "classId", "department", "from", "to")));
      items.add(
          map(
              "kind",
              "ASSESSMENT_COMPLETENESS",
              "filters",
              List.of("sessionId", "termId", "classId", "cycleId", "from", "to")));
    }
    items.add(
        map(
            "kind",
            "PUBLISHED_BROADSHEET",
            "requiredFilters",
            List.of("cycleId"),
            "filters",
            List.of("studentId", "department"),
            "teacherScope",
            "CURRENT_CLASS_LEAD"));
    items.add(
        map(
            "kind",
            "SUBJECT_MARKS",
            "requiredFilters",
            List.of("cycleId", "assignmentId"),
            "teacherScope",
            "ASSIGNED_SUBJECT_OR_CLASS_LEAD"));
    return map(
        "items",
        items,
        "formats",
        List.of("JSON", "CSV", "PDF"),
        "maximumRows",
        1000,
        "financeCataloguePath",
        "/api/v1/schools/" + school + "/reconciliation/reports/catalogue",
        "complaintsCataloguePath",
        "/api/v1/schools/" + school + "/complaints/reports/catalogue");
  }

  public record Filters(
      UUID sessionId,
      UUID termId,
      UUID classId,
      UUID studentId,
      String department,
      LocalDate from,
      LocalDate to,
      UUID cycleId,
      UUID assignmentId) {}

  private Filters validate(UUID school, Filters b) {
    UUID session = b.sessionId();
    LocalDate from = b.from(), to = b.to();
    if (b.termId() != null) {
      var term = s.one("select * from terms where school_id=? and id=?", school, b.termId());
      if (session != null && !session.equals(term.get("session_id")))
        throw fail(422, "REPORT_SCOPE_MISMATCH", "The term belongs to another session.");
      session = (UUID) term.get("session_id");
      if (from == null) from = ((java.sql.Date) term.get("starts_on")).toLocalDate();
      if (to == null) to = ((java.sql.Date) term.get("ends_on")).toLocalDate();
    }
    if (session != null)
      s.one("select id from academic_sessions where school_id=? and id=?", school, session);
    if (b.classId() != null) {
      var cl =
          s.one("select session_id from classes where school_id=? and id=?", school, b.classId());
      if (session != null && !session.equals(cl.get("session_id")))
        throw fail(422, "REPORT_SCOPE_MISMATCH", "Choose a class in the selected session.");
      session = (UUID) cl.get("session_id");
    }
    if (b.studentId() != null)
      s.one("select id from students where school_id=? and id=?", school, b.studentId());
    if (b.department() != null && !Set.of("SCIENCE", "ARTS", "COMMERCIAL").contains(b.department()))
      throw fail(422, "INVALID_DEPARTMENT", "Choose SCIENCE, ARTS or COMMERCIAL.");
    if (from != null && to != null && (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 366))
      throw fail(422, "INVALID_REPORT_DATES", "Use ordered report dates within 367 days.");
    return new Filters(
        session,
        b.termId(),
        b.classId(),
        b.studentId(),
        b.department(),
        from,
        to,
        b.cycleId(),
        b.assignmentId());
  }

  private void filter(
      StringBuilder sql, List<Object> args, String column, Object value, String operator) {
    if (value != null) {
      sql.append(" and ").append(column).append(operator).append("?");
      args.add(value);
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ReportData build(Actor a, UUID school, String rawKind, Filters input) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "TEACHER");
    String kind = rawKind.toUpperCase(Locale.ROOT);
    Filters b = validate(school, input);
    if ("PUBLISHED_BROADSHEET".equals(kind)) return published(a, school, b);
    if ("SUBJECT_MARKS".equals(kind)) return subject(a, school, b);
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    if (b.assignmentId() != null
        || b.cycleId() != null && !"ASSESSMENT_COMPLETENESS".equals(kind)
        || b.studentId() != null
            && Set.of("TEACHING_ASSIGNMENTS", "ASSESSMENT_COMPLETENESS").contains(kind)
        || b.department() != null && "ASSESSMENT_COMPLETENESS".equals(kind))
      throw fail(
          422,
          "UNSUPPORTED_REPORT_FILTER",
          "Use only the filters listed for this report in the catalogue.");
    StringBuilder sql = new StringBuilder();
    List<Object> args = new ArrayList<>();
    List<String> columns;
    List<String> notes =
        new ArrayList<>(
            List.of(
                "School: " + s.school(school).get("name"),
                "Staff report. Downloads repeat current school and role checks."));
    switch (kind) {
      case "ENROLMENTS" -> {
        sql.append(
            "select st.id as \"studentId\",st.admission_number as \"admissionNumber\",st.full_name as \"studentName\",st.status,y.name as session,c.level,c.arm,e.department,e.starts_on as \"startsOn\",e.ends_on as \"endsOn\" from enrolments e join students st on st.id=e.student_id join classes c on c.id=e.class_id join academic_sessions y on y.id=e.session_id where e.school_id=?");
        args.add(school);
        filter(sql, args, "e.session_id", b.sessionId(), "=");
        filter(sql, args, "e.class_id", b.classId(), "=");
        filter(sql, args, "e.student_id", b.studentId(), "=");
        filter(sql, args, "e.department", b.department(), "=");
        filter(sql, args, "e.starts_on", b.to(), "<=");
        if (b.from() != null) {
          sql.append(" and (e.ends_on is null or e.ends_on>=?)");
          args.add(b.from());
        }
        sql.append(" order by y.starts_on,c.level,c.arm,st.admission_number,e.id limit 1001");
        columns =
            List.of(
                "studentId",
                "admissionNumber",
                "studentName",
                "status",
                "session",
                "level",
                "arm",
                "department",
                "startsOn",
                "endsOn");
      }
      case "ATTENDANCE" -> {
        if (b.from() == null || b.to() == null)
          throw fail(
              422,
              "REPORT_DATES_REQUIRED",
              "Choose a term or explicit from/to dates for attendance.");
        sql.append(
            "select st.id as \"studentId\",st.admission_number as \"admissionNumber\",st.full_name as \"studentName\",c.level,c.arm,e.department,d.on_date::date as date,case when r.state='SUBMITTED' then coalesce(ar.status,'NOT_RECORDED') else 'NOT_RECORDED' end as status,coalesce(r.state='SUBMITTED',false) as \"registerSubmitted\" from generate_series(?::date,?::date,interval '1 day') d(on_date) join enrolments e on e.starts_on<=d.on_date::date and (e.ends_on is null or e.ends_on>=d.on_date::date) join students st on st.id=e.student_id join classes c on c.id=e.class_id join academic_sessions y on y.id=e.session_id and d.on_date::date between y.starts_on and y.ends_on left join calendar_exceptions ce on ce.school_id=e.school_id and ce.on_date=d.on_date::date left join attendance_registers r on r.class_id=e.class_id and r.on_date=d.on_date::date left join attendance_rows ar on ar.register_id=r.id and ar.student_id=e.student_id where e.school_id=? and coalesce(ce.teaching_day,extract(isodow from d.on_date)<6)");
        args.add(b.from());
        args.add(b.to());
        args.add(school);
        filter(sql, args, "e.session_id", b.sessionId(), "=");
        filter(sql, args, "e.class_id", b.classId(), "=");
        filter(sql, args, "e.student_id", b.studentId(), "=");
        filter(sql, args, "e.department", b.department(), "=");
        sql.append(" order by d.on_date,c.level,c.arm,st.admission_number limit 1001");
        columns =
            List.of(
                "studentId",
                "admissionNumber",
                "studentName",
                "level",
                "arm",
                "department",
                "date",
                "status",
                "registerSubmitted");
        notes.add(
            "NOT_RECORDED means a missing/unsubmitted register or missing accepted row. It never counts as absence.");
      }
      case "TEACHING_ASSIGNMENTS" -> {
        sql.append(
            "select t.id as \"assignmentId\",st.full_name as \"teacherName\",st.staff_number as \"staffNumber\",y.name as session,c.level,c.arm,su.code as subject,t.department,t.weekly_periods as \"weeklyPeriods\",t.active from teaching_assignments t join staff st on st.id=t.teacher_id join classes c on c.id=t.class_id join academic_sessions y on y.id=c.session_id join subjects su on su.id=t.subject_id where t.school_id=?");
        args.add(school);
        filter(sql, args, "c.session_id", b.sessionId(), "=");
        filter(sql, args, "c.id", b.classId(), "=");
        filter(sql, args, "t.department", b.department(), "=");
        filter(sql, args, "y.starts_on", b.to(), "<=");
        filter(sql, args, "y.ends_on", b.from(), ">=");
        sql.append(" order by st.full_name,c.level,c.arm,su.code limit 1001");
        columns =
            List.of(
                "assignmentId",
                "teacherName",
                "staffNumber",
                "session",
                "level",
                "arm",
                "subject",
                "department",
                "weeklyPeriods",
                "active");
      }
      case "ASSESSMENT_COMPLETENESS" -> {
        sql.append(
            "select x.id as \"cycleId\",y.name as session,t.number as term,c.level,c.arm,x.state,x.version,(select count(*) from assessment_sheets sh where sh.cycle_id=x.id and sh.active) as \"subjectSheets\",(select count(*) from assessment_sheets sh where sh.cycle_id=x.id and sh.active and sh.state='SUBMITTED') as \"submittedSheets\",(select count(*) from assessment_roster r where r.cycle_id=x.id and r.active) as pupils from assessment_cycles x join classes c on c.id=x.class_id join terms t on t.id=x.term_id join academic_sessions y on y.id=t.session_id where x.school_id=?");
        args.add(school);
        filter(sql, args, "t.session_id", b.sessionId(), "=");
        filter(sql, args, "t.id", b.termId(), "=");
        filter(sql, args, "c.id", b.classId(), "=");
        filter(sql, args, "x.id", b.cycleId(), "=");
        filter(sql, args, "t.starts_on", b.to(), "<=");
        filter(sql, args, "t.ends_on", b.from(), ">=");
        sql.append(" order by y.starts_on,t.number,c.level,c.arm limit 1001");
        columns =
            List.of(
                "cycleId",
                "session",
                "term",
                "level",
                "arm",
                "state",
                "version",
                "subjectSheets",
                "submittedSheets",
                "pupils");
        notes.add("Completeness is a workflow summary. It does not publish marks or rank pupils.");
      }
      default ->
          throw fail(404, "REPORT_NOT_FOUND", "Choose a report from the permitted catalogue.");
    }
    return new ReportData(
        kind,
        kind.replace('_', ' '),
        columns,
        s.db.queryForList(sql.toString(), args.toArray()),
        notes);
  }

  private ReportData published(Actor a, UUID school, Filters b) {
    if (b.cycleId() == null) throw fail(422, "CYCLE_REQUIRED", "Choose an assessment cycle.");
    var cycle = assessment.cycle(a, school, b.cycleId(), false, true);
    if (b.assignmentId() != null
        || b.sessionId() != null && !b.sessionId().equals(cycle.get("session_id"))
        || b.termId() != null && !b.termId().equals(cycle.get("term_id"))
        || b.classId() != null && !b.classId().equals(cycle.get("class_id"))
        || b.from() != null && b.termId() == null
        || b.to() != null && b.termId() == null)
      throw fail(
          422,
          "REPORT_SCOPE_MISMATCH",
          "Use the selected cycle's scope for a published broadsheet.");
    var snapshots =
        s.db.queryForList(
            "select distinct on(student_id) snapshot from report_versions where school_id=? and cycle_id=? and (?::uuid is null or student_id=?::uuid) order by student_id,revision desc limit 1001",
            school,
            b.cycleId(),
            b.studentId(),
            b.studentId());
    if (snapshots.isEmpty())
      throw fail(409, "NO_PUBLISHED_REPORTS", "This scope has no published reports.");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var saved : snapshots) {
      var p = s.json.tree(saved.get("snapshot").toString());
      if (b.department() != null && !b.department().equals(p.path("department").asText())) continue;
      for (var sub : p.path("subjects")) {
        rows.add(
            map(
                "admissionNumber",
                p.path("admissionNumber").asText(),
                "studentName",
                p.path("studentName").asText(),
                "subject",
                sub.path("code").asText(),
                "ca1",
                sub.path("ca1").decimalValue(),
                "ca2",
                sub.path("ca2").decimalValue(),
                "exam",
                sub.path("exam").decimalValue(),
                "total",
                sub.path("total").decimalValue(),
                "grade",
                sub.path("grade").asText(),
                "average",
                p.path("average").decimalValue(),
                "reportRevision",
                p.path("revision").asInt()));
        if (rows.size() > 1000) break;
      }
      if (rows.size() > 1000) break;
    }
    return new ReportData(
        "PUBLISHED_BROADSHEET",
        "Published class broadsheet",
        List.of(
            "admissionNumber",
            "studentName",
            "subject",
            "ca1",
            "ca2",
            "exam",
            "total",
            "grade",
            "average",
            "reportRevision"),
        rows,
        List.of(
            "Latest published snapshot per pupil; corrections in progress do not overwrite accepted reports.",
            "No overall class ranking."));
  }

  @SuppressWarnings("unchecked")
  private ReportData subject(Actor a, UUID school, Filters b) {
    if (b.cycleId() == null || b.assignmentId() == null)
      throw fail(422, "SUBJECT_SCOPE_REQUIRED", "Choose a cycle and subject assignment.");
    if (b.sessionId() != null
        || b.termId() != null
        || b.classId() != null
        || b.studentId() != null
        || b.department() != null
        || b.from() != null
        || b.to() != null)
      throw fail(
          422, "UNSUPPORTED_REPORT_FILTER", "A subject sheet uses its cycle and assignment only.");
    var sheet = (Map<String, Object>) assessment.sheet(a, school, b.cycleId(), b.assignmentId());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var source : (List<Map<String, Object>>) sheet.get("rows")) {
      var row = new LinkedHashMap<>(source);
      if (row.get("ca1") != null && row.get("ca2") != null && row.get("exam") != null) {
        BigDecimal total =
            SchoolRules.total(
                (BigDecimal) row.get("ca1"),
                (BigDecimal) row.get("ca2"),
                (BigDecimal) row.get("exam"));
        row.put("total", total);
        row.put("grade", SchoolRules.grade(total));
      }
      rows.add(row);
    }
    return new ReportData(
        "SUBJECT_MARKS",
        "Permitted subject sheet",
        List.of(
            "admissionNumber",
            "fullName",
            "ca1",
            "ca2",
            "exam",
            "total",
            "grade",
            "examState",
            "active"),
        rows,
        List.of(
            "School staff working sheet; cycle state: "
                + sheet.get("cycleState")
                + "; sheet state: "
                + sheet.get("state"),
            "A draft or submitted working sheet is not a published family report."));
  }
}
