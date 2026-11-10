package com.educore.finance;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class FinanceService {
  final JdbcTemplate db;
  final Commands commands;
  final Json json;
  final Crypto crypto;
  final ServiceHttp http;
  final Outbox outbox;
  final Paystack paystack;
  final PaymentSettingsService paymentSettings;
  final String academics;

  public FinanceService(
      JdbcTemplate db,
      Commands commands,
      Json json,
      Crypto crypto,
      ServiceHttp http,
      Outbox outbox,
      Paystack paystack,
      PaymentSettingsService paymentSettings,
      @Value("${educore.academics-url}") String academics) {
    this.db = db;
    this.commands = commands;
    this.json = json;
    this.crypto = crypto;
    this.http = http;
    this.outbox = outbox;
    this.paystack = paystack;
    this.paymentSettings = paymentSettings;
    this.academics = academics;
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "The financial record is unavailable.");
    return rows.get(0);
  }

  Object child(Actor a, UUID school, UUID student) {
    var context = a.school(school, "PRINCIPAL", "ACCOUNTS", "PARENT");
    UUID guardian = null;
    if ("PARENT".equals(context.role())) {
      a.child(student, true);
      guardian = context.subjectId();
    }
    return http.post(
        academics,
        "/internal/student-context",
        map("schoolId", school, "studentId", student, "guardianId", guardian),
        Object.class);
  }

  Map<String, Object> invoice(Actor a, UUID school, UUID id) {
    var row = one("select * from invoices where school_id=? and id=?", school, id);
    child(a, school, (UUID) row.get("student_id"));
    return row;
  }

  public record Line(String description, long amountMinor, int quantity) {}

  public record InvoiceInput(
      UUID studentId,
      UUID termId,
      String description,
      List<Line> lines,
      LocalDate dueOn,
      boolean partialAllowed) {}

  public Object issue(Actor a, UUID school, InvoiceInput b, String key) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    child(a, school, b.studentId());
    http.post(
        academics,
        "/internal/student-context",
        map("schoolId", school, "studentId", b.studentId(), "termId", b.termId()),
        Object.class);
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "invoices",
        key,
        b,
        () -> {
          if (b.lines() == null
              || b.lines().isEmpty()
              || b.lines().size() > 30
              || b.dueOn() == null
              || b.termId() == null)
            throw fail(422, "INVALID_INVOICE", "Provide a term, due date and 1–30 invoice lines.");
          long total = 0;
          for (var l : b.lines()) {
            text(l.description(), 2, 160, "Charge description");
            amount(l.amountMinor());
            if (l.quantity() < 1 || l.quantity() > 100)
              throw fail(422, "INVALID_QUANTITY", "Quantity must be between 1 and 100.");
            try {
              total = Math.addExact(total, Math.multiplyExact(l.amountMinor(), l.quantity()));
            } catch (ArithmeticException ex) {
              throw fail(422, "AMOUNT_TOO_LARGE", "The invoice amount is too large.");
            }
          }
          amount(total);
          UUID id = UUID.randomUUID();
          db.update(
              "insert into invoices(id,school_id,student_id,term_id,description,lines,total_minor,due_on,partial_allowed) values (?,?,?,?,?,?::jsonb,?,?,?)",
              id,
              school,
              b.studentId(),
              b.termId(),
              text(b.description(), 2, 200, "Invoice description"),
              json.write(b.lines()),
              total,
              b.dueOn(),
              b.partialAllowed());
          commands.audit(
              school,
              a.accountId(),
              "INVOICE_ISSUED",
              "INVOICE",
              id,
              null,
              map("totalMinor", total, "currency", "NGN"));
          return detail(a, school, id);
        });
  }

  static void amount(long amount) {
    if (amount < 1 || amount > 1_000_000_000_000L)
      throw fail(
          422, "INVALID_AMOUNT", "Use a positive amount in kobo within the supported limit.");
  }

  static long balance(Map<String, Object> i) {
    return Math.max(
        0,
        ((Number) i.get("total_minor")).longValue()
            - ((Number) i.get("credit_minor")).longValue()
            - ((Number) i.get("paid_minor")).longValue()
            + ((Number) i.get("refunded_minor")).longValue());
  }

  private Object wire(Map<String, Object> i) {
    long total = ((Number) i.get("total_minor")).longValue(),
        paid =
            ((Number) i.get("paid_minor")).longValue()
                - ((Number) i.get("refunded_minor")).longValue();
    long credits = ((Number) i.get("credit_minor")).longValue(), charge = total - credits;
    return map(
        "id",
        i.get("id"),
        "version",
        i.get("version"),
        "studentId",
        i.get("student_id"),
        "termId",
        i.get("term_id"),
        "description",
        i.get("description"),
        "lines",
        json.value(i.get("lines").toString()),
        "currency",
        "NGN",
        "totalMinor",
        total,
        "paidMinor",
        paid,
        "grossPaidMinor",
        i.get("paid_minor"),
        "creditMinor",
        credits,
        "refundedMinor",
        i.get("refunded_minor"),
        "chargeMinor",
        charge,
        "creditBalanceMinor",
        Math.max(0, paid - charge),
        "balanceMinor",
        Math.max(0, charge - paid),
        "status",
        paid >= charge ? "PAID" : paid == 0 ? "UNPAID" : "PART_PAID",
        "dueOn",
        i.get("due_on"),
        "partialAllowed",
        i.get("partial_allowed"));
  }

  public Object detail(Actor a, UUID school, UUID id) {
    var i = invoice(a, school, id);
    return map(
        "invoice",
        wire(i),
        "confirmedPayments",
        db.queryForList(
            "select id,source,external_reference as reference,amount_minor as \"amountMinor\",allocated_minor as \"allocatedMinor\",confirmed_at as \"confirmedAt\" from payments where school_id=? and invoice_id=? order by confirmed_at,id",
            school,
            id));
  }

  public Object list(Actor a, UUID school, UUID student, UUID cursor, int limit) {
    var c = a.school(school, "PRINCIPAL", "ACCOUNTS", "PARENT");
    if ("PARENT".equals(c.role())) {
      if (student == null) student = c.childId();
      child(a, school, student);
    }
    var rows =
        db
            .queryForList(
                "select * from invoices where school_id=? and (?::uuid is null or student_id=?::uuid) and (?::uuid is null or id>?::uuid) order by id limit ?",
                school,
                student,
                student,
                cursor,
                cursor,
                limit + 1)
            .stream()
            .map(this::wire)
            .toList();
    return page(rows, limit, r -> ((Map<?, ?>) r).get("id").toString());
  }

  public record Manual(
      long amountMinor,
      String method,
      String reference,
      String verificationReference,
      String reason) {}

  public Object manual(Actor a, UUID school, UUID id, Manual b, long version, String key) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    invoice(a, school, id);
    amount(b.amountMinor());
    if (b.method() == null || !Set.of("CASH", "BANK").contains(b.method()))
      throw fail(422, "INVALID_METHOD", "Select CASH or independently verified BANK funds.");
    String reference = text(b.reference(), 3, 100, "Payment reference"),
        evidence = text(b.verificationReference(), 5, 240, "Verification reference"),
        reason = text(b.reason(), 5, 500, "Recording reason");
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "invoices/" + id + "/manual-payments",
        key,
        map("payment", b, "version", version),
        () -> {
          var i = one("select * from invoices where school_id=? and id=? for update", school, id);
          expectVersion(version, ((Number) i.get("version")).longValue());
          long balance = balance(i);
          if (b.amountMinor() > balance
              || !Boolean.TRUE.equals(i.get("partial_allowed")) && b.amountMinor() != balance)
            throw fail(
                422,
                "PAYMENT_AMOUNT",
                "The payment must respect the remaining balance and invoice part-payment policy.");
          UUID payment =
              record(
                  i,
                  b.method(),
                  reference,
                  b.amountMinor(),
                  b.amountMinor(),
                  evidence,
                  a.accountId());
          commands.audit(
              school,
              a.accountId(),
              "MANUAL_PAYMENT_CONFIRMED",
              "PAYMENT",
              payment,
              reason,
              map("invoiceId", id, "amountMinor", b.amountMinor(), "method", b.method()));
          return map(
              "paymentId",
              payment,
              "state",
              "CONFIRMED",
              "invoice",
              wire(one("select * from invoices where id=?", id)));
        });
  }

  private UUID record(
      Map<String, Object> invoice,
      String source,
      String reference,
      long amount,
      long allocated,
      String evidence,
      UUID actor) {
    UUID id = UUID.randomUUID();
    db.update(
        "insert into payments(id,school_id,invoice_id,source,external_reference,amount_minor,allocated_minor,verification_reference,recorded_by) values (?,?,?,?,?,?,?,?,?)",
        id,
        invoice.get("school_id"),
        invoice.get("id"),
        source,
        reference,
        amount,
        allocated,
        evidence,
        actor);
    db.update(
        "update invoices set paid_minor=paid_minor+?,version=version+1 where id=?",
        allocated,
        invoice.get("id"));
    outbox.add(
        "communication",
        "CHILD_NOTIFICATION",
        map(
            "schoolId",
            invoice.get("school_id"),
            "childId",
            invoice.get("student_id"),
            "category",
            "FEES",
            "title",
            "Payment confirmed",
            "safePreview",
            "Your school has posted a payment receipt.",
            "target",
            map(
                "screenId",
                "PA12",
                "schoolId",
                invoice.get("school_id"),
                "childId",
                invoice.get("student_id"),
                "resourceId",
                id)));
    return id;
  }

  public Object setting(Actor a, UUID school, String subaccount, String key) {
    return paymentSettings.create(a, school, subaccount, key);
  }

  public record Checkout(long amountMinor, String email) {}

  public Object checkout(Actor a, UUID school, UUID invoiceId, Checkout b, String key) {
    var initial = invoice(a, school, invoiceId);
    paystack.configured();
    String email = email(b.email());
    amount(b.amountMinor());
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "invoices/" + invoiceId + "/checkout",
        key,
        b,
        () -> {
          var i =
              one(
                  "select * from invoices where school_id=? and id=? for update",
                  school,
                  invoiceId);
          long balance = balance(i);
          if (b.amountMinor() > balance
              || !Boolean.TRUE.equals(i.get("partial_allowed")) && b.amountMinor() != balance)
            throw fail(
                422,
                "PAYMENT_AMOUNT",
                "Choose an amount permitted by the current invoice balance.");
          var settings = paymentSettings.checkout(school);
          Object payerSnapshot =
              http.post(
                  academics,
                  "/internal/finance-context",
                  map("schoolId", school, "actor", a, "studentId", i.get("student_id")),
                  Object.class);
          UUID id = UUID.randomUUID();
          String reference = "EC-" + id;
          db.update(
              "insert into payment_intents(id,school_id,invoice_id,actor_id,reference,amount_minor,email_cipher,settlement_subaccount,settlement_settings_version,payer_snapshot) values (?,?,?,?,?,?,?,?,?,?::jsonb)",
              id,
              school,
              invoiceId,
              a.accountId(),
              reference,
              b.amountMinor(),
              crypto.seal(email),
              settings.get("paystack_subaccount"),
              settings.get("version"),
              json.write(payerSnapshot));
          try {
            JsonNode response =
                paystack.initialize(
                    reference,
                    b.amountMinor(),
                    email,
                    (String) settings.get("paystack_subaccount"));
            String authorization = response.path("authorization_url").asText();
            if (!reference.equals(response.path("reference").asText())
                || !authorization.startsWith("https://checkout.paystack.com/"))
              throw fail(
                  503, "INVALID_PROVIDER_RESPONSE", "The provider response requires verification.");
            db.update(
                "update payment_intents set authorization_url=? where id=?", authorization, id);
            return map(
                "id",
                id,
                "reference",
                reference,
                "state",
                "PENDING",
                "authorizationUrl",
                authorization,
                "testMode",
                true);
          } catch (Api.Failure ex) {
            db.update("update payment_intents set state='INIT_UNKNOWN' where id=?", id);
            return map(
                "id",
                id,
                "reference",
                reference,
                "state",
                "INIT_UNKNOWN",
                "message",
                "The checkout outcome is not known yet. Check this reference before creating another payment.",
                "testMode",
                true);
          }
        });
  }

  public Object intent(Actor a, UUID school, UUID id) {
    var p = one("select * from payment_intents where school_id=? and id=?", school, id);
    invoice(a, school, (UUID) p.get("invoice_id"));
    return map(
        "id",
        id,
        "reference",
        p.get("reference"),
        "state",
        p.get("state"),
        "amountMinor",
        p.get("amount_minor"),
        "currency",
        "NGN",
        "authorizationUrl",
        p.get("authorization_url"),
        "version",
        p.get("version"),
        "testMode",
        true);
  }

  public Object verify(Actor a, UUID school, UUID id) {
    intent(a, school, id);
    verifyReference(
        (String) one("select reference from payment_intents where id=?", id).get("reference"));
    return intent(a, school, id);
  }

  public void verifyReference(String reference) {
    var rows = db.queryForList("select * from payment_intents where reference=?", reference);
    if (rows.isEmpty()) return;
    var p = rows.get(0);
    if ("CONFIRMED".equals(p.get("state"))) return;
    if (db.update(
            "update payment_intents set last_verified_at=now(),attempts=attempts+1 where id=? and (last_verified_at is null or last_verified_at<now()-interval '20 seconds')",
            p.get("id"))
        == 0) return;
    JsonNode result = paystack.verify(reference);
    if (!"success".equals(result.path("status").asText())) {
      String status = result.path("status").asText();
      if (Set.of("failed", "abandoned").contains(status))
        db.update(
            "update payment_intents set state='FAILED',version=version+1 where id=? and state<>'CONFIRMED'",
            p.get("id"));
      return;
    }
    commands.transaction(
        () -> {
          var locked = one("select * from payment_intents where id=? for update", p.get("id"));
          if ("CONFIRMED".equals(locked.get("state"))) return null;
          var i = one("select * from invoices where id=? for update", locked.get("invoice_id"));
          long amount = result.path("amount").asLong(-1),
              expected = ((Number) locked.get("amount_minor")).longValue();
          if (!result.path("amount").isIntegralNumber()
              || !result.path("amount").canConvertToLong()
              || !reference.equals(result.path("reference").asText())
              || !"NGN".equals(result.path("currency").asText())
              || amount != expected
              || !"test".equals(result.path("domain").asText())) {
            exception((UUID) locked.get("school_id"), reference, "PROVIDER_MISMATCH");
            db.update(
                "update payment_intents set state='REQUIRES_REVIEW',version=version+1 where id=?",
                locked.get("id"));
            return null;
          }
          long balance = balance(i), allocated = Math.min(balance, amount);
          record(
              i,
              "PAYSTACK",
              reference,
              amount,
              allocated,
              "Paystack server verification",
              (UUID) locked.get("actor_id"));
          if (allocated < amount)
            exception((UUID) locked.get("school_id"), reference, "UNALLOCATED_OVERPAYMENT");
          db.update(
              "update payment_intents set state='CONFIRMED',version=version+1 where id=?",
              locked.get("id"));
          return null;
        });
  }

  private void exception(UUID school, String reference, String code) {
    db.update(
        "insert into finance_exceptions(id,school_id,reference,code) values (?,?,?,?) on conflict(reference,code) do nothing",
        UUID.randomUUID(),
        school,
        reference,
        code);
  }

  public Object webhook(byte[] body, String signature) {
    if (!paystack.signature(body, signature))
      throw fail(401, "INVALID_WEBHOOK_SIGNATURE", "The webhook signature could not be verified.");
    if (body.length > 262144)
      throw fail(413, "WEBHOOK_TOO_LARGE", "The webhook exceeds the permitted size.");
    JsonNode event;
    try {
      event = json.tree(new String(body, java.nio.charset.StandardCharsets.UTF_8));
    } catch (IllegalArgumentException ex) {
      throw fail(422, "INVALID_WEBHOOK", "The webhook body is invalid.");
    }
    String kind = event.path("event").asText(),
        reference = event.path("data").path("reference").asText();
    if (reference.length() > 80 || kind.length() > 80)
      throw fail(422, "INVALID_WEBHOOK", "The webhook reference is invalid.");
    commands.transaction(
        () -> {
          int inserted =
              db.update(
                  "insert into provider_events(event_hash,reference,event_kind) values (?,?,?) on conflict do nothing",
                  Crypto.hash(new String(body, java.nio.charset.StandardCharsets.UTF_8)),
                  reference,
                  kind);
          if (inserted > 0 && "charge.success".equals(kind))
            db.update(
                "update payment_intents set last_verified_at=null,attempts=0 where reference=? and state<>'CONFIRMED'",
                reference);
          return null;
        });
    return map("accepted", true);
  }

  @Scheduled(fixedDelayString = "${educore.payment-verification-delay-ms:30000}")
  public void reconcilePending() {
    for (var p :
        db.queryForList(
            "select reference from payment_intents where state in ('PENDING','INIT_UNKNOWN') and attempts<20 and (last_verified_at is null or last_verified_at<now()-interval '30 seconds') order by created_at limit 10")) {
      try {
        verifyReference((String) p.get("reference"));
      } catch (Api.Failure ignored) {
        /* pending is preserved; no secrets or financial payloads are logged */
      }
    }
  }

  public Object exceptions(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    return map(
        "items",
        db.queryForList(
            "select id,reference,code,created_at as \"createdAt\" from finance_exceptions where school_id=? order by created_at desc limit 100",
            school),
        "hasMore",
        false,
        "scope",
        "Transaction verification exceptions; provider settlement reconciliation is a separate workflow.");
  }
}
