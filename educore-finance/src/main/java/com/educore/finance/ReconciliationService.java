package com.educore.finance;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {
  private final FinanceService f;

  public ReconciliationService(FinanceService f) {
    this.f = f;
  }

  public record Row(String reference, long amountMinor, long feeMinor, long netMinor) {}

  public record Statement(String reference, List<Row> rows) {}

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

  private UUID store(
      Actor a, UUID school, String source, String ref, List<Row> rows, Object summary) {
    if (rows == null || rows.isEmpty() || rows.size() > 1000)
      throw fail(422, "INVALID_SETTLEMENT_ROWS", "Review one to 1,000 statement rows per batch.");
    Set<String> refs = new HashSet<>();
    for (var r : rows) {
      text(r.reference(), 3, 100, "Transaction reference");
      FinanceService.amount(r.amountMinor());
      if (!refs.add(r.reference())
          || r.feeMinor() < 0
          || r.feeMinor() > r.amountMinor()
          || r.netMinor() != r.amountMinor() - r.feeMinor())
        throw fail(
            422,
            "INVALID_SETTLEMENT_ROW",
            "Use distinct references with consistent gross, fee and net amounts in kobo.");
    }
    var snapshot = map("summary", summary, "rows", rows);
    String payload = f.json.write(snapshot);
    var existing =
        f.db.queryForList(
            "select id,snapshot from settlement_batches where school_id=? and source=? and stable_reference=?",
            school,
            source,
            ref);
    if (!existing.isEmpty()) {
      if (!f.json.tree(existing.get(0).get("snapshot").toString()).equals(f.json.tree(payload)))
        throw fail(
            409,
            "STATEMENT_REFERENCE_REUSED",
            "This statement reference already identifies a different immutable statement.");
      return (UUID) existing.get(0).get("id");
    }
    UUID id = UUID.randomUUID();
    f.db.update(
        "insert into settlement_batches(id,school_id,source,stable_reference,snapshot,state,created_by) values (?,?,?,?,?::jsonb,'MATCHED',?)",
        id,
        school,
        source,
        ref,
        payload,
        a.accountId());
    boolean issues = false;
    for (var r : rows) {
      var payments =
          f.db.queryForList(
              "select id,amount_minor from payments where school_id=? and source=? and external_reference=?",
              school,
              "PAYSTACK".equals(source) ? "PAYSTACK" : "BANK",
              r.reference());
      String issue =
          payments.isEmpty()
              ? "PAYMENT_NOT_CONFIRMED"
              : ((Number) payments.get(0).get("amount_minor")).longValue() != r.amountMinor()
                  ? "AMOUNT_MISMATCH"
                  : null;
      UUID payment = payments.isEmpty() ? null : (UUID) payments.get(0).get("id");
      if (payment != null
          && f.db.queryForObject(
                  "select count(*) from settlement_rows r join settlement_batches b on b.id=r.batch_id where b.school_id=? and r.payment_id=? and b.source=?",
                  Integer.class,
                  school,
                  payment,
                  source)
              > 0) issue = "PAYMENT_IN_ANOTHER_SETTLEMENT";
      issues |= issue != null;
      f.db.update(
          "insert into settlement_rows(id,batch_id,reference,payment_id,amount_minor,fee_minor,net_minor,issue) values (?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          id,
          r.reference(),
          payment,
          r.amountMinor(),
          r.feeMinor(),
          r.netMinor(),
          issue);
    }
    if (issues) f.db.update("update settlement_batches set state='REQUIRES_REVIEW' where id=?", id);
    f.commands.audit(
        school,
        a.accountId(),
        "SETTLEMENT_RECONCILED",
        "SETTLEMENT",
        id,
        null,
        map("source", source, "reference", ref, "rows", rows.size(), "hasExceptions", issues));
    return id;
  }

  public Object statement(Actor a, UUID school, Statement b, String key) {
    return command(
        a,
        school,
        "reconciliation/statements",
        key,
        b,
        () -> {
          f.db.queryForObject(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              Object.class,
              "settlement|" + school);
          UUID id =
              store(
                  a,
                  school,
                  "BANK_STATEMENT",
                  text(b.reference(), 3, 100, "Statement reference"),
                  b.rows(),
                  map("currency", "NGN", "verification", "REVIEWED_BANK_STATEMENT"));
          return detail(a, school, id);
        });
  }

  public record Period(LocalDate from, LocalDate to) {}

  public Object sync(Actor a, UUID school, Period b, String key) {
    role(a, school);
    if (b.from() == null
        || b.to() == null
        || b.to().isBefore(b.from())
        || java.time.temporal.ChronoUnit.DAYS.between(b.from(), b.to()) > 31)
      throw fail(
          422, "INVALID_SETTLEMENT_PERIOD", "Choose a settlement period of at most 31 days.");
    return command(
        a,
        school,
        "reconciliation/paystack-sync",
        key,
        b,
        () -> {
          f.db.queryForObject(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              Object.class,
              "settlement|" + school);
          var settings =
              f.one(
                  "select paystack_subaccount from school_payment_settings where school_id=?",
                  school);
          var result = new ArrayList<Object>();
          boolean done = false;
          for (int page = 1; page <= 10; page++) {
            var list =
                f.paystack.settlements(
                    settings.get("paystack_subaccount").toString(), b.from(), b.to(), page);
            if (!list.isArray())
              throw fail(
                  503,
                  "PROVIDER_SETTLEMENT_RESPONSE",
                  "The provider returned an invalid settlement page.");
            for (var n : list) {
              if (!"test".equals(n.path("domain").asText())
                  || !"NGN".equals(n.path("currency").asText())
                  || !"success".equals(n.path("status").asText()))
                throw fail(
                    422,
                    "PROVIDER_SETTLEMENT_MISMATCH",
                    "Only confirmed NGN test-mode settlements can be imported.");
              String ref = n.path("id").asText();
              var rows = new ArrayList<Row>();
              boolean txDone = false;
              for (int tp = 1; tp <= 10; tp++) {
                var tx = f.paystack.settlementTransactions(ref, tp);
                if (!tx.isArray())
                  throw fail(
                      503,
                      "PROVIDER_SETTLEMENT_RESPONSE",
                      "The provider returned an invalid transaction page.");
                for (var t : tx) {
                  if (!"test".equals(t.path("domain").asText())
                      || !"NGN".equals(t.path("currency").asText())
                      || !"success".equals(t.path("status").asText()))
                    throw fail(
                        422,
                        "PROVIDER_TRANSACTION_MISMATCH",
                        "Settlement transactions must be confirmed NGN test transactions.");
                  long gross = t.path("amount").asLong(-1), fee = t.path("fees").asLong(-1);
                  rows.add(new Row(t.path("reference").asText(), gross, fee, gross - fee));
                }
                if (tx.size() < 100) {
                  txDone = true;
                  break;
                }
              }
              if (!txDone)
                throw fail(
                    422,
                    "SETTLEMENT_TOO_LARGE",
                    "Review this settlement in a dedicated finance process; its transaction count exceeds this batch limit.");
              long net = rows.stream().mapToLong(Row::netMinor).sum();
              long effective = n.path("effective_amount").asLong(-1);
              var summary =
                  map(
                      "providerSettlementId",
                      ref,
                      "currency",
                      "NGN",
                      "grossMinor",
                      n.path("total_processed").asLong(-1),
                      "feeMinor",
                      n.path("total_fees").asLong(-1),
                      "effectiveMinor",
                      effective,
                      "transactionsNetMinor",
                      net,
                      "settledOn",
                      n.path("settlement_date").asText());
              UUID id = store(a, school, "PAYSTACK", ref, rows, summary);
              if (net != effective) {
                f.db.update(
                    "insert into settlement_rows(id,batch_id,reference,amount_minor,fee_minor,net_minor,issue) values (?,?,?,0,0,0,'SETTLEMENT_NET_MISMATCH') on conflict(batch_id,reference) do nothing",
                    UUID.randomUUID(),
                    id,
                    "SETTLEMENT-SUMMARY");
                f.db.update("update settlement_batches set state='REQUIRES_REVIEW' where id=?", id);
              }
              result.add(detail(a, school, id));
            }
            if (list.size() < 100) {
              done = true;
              break;
            }
          }
          if (!done)
            throw fail(
                422, "SETTLEMENT_PERIOD_TOO_LARGE", "Choose a shorter settlement date range.");
          return map("items", result, "currency", "NGN");
        });
  }

  public Object detail(Actor a, UUID school, UUID id) {
    role(a, school);
    var b =
        f.one(
            "select id,source,stable_reference as reference,state,created_at as \"createdAt\" from settlement_batches where school_id=? and id=?",
            school,
            id);
    b.put(
        "rows",
        f.db.queryForList(
            "select id,reference,payment_id as \"paymentId\",amount_minor as \"amountMinor\",fee_minor as \"feeMinor\",net_minor as \"netMinor\",issue,resolution,resolution_reason as \"resolutionReason\",version from settlement_rows where batch_id=? order by reference",
            id));
    return b;
  }

  public Object list(Actor a, UUID school, UUID cursor, int limit) {
    role(a, school);
    return page(
        f.db.queryForList(
            "select id,source,stable_reference as reference,state,created_at as \"createdAt\" from settlement_batches where school_id=? and (?::uuid is null or id>?::uuid) order by id limit ?",
            school,
            cursor,
            cursor,
            limit + 1),
        limit,
        r -> r.get("id").toString());
  }

  public record Resolution(String resolution, String reason) {}

  public Object resolve(
      Actor a, UUID school, UUID batch, UUID row, Resolution b, long version, String key) {
    a.school(school, "PRINCIPAL");
    return command(
        a,
        school,
        "reconciliation/settlements/" + batch + "/rows/" + row + "/resolution",
        key,
        map("input", b, "version", version),
        () -> {
          f.one(
              "select id from settlement_batches where school_id=? and id=? for update",
              school,
              batch);
          var r =
              f.one(
                  "select * from settlement_rows where batch_id=? and id=? for update", batch, row);
          expectVersion(version, ((Number) r.get("version")).longValue());
          if (r.get("issue") == null || r.get("resolution") != null)
            throw fail(409, "EXCEPTION_NOT_OPEN", "Resolve an open reconciliation exception.");
          if (b.resolution() == null
              || !Set.of(
                      "EVIDENCE_RECORDED",
                      "PROVIDER_FOLLOW_UP",
                      "DUPLICATE_STATEMENT",
                      "OUTSIDE_SCHOOL_SCOPE")
                  .contains(b.resolution()))
            throw fail(
                422, "INVALID_RESOLUTION", "Choose a supported reviewed exception resolution.");
          String why = text(b.reason(), 10, 500, "Resolution evidence and reason");
          f.db.update(
              "update settlement_rows set resolution=?,resolution_reason=?,resolved_by=?,resolved_at=now(),version=version+1 where id=?",
              b.resolution(),
              why,
              a.accountId(),
              row);
          if (f.db.queryForObject(
                  "select count(*) from settlement_rows where batch_id=? and issue is not null and resolution is null",
                  Integer.class,
                  batch)
              == 0) f.db.update("update settlement_batches set state='REVIEWED' where id=?", batch);
          f.commands.audit(
              school,
              a.accountId(),
              "RECONCILIATION_EXCEPTION_REVIEWED",
              "SETTLEMENT",
              batch,
              why,
              map("rowId", row, "issue", r.get("issue"), "resolution", b.resolution()));
          return detail(a, school, batch);
        });
  }

  public Object overview(Actor a, UUID school) {
    role(a, school);
    return f.one(
        "select count(*) as \"invoiceCount\",coalesce(sum(total_minor-credit_minor),0) as \"chargedMinor\",coalesce(sum(paid_minor-refunded_minor),0) as \"netPaidMinor\",coalesce(sum(greatest(0,total_minor-credit_minor-paid_minor+refunded_minor)),0) as \"outstandingMinor\",count(*) filter(where due_on<current_date and total_minor-credit_minor-paid_minor+refunded_minor>0) as \"overdueInvoices\" from invoices where school_id=?",
        school);
  }
}
