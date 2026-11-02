package com.educore.finance;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OpeningBalanceService {
  private final FinanceService f;

  public OpeningBalanceService(FinanceService f) {
    this.f = f;
  }

  private void role(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object b,
      java.util.function.Supplier<Object> action) {
    role(a, school);
    return f.commands.execute(a.accountId().toString(), school.toString(), path, key, b, action);
  }

  public record Preview(UUID termId, String csv, Map<String, String> columnMapping) {}

  private String required(Map<String, String> r, String key, int max) {
    return text(r.get(key), 1, max, key);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> students(UUID school, UUID term, List<Map<String, String>> rows) {
    return f.http.post(
        f.academics,
        "/internal/student-references",
        map(
            "schoolId",
            school,
            "termId",
            term,
            "admissionNumbers",
            rows.stream()
                .map(r -> required(r, "admission_number", 40).toUpperCase(Locale.ROOT))
                .distinct()
                .toList()),
        Map.class);
  }

  private List<Map<String, String>> parse(Preview b) {
    var raw = Documents.parseCsv(b.csv());
    if (raw.isEmpty() || raw.size() > 200)
      throw fail(422, "INVALID_OPENING_BATCH", "Review one to 200 opening balance rows per batch.");
    var rows = new ArrayList<Map<String, String>>();
    Set<String> refs = new HashSet<>();
    for (var input : raw) {
      Map<String, String> r = new TreeMap<>();
      for (String col :
          List.of(
              "row_reference",
              "admission_number",
              "amount_minor",
              "due_on",
              "description",
              "evidence_reference")) {
        String header = b.columnMapping() == null ? col : b.columnMapping().getOrDefault(col, col);
        r.put(col, input.getOrDefault(header, "").strip());
      }
      String ref = required(r, "row_reference", 80);
      if (!ref.matches("[A-Za-z0-9_.-]{1,80}") || !refs.add(ref))
        throw fail(
            422, "INVALID_ROW_REFERENCE", "Use distinct permanent opening-balance row references.");
      required(r, "admission_number", 40);
      rows.add(r);
    }
    return rows;
  }

  @SuppressWarnings("unchecked")
  private List<Object> review(
      UUID school, List<Map<String, String>> rows, Map<String, Object> source) {
    var people = new HashMap<String, Map<String, Object>>();
    for (Object p : (List<?>) source.get("items")) {
      Map<String, Object> person = (Map<String, Object>) p;
      people.put(person.get("admissionNumber").toString(), person);
    }
    var out = new ArrayList<Object>();
    for (var r : rows) {
      String error = null;
      long amount = 0;
      LocalDate due = null;
      try {
        amount = Long.parseLong(required(r, "amount_minor", 20));
        FinanceService.amount(amount);
        due = LocalDate.parse(required(r, "due_on", 10));
        text(r.get("description"), 3, 160, "Opening-balance description");
        text(r.get("evidence_reference"), 5, 240, "Reviewed opening ledger evidence");
      } catch (RuntimeException ex) {
        error =
            "Use a positive whole-number kobo amount, YYYY-MM-DD due date, description and verified opening-ledger reference.";
      }
      var person = people.get(r.get("admission_number").toUpperCase(Locale.ROOT));
      if (person == null)
        error =
            "This admission number does not identify an existing student in the selected school.";
      var previous =
          f.db.queryForList(
              "select source_hash,invoice_id from opening_balance_entries where school_id=? and row_reference=?",
              school,
              r.get("row_reference"));
      if (!previous.isEmpty()
          && !Crypto.equal(
              previous.get(0).get("source_hash").toString(), Crypto.hash(f.json.write(r))))
        error = "This permanent row reference already identifies different imported balance data.";
      out.add(
          map(
              "rowReference",
              r.get("row_reference"),
              "studentId",
              person == null ? null : person.get("id"),
              "studentName",
              person == null ? null : person.get("fullName"),
              "amountMinor",
              amount,
              "dueOn",
              due,
              "action",
              error != null ? "INVALID" : previous.isEmpty() ? "ISSUE" : "ALREADY_IMPORTED",
              "error",
              error,
              "invoiceId",
              previous.isEmpty() ? null : previous.get(0).get("invoice_id")));
    }
    return out;
  }

  public Object preview(Actor a, UUID school, Preview b, String key) {
    role(a, school);
    var rows = parse(b);
    return command(
        a,
        school,
        "invoices/opening-balances",
        key,
        b,
        () -> {
          var source = students(school, b.termId(), rows);
          UUID id = UUID.randomUUID();
          f.db.update(
              "insert into opening_balance_batches(id,school_id,term_id,payload_cipher,source_signature,created_by) values (?,?,?,?,?,?)",
              id,
              school,
              b.termId(),
              f.crypto.seal(f.json.write(rows)),
              Crypto.hash(f.json.write(source)),
              a.accountId());
          return detail(a, school, id);
        });
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, String>> rows(Map<String, Object> batch) {
    return (List<Map<String, String>>)
        (List<?>) f.json.read(f.crypto.open(batch.get("payload_cipher").toString()), List.class);
  }

  public Object detail(Actor a, UUID school, UUID id) {
    role(a, school);
    var b = f.one("select * from opening_balance_batches where school_id=? and id=?", school, id);
    var rows = rows(b);
    return map(
        "id",
        id,
        "state",
        b.get("state"),
        "version",
        b.get("version"),
        "rows",
        review(school, rows, students(school, (UUID) b.get("term_id"), rows)));
  }

  public record Confirm(String reason) {}

  public Object confirm(Actor a, UUID school, UUID id, Confirm body, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "invoices/opening-balances/" + id + "/confirm",
        key,
        map("input", body, "version", version),
        () -> {
          var b =
              f.one(
                  "select * from opening_balance_batches where school_id=? and id=? for update",
                  school,
                  id);
          expectVersion(version, ((Number) b.get("version")).longValue());
          if (!"PREVIEW".equals(b.get("state")))
            throw fail(409, "BATCH_CONFIRMED", "This opening batch is already confirmed.");
          if (a.accountId().equals(b.get("created_by")))
            throw fail(
                403,
                "INDEPENDENT_APPROVER_REQUIRED",
                "A different principal must confirm a batch you created; accounts staff can prepare it for your review.");
          String reason = text(body.reason(), 10, 500, "Reviewed opening-ledger reason");
          f.db.queryForObject(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              Object.class,
              "opening-ledger|" + school);
          var rows = rows(b);
          var source = students(school, (UUID) b.get("term_id"), rows);
          if (!Crypto.equal(
              Crypto.hash(f.json.write(source)), b.get("source_signature").toString()))
            throw fail(
                412,
                "OPENING_SOURCE_CHANGED",
                "Student references changed. Prepare and review a fresh preview.");
          var reviewed = review(school, rows, source);
          for (Object value : reviewed) {
            Map<?, ?> r = (Map<?, ?>) value;
            if ("INVALID".equals(r.get("action")))
              throw fail(
                  422,
                  "INVALID_OPENING_ROWS",
                  "Correct the invalid opening-balance rows and prepare a new preview.");
          }
          for (int n = 0; n < rows.size(); n++) {
            var raw = rows.get(n);
            Map<?, ?> row = (Map<?, ?>) reviewed.get(n);
            if ("ALREADY_IMPORTED".equals(row.get("action"))) continue;
            String ref = raw.get("row_reference");
            String issueKey =
                UUID.nameUUIDFromBytes(
                        ("opening|" + school + "|" + ref).getBytes(StandardCharsets.UTF_8))
                    .toString();
            @SuppressWarnings("unchecked")
            var created =
                (Map<String, Object>)
                    f.issue(
                        a,
                        school,
                        new FinanceService.InvoiceInput(
                            uuid(row.get("studentId").toString()),
                            (UUID) b.get("term_id"),
                            raw.get("description"),
                            List.of(
                                new FinanceService.Line(
                                    "Reviewed opening balance",
                                    ((Number) row.get("amountMinor")).longValue(),
                                    1)),
                            LocalDate.parse(raw.get("due_on")),
                            true),
                        issueKey);
            Map<?, ?> invoice = (Map<?, ?>) created.get("invoice");
            f.db.update(
                "insert into opening_balance_entries(school_id,row_reference,source_hash,invoice_id,evidence_reference) values (?,?,?,?,?)",
                school,
                ref,
                Crypto.hash(f.json.write(raw)),
                uuid(invoice.get("id").toString()),
                raw.get("evidence_reference"));
          }
          f.db.update(
              "update opening_balance_batches set state='CONFIRMED',confirmed_by=?,confirmed_at=now(),version=version+1 where id=?",
              a.accountId(),
              id);
          f.commands.audit(
              school,
              a.accountId(),
              "OPENING_BALANCE_BATCH_CONFIRMED",
              "OPENING_BALANCE_BATCH",
              id,
              reason,
              map("rows", rows.size()));
          return detail(a, school, id);
        });
  }
}
