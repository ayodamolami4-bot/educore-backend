package com.educore.finance;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class FinanceReportService {
  private final FinanceService s;

  public FinanceReportService(FinanceService s) {
    this.s = s;
  }

  public Object catalogue(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    return map(
        "items",
        List.of(
            map(
                "kind",
                "INVOICE_BALANCES",
                "filters",
                List.of("termId", "studentId", "from", "to")),
            map(
                "kind",
                "CONFIRMED_PAYMENTS",
                "filters",
                List.of("termId", "studentId", "source", "from", "to")),
            map("kind", "REFUNDS", "filters", List.of("termId", "studentId", "from", "to")),
            map("kind", "SETTLEMENTS", "filters", List.of("source", "from", "to"))),
        "formats",
        List.of("JSON", "CSV", "PDF"),
        "maximumRows",
        1000,
        "currency",
        "NGN",
        "amountUnit",
        "KOBO");
  }

  private void filter(
      StringBuilder sql, List<Object> args, String column, Object value, String op) {
    if (value != null) {
      sql.append(" and ").append(column).append(op).append("?");
      args.add(value);
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ReportData build(
      Actor a,
      UUID school,
      String rawKind,
      UUID termId,
      UUID studentId,
      String source,
      LocalDate from,
      LocalDate to) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    String kind = rawKind.toUpperCase(Locale.ROOT);
    if (from != null && to != null && (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 366))
      throw fail(422, "INVALID_REPORT_DATES", "Use ordered report dates within 367 days.");
    if (studentId != null) s.child(a, school, studentId);
    StringBuilder sql = new StringBuilder();
    List<Object> args = new ArrayList<>();
    List<String> columns;
    String dateColumn;
    switch (kind) {
      case "INVOICE_BALANCES" -> {
        if (source != null)
          throw fail(
              422,
              "UNSUPPORTED_REPORT_FILTER",
              "Invoice balances do not filter by payment method.");
        sql.append(
            "select i.id as \"invoiceId\",i.student_id as \"studentId\",i.term_id as \"termId\",i.description,i.total_minor as \"originalKobo\",i.credit_minor as \"creditKobo\",i.paid_minor as \"grossPaidKobo\",i.refunded_minor as \"refundedKobo\",greatest(0,i.total_minor-i.credit_minor-i.paid_minor+i.refunded_minor) as \"balanceKobo\",i.due_on as \"dueOn\",i.due_on<current_date and greatest(0,i.total_minor-i.credit_minor-i.paid_minor+i.refunded_minor)>0 as overdue,i.version from invoices i where i.school_id=?");
        dateColumn = "i.due_on";
        columns =
            List.of(
                "invoiceId",
                "studentId",
                "termId",
                "description",
                "originalKobo",
                "creditKobo",
                "grossPaidKobo",
                "refundedKobo",
                "balanceKobo",
                "dueOn",
                "overdue",
                "version");
      }
      case "CONFIRMED_PAYMENTS" -> {
        if (source != null && !Set.of("PAYSTACK", "CASH", "BANK").contains(source))
          throw fail(422, "INVALID_PAYMENT_SOURCE", "Choose PAYSTACK, CASH or BANK.");
        sql.append(
            "select p.id as \"paymentId\",p.invoice_id as \"invoiceId\",i.student_id as \"studentId\",i.term_id as \"termId\",p.source,p.external_reference as reference,p.amount_minor as \"receivedKobo\",p.allocated_minor as \"allocatedKobo\",p.confirmed_at as \"confirmedAt\" from payments p join invoices i on i.id=p.invoice_id where p.school_id=?");
        dateColumn = "(p.confirmed_at at time zone 'Africa/Lagos')::date";
        columns =
            List.of(
                "paymentId",
                "invoiceId",
                "studentId",
                "termId",
                "source",
                "reference",
                "receivedKobo",
                "allocatedKobo",
                "confirmedAt");
      }
      case "REFUNDS" -> {
        if (source != null)
          throw fail(
              422,
              "UNSUPPORTED_REPORT_FILTER",
              "Refund status uses its approval and external-confirmation records.");
        sql.append(
            "select r.id as \"refundId\",r.invoice_id as \"invoiceId\",i.student_id as \"studentId\",i.term_id as \"termId\",r.payment_id as \"paymentId\",r.amount_minor as \"requestedKobo\",r.allocated_refund_minor as \"allocatedRefundKobo\",r.state,r.version,r.created_at as \"requestedAt\",r.confirmed_at as \"confirmedAt\" from fee_adjustments r join invoices i on i.id=r.invoice_id where r.school_id=? and r.kind='REFUND'");
        dateColumn = "(r.created_at at time zone 'Africa/Lagos')::date";
        columns =
            List.of(
                "refundId",
                "invoiceId",
                "studentId",
                "termId",
                "paymentId",
                "requestedKobo",
                "allocatedRefundKobo",
                "state",
                "version",
                "requestedAt",
                "confirmedAt");
      }
      case "SETTLEMENTS" -> {
        if (termId != null || studentId != null)
          throw fail(
              422,
              "UNSUPPORTED_REPORT_FILTER",
              "A settlement batch may span pupils and terms. Use its source and import-date filters.");
        if (source != null && !Set.of("PAYSTACK", "BANK_STATEMENT").contains(source))
          throw fail(422, "INVALID_SETTLEMENT_SOURCE", "Choose PAYSTACK or BANK_STATEMENT.");
        sql.append(
            "select b.id as \"batchId\",b.source,b.stable_reference as reference,b.state,b.created_at as \"importedAt\",(select count(*) from settlement_rows r where r.batch_id=b.id) as rows,(select coalesce(sum(r.amount_minor),0) from settlement_rows r where r.batch_id=b.id) as \"grossKobo\",(select coalesce(sum(r.fee_minor),0) from settlement_rows r where r.batch_id=b.id) as \"feeKobo\",(select coalesce(sum(r.net_minor),0) from settlement_rows r where r.batch_id=b.id) as \"netKobo\",(select count(*) from settlement_rows r where r.batch_id=b.id and r.issue is not null and r.resolution is null) as \"unresolvedRows\" from settlement_batches b where b.school_id=?");
        dateColumn = "(b.created_at at time zone 'Africa/Lagos')::date";
        columns =
            List.of(
                "batchId",
                "source",
                "reference",
                "state",
                "importedAt",
                "rows",
                "grossKobo",
                "feeKobo",
                "netKobo",
                "unresolvedRows");
      }
      default ->
          throw fail(
              404, "REPORT_NOT_FOUND", "Choose a report from the permitted finance catalogue.");
    }
    args.add(school);
    if (!"SETTLEMENTS".equals(kind)) {
      filter(sql, args, "i.term_id", termId, "=");
      filter(sql, args, "i.student_id", studentId, "=");
    }
    if (source != null)
      filter(sql, args, "SETTLEMENTS".equals(kind) ? "b.source" : "p.source", source, "=");
    filter(sql, args, dateColumn, from, ">=");
    filter(sql, args, dateColumn, to, "<=");
    sql.append(" order by ").append(dateColumn).append(",1 limit 1001");
    return new ReportData(
        kind,
        "Finance: " + kind.replace('_', ' '),
        columns,
        s.db.queryForList(sql.toString(), args.toArray()),
        List.of(
            "All amounts are NGN integer kobo. Student and term IDs refer to this school's records.",
            "Confirmed payments, invoice balances and provider/bank settlement are separate evidence.",
            "Refund approval does not mean money has been returned; only external confirmation changes the financial record."));
  }
}
