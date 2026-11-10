package com.educore.finance;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentSettingsService {
  private final JdbcTemplate db;
  private final Commands commands;
  private final Paystack paystack;

  public PaymentSettingsService(JdbcTemplate db, Commands commands, Paystack paystack) {
    this.db = db;
    this.commands = commands;
    this.paystack = paystack;
  }

  public record Update(String subaccount, Boolean checkoutEnabled, String reason) {}

  private void subaccount(String value) {
    if (value == null || !value.matches("ACCT_[A-Za-z0-9]{4,70}"))
      throw fail(
          422, "INVALID_SUBACCOUNT", "Provide the school's approved Paystack test subaccount.");
  }

  private void lock(UUID school) {
    db.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        "payment-settings:" + school);
  }

  private void bind(UUID school, String subaccount) {
    db.update(
        "insert into school_settlement_accounts(paystack_subaccount,school_id) values (?,?) on conflict do nothing",
        subaccount,
        school);
    var owner =
        db.queryForMap(
            "select school_id from school_settlement_accounts where paystack_subaccount=?",
            subaccount);
    if (!school.equals(owner.get("school_id")))
      throw fail(
          409,
          "SUBACCOUNT_UNAVAILABLE",
          "This settlement reference is unavailable for this school. Review the approved school configuration.");
  }

  private void revision(
      Actor a, UUID school, long version, String subaccount, boolean enabled, String reason) {
    db.update(
        "insert into payment_setting_revisions(school_id,version,paystack_subaccount,checkout_enabled,actor_id,reason,source) values (?,?,?,?,?,?,'COMMAND')",
        school,
        version,
        subaccount,
        enabled,
        a.accountId(),
        reason);
    commands.audit(
        school,
        a.accountId(),
        "PAYMENT_SETTINGS_UPDATED",
        "SCHOOL",
        school,
        reason,
        map("version", version, "checkoutEnabled", enabled));
  }

  public Object create(Actor a, UUID school, String subaccount, String key) {
    a.school(school, "PRINCIPAL");
    subaccount(subaccount);
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "payment-settings",
        key,
        map("subaccount", subaccount),
        () -> {
          lock(school);
          if (!db.queryForList(
                  "select school_id from school_payment_settings where school_id=?", school)
              .isEmpty())
            throw fail(
                409,
                "PAYMENT_SETTINGS_EXIST",
                "Reload the saved settings and use the versioned update action.");
          bind(school, subaccount);
          db.update(
              "insert into school_payment_settings(school_id,paystack_subaccount,updated_by) values (?,?,?)",
              school,
              subaccount,
              a.accountId());
          revision(
              a,
              school,
              1,
              subaccount,
              true,
              "Principal approved the initial test settlement configuration");
          return view(a, school);
        });
  }

  public Object update(Actor a, UUID school, Update b, long version, String key) {
    a.school(school, "PRINCIPAL");
    subaccount(b.subaccount());
    if (b.checkoutEnabled() == null)
      throw fail(422, "CHECKOUT_STATE_REQUIRED", "Choose whether new test checkouts are enabled.");
    String why = text(b.reason(), 5, 500, "Settlement configuration change reason");
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "payment-settings/update",
        key,
        map("input", b, "version", version),
        () -> {
          lock(school);
          var rows =
              db.queryForList(
                  "select * from school_payment_settings where school_id=? for update", school);
          if (rows.isEmpty())
            throw fail(
                404, "NOT_FOUND", "Create the initial school settlement configuration first.");
          expectVersion(version, ((Number) rows.get(0).get("version")).longValue());
          bind(school, b.subaccount());
          db.update(
              "update school_payment_settings set paystack_subaccount=?,checkout_enabled=?,version=version+1,updated_at=now(),updated_by=? where school_id=?",
              b.subaccount(),
              b.checkoutEnabled(),
              a.accountId(),
              school);
          revision(a, school, version + 1, b.subaccount(), b.checkoutEnabled(), why);
          return view(a, school);
        });
  }

  public Object view(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    var rows = db.queryForList("select * from school_payment_settings where school_id=?", school);
    var row = rows.isEmpty() ? null : rows.get(0);
    boolean enabled = row != null && Boolean.TRUE.equals(row.get("checkout_enabled"));
    return map(
        "schoolId",
        school,
        "testMode",
        true,
        "configured",
        row != null,
        "providerConfigured",
        paystack.isConfigured(),
        "providerOwnershipValidation",
        "NOT_PERFORMED_BY_THIS_API",
        "knownSubaccounts",
        db.queryForList(
            "select paystack_subaccount as subaccount,first_recorded_at as \"firstRecordedAt\" from school_settlement_accounts where school_id=? order by first_recorded_at desc,paystack_subaccount limit 200",
            school),
        "checkoutEnabled",
        enabled,
        "ready",
        enabled && paystack.isConfigured(),
        "version",
        row == null ? null : row.get("version"),
        "subaccount",
        row == null ? null : row.get("paystack_subaccount"),
        "updatedAt",
        row == null ? null : row.get("updated_at"));
  }

  public Object history(Actor a, UUID school, Long beforeVersion, int limit) {
    a.school(school, "PRINCIPAL", "ACCOUNTS");
    if (beforeVersion != null && beforeVersion < 1)
      throw fail(422, "INVALID_VERSION", "Use a positive settings-history version.");
    return page(
        db.queryForList(
            "select version,paystack_subaccount as \"subaccount\",checkout_enabled as \"checkoutEnabled\",actor_id as \"actorId\",reason,source,recorded_at as \"recordedAt\" from payment_setting_revisions where school_id=? and (?::bigint is null or version<?::bigint) order by version desc limit ?",
            school,
            beforeVersion,
            beforeVersion,
            limit + 1),
        limit,
        r -> r.get("version").toString());
  }

  Map<String, Object> checkout(UUID school) {
    var rows =
        db.queryForList(
            "select * from school_payment_settings where school_id=? for share", school);
    if (rows.isEmpty())
      throw fail(
          503,
          "SCHOOL_SETTLEMENT_NOT_CONFIGURED",
          "The school has not configured its approved Paystack test settlement account.");
    var row = rows.get(0);
    if (!Boolean.TRUE.equals(row.get("checkout_enabled")))
      throw fail(
          409,
          "SCHOOL_CHECKOUT_DISABLED",
          "The school has paused new online checkouts. Existing payments can still be verified.");
    return row;
  }
}
