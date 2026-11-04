package com.educore.finance;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FeeAdministrationService {
  private final FinanceService f;

  public FeeAdministrationService(FinanceService f) {
    this.f = f;
  }

  private Object command(
      Actor a,
      UUID school,
      String path,
      String key,
      Object b,
      java.util.function.Supplier<Object> action) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    return f.commands.execute(a.accountId().toString(), school.toString(), path, key, b, action);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> roster(UUID school, UUID term, List<UUID> classes) {
    return f.http.post(
        f.academics,
        "/internal/class-roster",
        map("schoolId", school, "termId", term, "classIds", classes),
        Map.class);
  }

  public record Plan(
      UUID termId,
      String name,
      List<UUID> classIds,
      List<FinanceService.Line> lines,
      LocalDate dueOn,
      boolean partialAllowed) {}

  private long total(List<FinanceService.Line> lines) {
    if (lines == null || lines.isEmpty() || lines.size() > 30)
      throw fail(422, "INVALID_FEE_LINES", "Use one to 30 fee lines.");
    long total = 0;
    for (var l : lines) {
      text(l.description(), 2, 160, "Charge description");
      FinanceService.amount(l.amountMinor());
      if (l.quantity() < 1 || l.quantity() > 100)
        throw fail(422, "INVALID_QUANTITY", "Use a quantity from one to 100.");
      try {
        total = Math.addExact(total, Math.multiplyExact(l.amountMinor(), l.quantity()));
      } catch (ArithmeticException ex) {
        throw fail(422, "AMOUNT_TOO_LARGE", "The fee plan amount is too large.");
      }
    }
    FinanceService.amount(total);
    return total;
  }

  public Object savePlan(Actor a, UUID school, UUID id, Plan b, Long version, String key) {
    return command(
        a,
        school,
        "fee-plans/" + Objects.toString(id, "new"),
        key,
        map("input", b, "version", version),
        () -> {
          total(b.lines());
          if (b.dueOn() == null) throw fail(422, "DUE_DATE_REQUIRED", "Provide a fee due date.");
          roster(school, b.termId(), b.classIds());
          UUID plan = id == null ? UUID.randomUUID() : id;
          if (id == null)
            f.db.update(
                "insert into fee_plans(id,school_id,term_id,name,class_ids,lines,due_on,partial_allowed,created_by) values (?,?,?,?,?::jsonb,?::jsonb,?,?,?)",
                plan,
                school,
                b.termId(),
                text(b.name(), 3, 120, "Plan name"),
                f.json.write(b.classIds()),
                f.json.write(b.lines()),
                b.dueOn(),
                b.partialAllowed(),
                a.accountId());
          else {
            var p =
                f.one("select * from fee_plans where school_id=? and id=? for update", school, id);
            expectVersion(version, ((Number) p.get("version")).longValue());
            if (!"DRAFT".equals(p.get("state")))
              throw fail(
                  409,
                  "FEE_PLAN_PUBLISHED",
                  "Published fee lines are preserved; create a new plan or request a credit.");
            f.db.update(
                "update fee_plans set term_id=?,name=?,class_ids=?::jsonb,lines=?::jsonb,due_on=?,partial_allowed=?,version=version+1 where id=?",
                b.termId(),
                text(b.name(), 3, 120, "Plan name"),
                f.json.write(b.classIds()),
                f.json.write(b.lines()),
                b.dueOn(),
                b.partialAllowed(),
                id);
          }
          return plan(a, school, plan);
        });
  }

  public Object plan(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    var p = f.one("select * from fee_plans where school_id=? and id=?", school, id);
    p.put("classIds", f.json.value(p.remove("class_ids").toString()));
    p.put("lines", f.json.value(p.get("lines").toString()));
    p.put("totalMinor", total(lines(p.get("lines"))));
    return p;
  }

  public Object plans(Actor a, UUID school, UUID term) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    return map(
        "items",
        f.db.queryForList(
            "select id,term_id as \"termId\",name,state,version,due_on as \"dueOn\" from fee_plans where school_id=? and (?::uuid is null or term_id=?::uuid) order by created_at desc",
            school,
            term,
            term),
        "hasMore",
        false);
  }

  public Object publish(Actor a, UUID school, UUID id, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "fee-plans/" + id + "/publish",
        key,
        map("version", version),
        () -> {
          var p =
              f.one("select * from fee_plans where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) p.get("version")).longValue());
          if (!"DRAFT".equals(p.get("state")))
            throw fail(409, "FEE_PLAN_PUBLISHED", "This plan is already published.");
          f.db.update(
              "update fee_plans set state='PUBLISHED',published_by=?,published_at=now(),version=version+1 where id=?",
              a.accountId(),
              id);
          f.commands.audit(
              school,
              a.accountId(),
              "FEE_PLAN_PUBLISHED",
              "FEE_PLAN",
              id,
              null,
              map("version", version + 1));
          return plan(a, school, id);
        });
  }

  private List<FinanceService.Line> lines(Object raw) {
    var node = f.json.tree(raw instanceof String ? raw.toString() : f.json.write(raw));
    var rows = new ArrayList<FinanceService.Line>();
    for (var l : node)
      rows.add(
          new FinanceService.Line(
              l.path("description").asText(),
              l.path("amountMinor").asLong(),
              l.path("quantity").asInt()));
    return rows;
  }

  private List<UUID> ids(Object raw) {
    var node = f.json.tree(raw.toString());
    var out = new ArrayList<UUID>();
    for (var n : node) out.add(uuid(n.asText()));
    return out;
  }

  public record Preview(List<UUID> classIds) {}

  public Object preview(Actor a, UUID school, UUID id, Preview b, String key) {
    return command(
        a,
        school,
        "fee-plans/" + id + "/batches",
        key,
        b,
        () -> {
          var p =
              f.one(
                  "select * from fee_plans where school_id=? and id=? and state='PUBLISHED'",
                  school,
                  id);
          List<UUID> classes = b.classIds() == null ? ids(p.get("class_ids")) : b.classIds();
          if (!ids(p.get("class_ids")).containsAll(classes))
            throw fail(422, "CLASS_OUTSIDE_PLAN", "Choose classes covered by this fee plan.");
          var r = roster(school, (UUID) p.get("term_id"), classes);
          UUID batch = UUID.randomUUID();
          f.db.update(
              "insert into fee_batches(id,school_id,plan_id,roster,source_signature,created_by) values (?,?,?,?::jsonb,?,?)",
              batch,
              school,
              id,
              f.json.write(r),
              Crypto.hash(f.json.write(r)),
              a.accountId());
          return batch(a, school, batch);
        });
  }

  public Object batch(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    var b = f.one("select * from fee_batches where school_id=? and id=?", school, id);
    var r = f.json.tree(b.get("roster").toString());
    var rows = new ArrayList<Object>();
    for (var n : r.path("students")) {
      UUID student = uuid(n.path("studentId").asText());
      var prior =
          f.db.queryForList(
              "select invoice_id from fee_plan_issues where plan_id=? and student_id=?",
              b.get("plan_id"),
              student);
      rows.add(
          map(
              "studentId",
              student,
              "studentName",
              n.path("fullName").asText(),
              "admissionNumber",
              n.path("admissionNumber").asText(),
              "classId",
              n.path("classId").asText(),
              "action",
              prior.isEmpty() ? "ISSUE" : "ALREADY_ISSUED",
              "invoiceId",
              prior.isEmpty() ? null : prior.get(0).get("invoice_id")));
    }
    return map(
        "id",
        id,
        "planId",
        b.get("plan_id"),
        "state",
        b.get("state"),
        "version",
        b.get("version"),
        "rows",
        rows);
  }

  public Object issue(Actor a, UUID school, UUID id, long version, String key) {
    return command(
        a,
        school,
        "fee-batches/" + id + "/confirm",
        key,
        map("version", version),
        () -> {
          var b =
              f.one("select * from fee_batches where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) b.get("version")).longValue());
          if (!"PREVIEW".equals(b.get("state")))
            throw fail(409, "BATCH_CONFIRMED", "This fee batch has already been confirmed.");
          var p =
              f.one(
                  "select * from fee_plans where school_id=? and id=? and state='PUBLISHED' for update",
                  school,
                  b.get("plan_id"));
          var old = f.json.tree(b.get("roster").toString());
          var classes = new ArrayList<UUID>();
          for (var c : old.path("classes")) classes.add(uuid(c.path("id").asText()));
          var now = roster(school, (UUID) p.get("term_id"), classes);
          if (!Crypto.equal(Crypto.hash(f.json.write(now)), b.get("source_signature").toString()))
            throw fail(
                412,
                "FEE_ROSTER_CHANGED",
                "The class roster changed. Prepare and review a new preview.");
          for (var n : old.path("students")) {
            UUID student = uuid(n.path("studentId").asText());
            if (f.db.queryForObject(
                    "select count(*) from fee_plan_issues where plan_id=? and student_id=?",
                    Integer.class,
                    p.get("id"),
                    student)
                > 0) continue;
            String issueKey =
                UUID.nameUUIDFromBytes(
                        ("fee-plan|" + p.get("id") + "|" + student)
                            .getBytes(StandardCharsets.UTF_8))
                    .toString();
            @SuppressWarnings("unchecked")
            var detail =
                (Map<String, Object>)
                    f.issue(
                        a,
                        school,
                        new FinanceService.InvoiceInput(
                            student,
                            (UUID) p.get("term_id"),
                            p.get("name").toString(),
                            lines(p.get("lines").toString()),
                            ((java.sql.Date) p.get("due_on")).toLocalDate(),
                            Boolean.TRUE.equals(p.get("partial_allowed"))),
                        issueKey);
            Map<?, ?> invoice = (Map<?, ?>) detail.get("invoice");
            f.db.update(
                "insert into fee_plan_issues(plan_id,student_id,term_id,invoice_id) values (?,?,?,?)",
                p.get("id"),
                student,
                p.get("term_id"),
                uuid(invoice.get("id").toString()));
          }
          f.db.update(
              "update fee_batches set state='CONFIRMED',version=version+1,confirmed_at=now() where id=?",
              id);
          f.commands.audit(
              school,
              a.accountId(),
              "FEE_BATCH_ISSUED",
              "FEE_BATCH",
              id,
              null,
              map("planId", p.get("id")));
          return batch(a, school, id);
        });
  }

  public record Adjustment(
      String kind, long amountMinor, UUID paymentId, String reason, String evidenceReference) {}

  public Object requestAdjustment(Actor a, UUID school, UUID invoice, Adjustment b, String key) {
    f.invoice(a, school, invoice);
    return command(
        a,
        school,
        "invoices/" + invoice + "/adjustments",
        key,
        b,
        () -> {
          if (b.kind() == null || !Set.of("CREDIT", "DISCOUNT", "REFUND").contains(b.kind()))
            throw fail(422, "INVALID_ADJUSTMENT", "Choose CREDIT, DISCOUNT or REFUND.");
          FinanceService.amount(b.amountMinor());
          if ("REFUND".equals(b.kind()))
            f.one(
                "select id from payments where school_id=? and invoice_id=? and id=?",
                school,
                invoice,
                b.paymentId());
          else if (b.paymentId() != null)
            throw fail(422, "UNEXPECTED_PAYMENT", "Only refund requests select a payment.");
          UUID id = UUID.randomUUID();
          f.db.update(
              "insert into fee_adjustments(id,school_id,invoice_id,kind,amount_minor,payment_id,reason,evidence_reference,requested_by) values (?,?,?,?,?,?,?,?,?)",
              id,
              school,
              invoice,
              b.kind(),
              b.amountMinor(),
              b.paymentId(),
              text(b.reason(), 5, 500, "Adjustment reason"),
              text(b.evidenceReference(), 5, 240, "Evidence reference"),
              a.accountId());
          f.commands.audit(
              school,
              a.accountId(),
              "FEE_ADJUSTMENT_REQUESTED",
              "FEE_ADJUSTMENT",
              id,
              b.reason(),
              map("invoiceId", invoice, "kind", b.kind(), "amountMinor", b.amountMinor()));
          return adjustment(a, school, id);
        });
  }

  public Object adjustment(Actor a, UUID school, UUID id) {
    var r = f.one("select * from fee_adjustments where school_id=? and id=?", school, id);
    f.invoice(a, school, (UUID) r.get("invoice_id"));
    r.remove("lease_token");
    r.remove("lease_until");
    return r;
  }

  public Object adjustments(Actor a, UUID school, UUID invoice) {
    if (invoice != null) f.invoice(a, school, invoice);
    else a.school(school, "PRINCIPAL", "ACCOUNTS");
    return map(
        "items",
        f.db.queryForList(
            "select id,invoice_id as \"invoiceId\",kind,amount_minor as \"amountMinor\",state,reason,version,created_at as \"createdAt\" from fee_adjustments where school_id=? and (?::uuid is null or invoice_id=?::uuid) order by created_at desc limit 200",
            school,
            invoice,
            invoice),
        "hasMore",
        false);
  }

  public record Decision(boolean approve, String reason) {}

  public Object decide(Actor a, UUID school, UUID id, Decision b, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "fee-adjustments/" + id + "/decision",
        key,
        map("input", b, "version", version),
        () -> {
          var r =
              f.one(
                  "select * from fee_adjustments where school_id=? and id=? for update",
                  school,
                  id);
          expectVersion(version, ((Number) r.get("version")).longValue());
          if (!"REQUESTED".equals(r.get("state")))
            throw fail(409, "ADJUSTMENT_REVIEWED", "This adjustment has already been reviewed.");
          if (a.accountId().equals(r.get("requested_by")))
            throw fail(
                403,
                "INDEPENDENT_APPROVER_REQUIRED",
                "A different principal must approve a request you created. Accounts staff can submit it for principal review.");
          String why = text(b.reason(), 5, 500, "Approval reason");
          if (b.approve()) {
            var i =
                f.one(
                    "select * from invoices where school_id=? and id=? for update",
                    school,
                    r.get("invoice_id"));
            long amount = ((Number) r.get("amount_minor")).longValue();
            if (!"REFUND".equals(r.get("kind"))) {
              if (amount
                  > ((Number) i.get("total_minor")).longValue()
                      - ((Number) i.get("credit_minor")).longValue())
                throw fail(
                    422,
                    "CREDIT_EXCEEDS_CHARGE",
                    "Credits cannot exceed the remaining invoice charge.");
              f.db.update(
                  "update invoices set credit_minor=credit_minor+?,version=version+1 where id=?",
                  amount,
                  i.get("id"));
            } else {
              var p =
                  f.one(
                      "select * from payments where school_id=? and id=? for update",
                      school,
                      r.get("payment_id"));
              long reserved =
                  f.db.queryForObject(
                      "select coalesce(sum(amount_minor),0) from fee_adjustments where payment_id=? and kind='REFUND' and state in ('APPROVED','PROCESSING','UNKNOWN','CONFIRMED')",
                      Long.class,
                      p.get("id"));
              if (amount > ((Number) p.get("amount_minor")).longValue() - reserved)
                throw fail(
                    422,
                    "REFUND_EXCEEDS_PAYMENT",
                    "The payment already has confirmed or reserved refunds covering this amount.");
            }
          }
          f.db.update(
              "update fee_adjustments set state=?,approved_by=?,approval_reason=?,version=version+1 where id=?",
              b.approve() ? "APPROVED" : "REJECTED",
              a.accountId(),
              why,
              id);
          f.commands.audit(
              school,
              a.accountId(),
              b.approve() ? "FEE_ADJUSTMENT_APPROVED" : "FEE_ADJUSTMENT_REJECTED",
              "FEE_ADJUSTMENT",
              id,
              why,
              map("kind", r.get("kind"), "amountMinor", r.get("amount_minor")));
          return adjustment(a, school, id);
        });
  }

  public record RefundConfirmation(String reference, String verificationReference) {}

  public Object confirmRefund(
      Actor a, UUID school, UUID id, RefundConfirmation b, long version, String key) {
    return command(
        a,
        school,
        "fee-adjustments/" + id + "/confirm-refund",
        key,
        map("input", b, "version", version),
        () -> {
          var r =
              f.one(
                  "select * from fee_adjustments where school_id=? and id=? for update",
                  school,
                  id);
          expectVersion(version, ((Number) r.get("version")).longValue());
          if (!"REFUND".equals(r.get("kind")) || !"APPROVED".equals(r.get("state")))
            throw fail(409, "REFUND_NOT_APPROVED", "Only an approved refund can be confirmed.");
          var p =
              f.one(
                  "select * from payments where school_id=? and id=? for update",
                  school,
                  r.get("payment_id"));
          String reference = text(b.reference(), 3, 100, "Refund reference"),
              evidence = text(b.verificationReference(), 5, 240, "Independent refund evidence");
          if ("PAYSTACK".equals(p.get("source"))) {
            var result = f.paystack.refund(reference);
            var transaction = f.paystack.verify(p.get("external_reference").toString());
            boolean transactionMatches =
                result.path("transaction").isObject()
                    ? p.get("external_reference")
                        .equals(result.path("transaction").path("reference").asText())
                    : result.path("transaction").asText().equals(transaction.path("id").asText());
            if (!result.path("amount").isIntegralNumber()
                || !result.path("amount").canConvertToLong()
                || !"test".equals(result.path("domain").asText())
                || !"test".equals(transaction.path("domain").asText())
                || !transactionMatches
                || !"processed".equals(result.path("status").asText())
                || result.path("amount").asLong(-1) != ((Number) r.get("amount_minor")).longValue()
                || !"NGN".equals(result.path("currency").asText()))
              throw fail(
                  422,
                  "PROVIDER_REFUND_NOT_CONFIRMED",
                  "Paystack has not confirmed this exact payment refund.");
          }
          var i =
              f.one(
                  "select * from invoices where school_id=? and id=? for update",
                  school,
                  p.get("invoice_id"));
          long prior =
              f.db.queryForObject(
                  "select coalesce(sum(allocated_refund_minor),0) from fee_adjustments where payment_id=? and state='CONFIRMED'",
                  Long.class,
                  p.get("id"));
          long allocation =
              Math.min(
                  ((Number) r.get("amount_minor")).longValue(),
                  Math.max(0, ((Number) p.get("allocated_minor")).longValue() - prior));
          f.db.update(
              "update invoices set refunded_minor=refunded_minor+?,version=version+1 where id=?",
              allocation,
              i.get("id"));
          f.db.update(
              "update fee_adjustments set state='CONFIRMED',allocated_refund_minor=?,provider_reference=?,evidence_reference=?,confirmed_at=now(),version=version+1 where id=?",
              allocation,
              reference,
              evidence,
              id);
          f.commands.audit(
              school,
              a.accountId(),
              "REFUND_CONFIRMED",
              "FEE_ADJUSTMENT",
              id,
              evidence,
              map("refundReference", reference, "amountMinor", r.get("amount_minor")));
          return adjustment(a, school, id);
        });
  }

  public Object payments(Actor a, UUID school, UUID student, UUID cursor, int limit) {
    var c = a.school(school, "PRINCIPAL", "ACCOUNTS", "PARENT");
    if ("PARENT".equals(c.role())) {
      if (student == null) student = c.childId();
      f.child(a, school, student);
    }
    return page(
        f.db.queryForList(
            "select p.id,p.invoice_id as \"invoiceId\",i.student_id as \"studentId\",p.source,p.external_reference as reference,p.amount_minor as \"amountMinor\",p.allocated_minor as \"allocatedMinor\",p.confirmed_at as \"confirmedAt\" from payments p join invoices i on i.id=p.invoice_id where p.school_id=? and (?::uuid is null or i.student_id=?::uuid) and (?::uuid is null or p.id>?::uuid) order by p.id limit ?",
            school,
            student,
            student,
            cursor,
            cursor,
            limit + 1),
        limit,
        r -> r.get("id").toString());
  }

  public byte[] invoicePdf(Actor a, UUID school, UUID id) {
    var i = f.invoice(a, school, id);
    @SuppressWarnings("unchecked")
    Map<String, Object> d = (Map<String, Object>) f.detail(a, school, id);
    Map<?, ?> w = (Map<?, ?>) d.get("invoice");
    var text = new ArrayList<String>();
    text.add("Invoice reference: " + id);
    text.add("Description: " + i.get("description"));
    text.add("Due: " + i.get("due_on"));
    for (var l : lines(i.get("lines").toString()))
      text.add(
          l.description()
              + " x "
              + l.quantity()
              + ": NGN "
              + money(l.amountMinor() * l.quantity()));
    text.add("Original total: NGN " + money(((Number) i.get("total_minor")).longValue()));
    text.add("Approved credits: NGN " + money(((Number) i.get("credit_minor")).longValue()));
    text.add("Net confirmed payments: NGN " + money(((Number) w.get("paidMinor")).longValue()));
    text.add("Balance: NGN " + money(((Number) w.get("balanceMinor")).longValue()));
    return Documents.pdf("EduCore school invoice", text);
  }

  private String money(long amount) {
    return java.math.BigDecimal.valueOf(amount, 2).toPlainString();
  }

  public byte[] receipt(Actor a, UUID school, UUID id) {
    var p = f.one("select * from payments where school_id=? and id=?", school, id);
    var i = f.invoice(a, school, (UUID) p.get("invoice_id"));
    return Documents.pdf(
        "EduCore confirmed payment receipt",
        List.of(
            "Receipt reference: " + id,
            "Invoice reference: " + i.get("id"),
            "Method: " + p.get("source"),
            "Payment reference: " + p.get("external_reference"),
            "Confirmed amount: NGN " + money(((Number) p.get("amount_minor")).longValue()),
            "Allocated amount: NGN " + money(((Number) p.get("allocated_minor")).longValue()),
            "Confirmed at: " + p.get("confirmed_at"),
            "Any later refunds are recorded separately and do not alter this receipt."));
  }

  public byte[] export(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    var rows =
        f.db.queryForList(
            "select id,student_id,total_minor,credit_minor,paid_minor,refunded_minor,due_on from invoices where school_id=? order by created_at,id limit 10001",
            school);
    if (rows.size() > 10000)
      throw fail(
          422,
          "EXPORT_TOO_LARGE",
          "Use the finance report date/term/student filters instead of truncating invoices.");
    return Documents.csv(
        List.of(
            "invoice_id",
            "student_id",
            "original_kobo",
            "credit_kobo",
            "gross_paid_kobo",
            "refunded_kobo",
            "balance_kobo",
            "due_on"),
        rows.stream()
            .map(
                i ->
                    List.of(
                        i.get("id"),
                        i.get("student_id"),
                        i.get("total_minor"),
                        i.get("credit_minor"),
                        i.get("paid_minor"),
                        i.get("refunded_minor"),
                        FinanceService.balance(i),
                        i.get("due_on")))
            .toList());
  }
}
