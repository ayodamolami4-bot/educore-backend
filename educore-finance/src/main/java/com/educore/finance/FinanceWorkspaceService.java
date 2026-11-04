package com.educore.finance;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FinanceWorkspaceService {
  private final FinanceService f;
  private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

  public FinanceWorkspaceService(FinanceService f) {
    this.f = f;
  }

  public Object feeStatus(Actor a, UUID school, UUID student, UUID term, UUID cursor, int limit) {
    a.school(school, "PRINCIPAL", "ACCOUNTS", "SCHOOL_OFFICE");
    if (student == null)
      throw fail(422, "STUDENT_REQUIRED", "Choose the pupil whose fee status is being reviewed.");
    Object pupil =
        f.http.post(
            f.academics,
            "/internal/student-context",
            map("schoolId", school, "studentId", student, "termId", term),
            Object.class);
    LocalDate today = LocalDate.now(LAGOS);
    var summary =
        f.one(
            "select count(*) as \"invoiceCount\",count(*) filter(where greatest(0,total_minor-credit_minor-paid_minor+refunded_minor)>0) as \"unpaidInvoiceCount\",count(*) filter(where due_on<? and greatest(0,total_minor-credit_minor-paid_minor+refunded_minor)>0) as \"overdueInvoiceCount\",coalesce(sum(greatest(0,total_minor-credit_minor-paid_minor+refunded_minor)),0) as \"outstandingMinor\" from invoices where school_id=? and student_id=? and (?::uuid is null or term_id=?::uuid)",
            today,
            school,
            student,
            term,
            term);
    if (cursor != null)
      f.one(
          "select id from invoices where school_id=? and student_id=? and id=?",
          school,
          student,
          cursor);
    var rows =
        f.db.queryForList(
            "select id,term_id,description,total_minor,credit_minor,paid_minor,refunded_minor,due_on from invoices where school_id=? and student_id=? and (?::uuid is null or term_id=?::uuid) and (?::uuid is null or id>?::uuid) order by id limit ?",
            school,
            student,
            term,
            term,
            cursor,
            cursor,
            limit + 1);
    var items = new ArrayList<Map<String, Object>>();
    for (var r : rows) {
      long balance = FinanceService.balance(r);
      long net =
          ((Number) r.get("paid_minor")).longValue()
              - ((Number) r.get("refunded_minor")).longValue();
      items.add(
          map(
              "id",
              r.get("id"),
              "termId",
              r.get("term_id"),
              "description",
              r.get("description"),
              "balanceMinor",
              balance,
              "dueOn",
              r.get("due_on"),
              "overdue",
              balance > 0 && ((java.sql.Date) r.get("due_on")).toLocalDate().isBefore(today),
              "status",
              balance == 0 ? "PAID" : net == 0 ? "UNPAID" : "PART_PAID"));
    }
    var paged = page(items, limit, r -> r.get("id").toString());
    var result =
        map("items", paged.items(), "nextCursor", paged.nextCursor(), "hasMore", paged.hasMore());
    result.putAll(
        map(
            "student",
            pupil,
            "termId",
            term,
            "currency",
            "NGN",
            "asOf",
            today,
            "readOnly",
            true,
            "summary",
            summary));
    return result;
  }

  public Object attempts(
      Actor a,
      UUID school,
      UUID student,
      UUID invoice,
      String state,
      String reference,
      LocalDate from,
      LocalDate to,
      UUID cursor,
      int limit) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    LocalDate end = to == null ? LocalDate.now(LAGOS) : to,
        start = from == null ? end.minusDays(30) : from;
    if (start.isAfter(end) || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 366)
      throw fail(422, "INVALID_PAYMENT_RANGE", "Use a checkout history range of at most 366 days.");
    if (state != null
        && !Set.of("PENDING", "INIT_UNKNOWN", "CONFIRMED", "FAILED", "REQUIRES_REVIEW")
            .contains(state))
      throw fail(422, "INVALID_CHECKOUT_STATE", "Choose a supported payment-intent state.");
    if (reference != null && !reference.matches("[A-Za-z0-9.=-]{1,80}"))
      throw fail(422, "INVALID_REFERENCE", "Use the exact checkout reference.");
    if (student != null)
      f.http.post(
          f.academics,
          "/internal/student-context",
          map("schoolId", school, "studentId", student),
          Object.class);
    if (invoice != null)
      f.one("select id from invoices where school_id=? and id=?", school, invoice);
    Object cursorDate = null;
    if (cursor != null)
      cursorDate =
          f.one("select created_at from payment_intents where school_id=? and id=?", school, cursor)
              .get("created_at");
    var rows =
        f.db.queryForList(
            "select p.*,i.student_id,i.term_id from payment_intents p join invoices i on i.school_id=p.school_id and i.id=p.invoice_id where p.school_id=? and p.created_at>=? and p.created_at<? and (?::uuid is null or i.student_id=?::uuid) and (?::uuid is null or p.invoice_id=?::uuid) and (?::text is null or p.state=?::text) and (?::text is null or p.reference=?::text) and (?::uuid is null or (p.created_at,p.id)<(?::timestamptz,?::uuid)) order by p.created_at desc,p.id desc limit ?",
            school,
            Timestamp.from(start.atStartOfDay(LAGOS).toInstant()),
            Timestamp.from(end.plusDays(1).atStartOfDay(LAGOS).toInstant()),
            student,
            student,
            invoice,
            invoice,
            state,
            state,
            reference,
            reference,
            cursor,
            cursorDate,
            cursor,
            limit + 1);
    var items = new ArrayList<Map<String, Object>>();
    for (var r : rows) {
      Object snapshot = r.get("payer_snapshot");
      items.add(
          map(
              "id",
              r.get("id"),
              "invoiceId",
              r.get("invoice_id"),
              "studentId",
              r.get("student_id"),
              "termId",
              r.get("term_id"),
              "reference",
              r.get("reference"),
              "method",
              "PAYSTACK",
              "state",
              r.get("state"),
              "amountMinor",
              r.get("amount_minor"),
              "currency",
              "NGN",
              "testMode",
              true,
              "createdAt",
              r.get("created_at"),
              "lastVerifiedAt",
              r.get("last_verified_at"),
              "verificationAttempts",
              r.get("attempts"),
              "version",
              r.get("version"),
              "initializationContext",
              snapshot == null ? null : f.json.value(snapshot.toString()),
              "snapshotAvailable",
              snapshot != null,
              "settlementSubaccount",
              r.get("settlement_subaccount"),
              "settlementSettingsVersion",
              r.get("settlement_settings_version")));
    }
    var paged = page(items, limit, r -> r.get("id").toString());
    var result =
        map("items", paged.items(), "nextCursor", paged.nextCursor(), "hasMore", paged.hasMore());
    result.putAll(map("from", start, "to", end, "provider", "PAYSTACK", "testMode", true));
    return result;
  }
}
