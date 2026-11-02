package com.educore.identity;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
  private final JdbcTemplate db;
  private final Commands commands;
  private final Crypto crypto;
  private final Json json;
  private final Outbox outbox;
  private final PasswordEncoder passwords;
  private final String dummyHash;

  public IdentityService(
      JdbcTemplate db,
      Commands commands,
      Crypto crypto,
      Json json,
      Outbox outbox,
      PasswordEncoder passwords) {
    this.db = db;
    this.commands = commands;
    this.crypto = crypto;
    this.json = json;
    this.outbox = outbox;
    this.passwords = passwords;
    dummyHash = passwords.encode(Crypto.token());
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "The requested record is unavailable.");
    return rows.get(0);
  }

  private static Instant instant(Object o) {
    return o instanceof Timestamp t ? t.toInstant() : ((OffsetDateTime) o).toInstant();
  }

  private Map<String, Object> account(UUID id) {
    return one("select * from accounts where id=?", id);
  }

  private Map<String, Object> identifier(String value) {
    var rows =
        db.queryForList(
            "select * from accounts where lower(login_id)=? or lower(verified_email)=?",
            value.strip().toLowerCase(Locale.ROOT),
            value.strip().toLowerCase(Locale.ROOT));
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static Api.Failure invalid() {
    return fail(
        401, "INVALID_CREDENTIALS", "The credentials or one-time proof could not be verified.");
  }

  public void limit(String category, String input, int maximum) {
    int n =
        db.queryForObject(
            """
   insert into rate_buckets(bucket_key,window_start,attempts) values (?,now(),1)
   on conflict(bucket_key) do update set attempts=case when rate_buckets.window_start<now()-interval '5 minutes' then 1 else rate_buckets.attempts+1 end,
   window_start=case when rate_buckets.window_start<now()-interval '5 minutes' then now() else rate_buckets.window_start end returning attempts
   """,
            Integer.class,
            Crypto.hash(category + ":" + Objects.toString(input, "")));
    if (n > maximum)
      throw new Api.Failure(
          429, "RATE_LIMITED", "Too many attempts. Wait a few minutes before trying again.", true);
  }

  private void passwordPolicy(String value) {
    if (value == null
        || value.codePointCount(0, value.length()) < 15
        || value.codePointCount(0, value.length()) > 128)
      throw fail(422, "PASSWORD_LENGTH", "Use a password of 15–128 characters.");
    String lowered = value.toLowerCase(Locale.ROOT);
    if (Set.of(
                "passwordpassword",
                "123456789012345",
                "qwertyuiopasdfgh",
                "letmeinletmein123",
                "password123456789")
            .contains(lowered)
        || lowered.chars().distinct().count() < 4)
      throw fail(422, "COMMON_PASSWORD", "Choose a less common password or memorable phrase.");
  }

  public Object login(
      String identifier, String password, String installationId, String deviceName) {
    identifier = text(identifier, 1, 254, "Login ID or verified email");
    limit("login", identifier.toLowerCase(Locale.ROOT), 10);
    String login = identifier;
    String installation = text(installationId, 1, 100, "Installation");
    String device = text(deviceName, 1, 100, "Device name");
    var acc = identifier(login);
    String hash =
        acc == null || acc.get("password_hash") == null
            ? dummyHash
            : (String) acc.get("password_hash");
    boolean matches =
        password != null && password.length() <= 512 && passwords.matches(password, hash);
    if (!matches || acc == null || !"ACTIVE".equals(acc.get("status"))) throw invalid();
    UUID accountId = (UUID) acc.get("id");
    String access = Crypto.token(), refresh = Crypto.token();
    UUID session = UUID.randomUUID();
    Instant expiry = Instant.now().plus(Duration.ofDays(30));
    commands.transaction(
        () -> {
          db.update(
              "insert into sessions(id,account_id,installation_id,device_name,access_hash,access_expires_at,refresh_hash,refresh_expires_at) values (?,?,?,?,?,now()+interval '15 minutes',?,?)",
              session,
              accountId,
              installation,
              device,
              Crypto.hash(access),
              Crypto.hash(refresh),
              Timestamp.from(expiry));
          return null;
        });
    return tokens(accountId, session, access, refresh, expiry);
  }

  private Object tokens(
      UUID account, UUID session, String access, String refresh, Instant expires) {
    int count =
        db.queryForObject(
            "select count(*) from memberships where account_id=? and active",
            Integer.class,
            account);
    return map(
        "accessToken",
        access,
        "refreshToken",
        refresh,
        "tokenType",
        "Bearer",
        "expiresIn",
        900,
        "refreshExpiresAt",
        expires,
        "sessionId",
        session,
        "accountId",
        account,
        "contextCount",
        count,
        "contextPath",
        "/me/contexts");
  }

  public Object refresh(String token) {
    if (token == null || token.length() > 128) throw invalid();
    String hash = Crypto.hash(token);
    Object result =
        commands.transaction(
            () -> {
              var rows =
                  db.queryForList(
                      "select s.*,a.status from sessions s join accounts a on a.id=s.account_id where refresh_hash=? for update of s",
                      hash);
              if (rows.isEmpty()) {
                var spent =
                    db.queryForList(
                        "select session_id from spent_refresh_tokens where token_hash=?", hash);
                if (!spent.isEmpty()) revoke((UUID) spent.get(0).get("session_id"));
                return null;
              }
              var s = rows.get(0);
              UUID id = (UUID) s.get("id");
              if (s.get("revoked_at") != null
                  || !"ACTIVE".equals(s.get("status"))
                  || instant(s.get("refresh_expires_at")).isBefore(Instant.now())) return null;
              String access = Crypto.token(), next = Crypto.token();
              db.update(
                  "insert into spent_refresh_tokens(token_hash,session_id) values (?,?)", hash, id);
              db.update(
                  "update sessions set access_hash=?,access_expires_at=now()+interval '15 minutes',refresh_hash=?,last_used_at=now() where id=?",
                  Crypto.hash(access),
                  Crypto.hash(next),
                  id);
              return tokens(
                  (UUID) s.get("account_id"),
                  id,
                  access,
                  next,
                  instant(s.get("refresh_expires_at")));
            });
    // Throw after commit so replay detection really revokes the session.
    if (result == null) throw invalid();
    return result;
  }

  private void revoke(UUID session) {
    db.update("update sessions set revoked_at=coalesce(revoked_at,now()) where id=?", session);
    db.update("update push_devices set enabled=false where session_id=?", session);
  }

  public Object logout(String token) {
    commands.transaction(
        () -> {
          if (token != null && token.length() <= 128) {
            String hash = Crypto.hash(token);
            var rows =
                db.queryForList(
                    "select id from sessions where refresh_hash=? union select session_id as id from spent_refresh_tokens where token_hash=?",
                    hash,
                    hash);
            for (var r : rows) revoke((UUID) r.get("id"));
          }
          return null;
        });
    return ack("Signed out.");
  }

  public Actor verify(String token) {
    if (token == null || token.length() > 128) throw invalid();
    var rows =
        db.queryForList(
            "select s.id,s.account_id,s.context_id from sessions s join accounts a on a.id=s.account_id where access_hash=? and revoked_at is null and access_expires_at>now() and refresh_expires_at>now() and a.status='ACTIVE'",
            Crypto.hash(token));
    if (rows.isEmpty()) throw invalid();
    var s = rows.get(0);
    Actor.Context context = null;
    if (s.get("context_id") != null) {
      var memberships =
          db.queryForList(
              "select * from memberships where id=? and account_id=? and active",
              s.get("context_id"),
              s.get("account_id"));
      if (!memberships.isEmpty()) context = context(memberships.get(0));
    }
    return new Actor((UUID) s.get("account_id"), (UUID) s.get("id"), context);
  }

  private List<String> actions(Map<String, Object> m) {
    String role = (String) m.get("role");
    if ("PRINCIPAL".equals(role))
      return List.of(
          "school.manage",
          "students.manage",
          "teachers.manage",
          "timetable.publish",
          "attendance.review",
          "results.publish",
          "fees.manage",
          "promotion.confirm",
          "cases.manage");
    if ("PARENT".equals(role)) {
      var a = new ArrayList<String>();
      if (Boolean.TRUE.equals(m.get("learning_access"))) a.add("child.learning.read");
      if (Boolean.TRUE.equals(m.get("payer_access"))) a.add("fees.pay");
      return a;
    }
    return List.of(role.toLowerCase(Locale.ROOT) + ".workspace");
  }

  private Actor.Context context(Map<String, Object> m) {
    return new Actor.Context(
        (UUID) m.get("school_id"),
        (String) m.get("school_name"),
        (String) m.get("role"),
        (UUID) m.get("child_id"),
        (String) m.get("child_name"),
        (UUID) m.get("subject_id"),
        actions(m));
  }

  private Object contextWire(Map<String, Object> m) {
    String role = (String) m.get("role");
    String home =
        switch (role) {
          case "TEACHER" -> "TE01";
          case "STUDENT" -> "ST01";
          case "PARENT" -> "PA01";
          default -> "AD01";
        };
    return map(
        "schoolId",
        m.get("school_id"),
        "schoolName",
        m.get("school_name"),
        "role",
        role,
        "childId",
        m.get("child_id"),
        "childName",
        m.get("child_name"),
        "homeScreenId",
        home,
        "capabilities",
        Map.of("actions", actions(m)));
  }

  public Object contexts(Actor actor) {
    return map(
        "items",
        db
            .queryForList(
                "select * from memberships where account_id=? and active order by school_name,role,id",
                actor.accountId())
            .stream()
            .map(this::contextWire)
            .toList(),
        "hasMore",
        false);
  }

  public Object choose(Actor actor, UUID school, String role, UUID child) {
    return commands.transaction(
        () -> {
          var rows =
              db.queryForList(
                  "select * from memberships where account_id=? and school_id=? and role=? and child_id is not distinct from ? and active",
                  actor.accountId(),
                  school,
                  role,
                  child);
          if (rows.isEmpty())
            throw fail(
                403,
                "CONTEXT_NOT_ALLOWED",
                "This school, role or child is not available to your account.");
          db.update(
              "update sessions set context_id=? where id=? and revoked_at is null",
              rows.get(0).get("id"),
              actor.sessionId());
          return map("schoolId", school, "role", role, "childId", child);
        });
  }

  public Object me(Actor actor) {
    var a = account(actor.accountId());
    var pending =
        db.queryForList(
            "select payload_cipher from proofs where account_id=? and kind='EMAIL_VERIFY' and consumed_at is null and expires_at>now() order by created_at desc limit 1",
            actor.accountId());
    String pendingEmail =
        pending.isEmpty()
            ? null
            : json.tree(crypto.open((String) pending.get(0).get("payload_cipher")))
                .path("email")
                .asText();
    return map(
        "id",
        a.get("id"),
        "version",
        a.get("version"),
        "loginId",
        a.get("login_id"),
        "displayName",
        a.get("display_name"),
        "phone",
        a.get("phone"),
        "verifiedEmail",
        a.get("verified_email"),
        "pendingEmailMasked",
        pendingEmail == null ? null : maskedEmail(pendingEmail),
        "lastContext",
        actor.context() == null
            ? null
            : map(
                "schoolId",
                actor.context().schoolId(),
                "role",
                actor.context().role(),
                "childId",
                actor.context().childId()),
        "capabilities",
        Map.of("actions", List.of("account.contact.update", "account.security.manage")));
  }

  public Object phone(Actor actor, String phone, long expected) {
    String normalized = Api.phone(phone);
    return commands.transaction(
        () -> {
          var a = one("select * from accounts where id=? for update", actor.accountId());
          expectVersion(expected, ((Number) a.get("version")).longValue());
          db.update(
              "update accounts set phone=?,version=version+1 where id=?",
              normalized,
              actor.accountId());
          return me(actor);
        });
  }

  private String proof(UUID account, UUID session, String kind, Duration duration, Object payload) {
    String token = Crypto.token();
    db.update(
        "insert into proofs(id,account_id,session_id,kind,token_hash,payload_cipher,expires_at) values (?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        account,
        session,
        kind,
        Crypto.hash(token),
        payload == null ? null : crypto.seal(json.write(payload)),
        Timestamp.from(Instant.now().plus(duration)));
    return token;
  }

  private Map<String, Object> consume(String token, String kind, UUID account, UUID session) {
    if (token == null || token.length() > 128) throw invalid();
    var rows =
        db.queryForList(
            "select * from proofs where token_hash=? and kind=? and consumed_at is null and expires_at>now() for update",
            Crypto.hash(token),
            kind);
    if (rows.isEmpty()) throw invalid();
    var p = rows.get(0);
    if (account != null && !account.equals(p.get("account_id"))
        || session != null && !session.equals(p.get("session_id"))) throw invalid();
    db.update("update proofs set consumed_at=now() where id=?", p.get("id"));
    return p;
  }

  private Object flow(UUID account, String mode) {
    String token = proof(account, null, "FLOW_" + mode, Duration.ofMinutes(10), null);
    var a = account(account);
    String login = (String) a.get("login_id");
    return map(
        "mode",
        mode,
        "flowToken",
        token,
        "expiresAt",
        Instant.now().plusSeconds(600),
        "maskedAccount",
        login.substring(0, Math.min(8, login.length())) + "***");
  }

  public Object activation(String login, String code, String invitation) {
    limit("activation", Objects.toString(login, invitation), 10);
    return commands.transaction(
        () -> {
          Map<String, Object> p;
          if (invitation != null) {
            p = consume(invitation, "ACTIVATION_LINK", null, null);
          } else {
            var a = identifier(text(login, 1, 80, "Login ID"));
            if (a == null) throw invalid();
            p = consume(code, "ACTIVATION_CODE", (UUID) a.get("id"), null);
          }
          UUID id = (UUID) p.get("account_id");
          if (!"PENDING".equals(account(id).get("status"))) throw invalid();
          return flow(id, "ACTIVATE");
        });
  }

  public Object emailRecovery(String identifier) {
    identifier = text(identifier, 1, 254, "Login ID or verified email");
    limit("email-recovery", identifier.toLowerCase(Locale.ROOT), 5);
    var a = identifier(identifier);
    if (a != null && "ACTIVE".equals(a.get("status")) && a.get("verified_email") != null) {
      commands.transaction(
          () -> {
            UUID id = (UUID) a.get("id");
            String token = proof(id, null, "RESET_LINK", Duration.ofMinutes(20), null);
            outbox.add(
                "communication",
                "PRIVATE_EMAIL",
                map("to", a.get("verified_email"), "template", "RESET", "token", token));
            return null;
          });
    }
    return ack("If this account has a verified email, recovery instructions will be sent.");
  }

  public Object emailReview(String token) {
    limit("reset-proof", Crypto.hash(Objects.toString(token, "")), 8);
    return commands.transaction(
        () -> {
          var p = consume(token, "RESET_LINK", null, null);
          return flow((UUID) p.get("account_id"), "RESET_EMAIL");
        });
  }

  public Object recoveryReview(String login, String code) {
    limit("saved-recovery", Objects.toString(login, ""), 8);
    return commands.transaction(
        () -> {
          var found = identifier(text(login, 1, 80, "Login ID"));
          if (found == null) throw invalid();
          var a = one("select * from accounts where id=? for update", found.get("id"));
          if (!"ACTIVE".equals(a.get("status"))
              || code == null
              || !Crypto.equal((String) a.get("recovery_hash"), Crypto.hash(code))) throw invalid();
          db.update("update accounts set recovery_hash=null where id=?", a.get("id"));
          return flow((UUID) a.get("id"), "RESET_SAVED_CODE");
        });
  }

  public Object setupPassword(String token, String password) {
    passwordPolicy(password);
    limit("password-proof", Crypto.hash(Objects.toString(token, "")), 10);
    return commands.transaction(
        () -> {
          if (token == null || token.length() > 128) throw invalid();
          var rows =
              db.queryForList(
                  "select * from proofs where token_hash=? and kind in ('FLOW_ACTIVATE','FLOW_RESET_EMAIL','FLOW_RESET_SAVED_CODE') and consumed_at is null and expires_at>now() for update",
                  Crypto.hash(token));
          if (rows.isEmpty()) throw invalid();
          var p = rows.get(0);
          return savePassword((UUID) p.get("account_id"), password);
        });
  }

  private Object savePassword(UUID id, String password) {
    var a = one("select * from accounts where id=? for update", id);
    if ("DISABLED".equals(a.get("status"))) throw invalid();
    String code = Crypto.token();
    db.update(
        "update accounts set password_hash=?,recovery_hash=?,status='ACTIVE',version=version+1 where id=?",
        passwords.encode(password),
        Crypto.hash(code),
        id);
    db.update("update proofs set consumed_at=now() where account_id=? and consumed_at is null", id);
    for (var s :
        db.queryForList("select id from sessions where account_id=? and revoked_at is null", id))
      revoke((UUID) s.get("id"));
    return map(
        "changed",
        true,
        "signInRequired",
        true,
        "recoveryCodeShownOnce",
        true,
        "newRecoveryCode",
        code);
  }

  private void verifyPassword(UUID id, String password) {
    var a = account(id);
    if (password == null
        || password.length() > 512
        || a.get("password_hash") == null
        || !passwords.matches(password, (String) a.get("password_hash"))) throw invalid();
  }

  public Object changePassword(Actor a, String current, String next) {
    passwordPolicy(next);
    limit("reauth", a.accountId().toString(), 10);
    verifyPassword(a.accountId(), current);
    return commands.transaction(() -> savePassword(a.accountId(), next));
  }

  public Object reauthenticate(Actor a, String password) {
    limit("reauth", a.accountId().toString(), 10);
    verifyPassword(a.accountId(), password);
    return commands.transaction(
        () ->
            map(
                "recentAuthToken",
                proof(a.accountId(), a.sessionId(), "RECENT_AUTH", Duration.ofMinutes(5), null),
                "expiresAt",
                Instant.now().plusSeconds(300)));
  }

  public Object rotate(Actor a, String recent) {
    return commands.transaction(
        () -> {
          consume(recent, "RECENT_AUTH", a.accountId(), a.sessionId());
          String code = Crypto.token();
          db.update(
              "update accounts set recovery_hash=?,version=version+1 where id=?",
              Crypto.hash(code),
              a.accountId());
          return map("recoveryCode", code, "shownOnce", true, "signInRequired", false);
        });
  }

  public Object revokeOthers(Actor a, String recent, String key) {
    return commands.execute(
        a.accountId().toString(),
        "account",
        "revoke-others",
        key,
        map("recentAuthToken", recent),
        () -> {
          consume(recent, "RECENT_AUTH", a.accountId(), a.sessionId());
          for (var s :
              db.queryForList(
                  "select id from sessions where account_id=? and id<>? and revoked_at is null",
                  a.accountId(),
                  a.sessionId())) revoke((UUID) s.get("id"));
          return ack("Other sessions signed out.");
        });
  }

  public Object sessions(Actor a, UUID cursor, int limit) {
    var rows =
        db.queryForList(
            "select * from sessions where account_id=? and revoked_at is null and refresh_expires_at>now() and (?::uuid is null or id>?::uuid) order by id limit ?",
            a.accountId(),
            cursor,
            cursor,
            limit + 1);
    var mapped =
        rows.stream()
            .map(
                s ->
                    map(
                        "id",
                        s.get("id"),
                        "version",
                        1,
                        "deviceName",
                        s.get("device_name"),
                        "createdAt",
                        instant(s.get("created_at")),
                        "lastUsedAt",
                        instant(s.get("last_used_at")),
                        "current",
                        a.sessionId().equals(s.get("id"))))
            .toList();
    return page(mapped, limit, s -> s.get("id").toString());
  }

  public Object preferences(Actor a) {
    var acc = account(a.accountId());
    return map(
        "pushEnabled",
        acc.get("push_enabled"),
        "emailOptionalEnabled",
        acc.get("email_optional_enabled"),
        "mandatoryInApp",
        true,
        "categories",
        json.value(acc.get("categories").toString()));
  }

  public Object preferences(Actor a, boolean push, boolean email, List<String> categories) {
    if (categories == null
        || categories.size() > 5
        || !Set.of("SCHOOL", "LEARNING", "FEES", "MESSAGES", "CASES").containsAll(categories))
      throw fail(422, "INVALID_CATEGORIES", "Choose supported notification categories.");
    db.update(
        "update accounts set push_enabled=?,email_optional_enabled=?,categories=?::jsonb where id=?",
        push,
        email,
        json.write(categories),
        a.accountId());
    return preferences(a);
  }

  public Object changeEmail(Actor a, String email, String recent, String key) {
    String normalized = Api.email(email);
    return commands.execute(
        a.accountId().toString(),
        "account",
        "email-change",
        key,
        map("email", normalized, "recentAuthToken", recent),
        () -> {
          consume(recent, "RECENT_AUTH", a.accountId(), a.sessionId());
          return emailRequest(a, normalized);
        });
  }

  private Object emailRequest(Actor a, String email) {
    db.update(
        "update proofs set consumed_at=now() where account_id=? and kind='EMAIL_VERIFY' and consumed_at is null",
        a.accountId());
    String token =
        proof(
            a.accountId(),
            a.sessionId(),
            "EMAIL_VERIFY",
            Duration.ofMinutes(30),
            map("email", email));
    UUID request =
        (UUID) one("select id from proofs where token_hash=?", Crypto.hash(token)).get("id");
    outbox.add(
        "communication",
        "PRIVATE_EMAIL",
        map("to", email, "template", "VERIFY_EMAIL", "token", token));
    return map(
        "accepted",
        true,
        "requestId",
        request,
        "maskedDestination",
        maskedEmail(email),
        "expiresAt",
        Instant.now().plusSeconds(1800));
  }

  public Object resendEmail(Actor a, UUID request, String key) {
    limit("email-resend", a.accountId().toString(), 5);
    return commands.execute(
        a.accountId().toString(),
        "account",
        "email-resend",
        key,
        map("requestId", request),
        () -> {
          var p =
              one(
                  "select * from proofs where id=? and account_id=? and kind='EMAIL_VERIFY'",
                  request,
                  a.accountId());
          if (p.get("consumed_at") != null)
            throw fail(409, "REQUEST_REPLACED", "Request a new email change.");
          String email =
              json.tree(crypto.open((String) p.get("payload_cipher"))).path("email").asText();
          return emailRequest(a, email);
        });
  }

  public Object confirmEmail(String token) {
    limit("verify-email", Crypto.hash(Objects.toString(token, "")), 10);
    return commands.transaction(
        () -> {
          var p = consume(token, "EMAIL_VERIFY", null, null);
          String email =
              json.tree(crypto.open((String) p.get("payload_cipher"))).path("email").asText();
          var other =
              db.queryForList(
                  "select id from accounts where lower(verified_email)=? and id<>?",
                  email,
                  p.get("account_id"));
          if (!other.isEmpty())
            throw fail(409, "EMAIL_UNAVAILABLE", "That email cannot be used for this account.");
          db.update(
              "update accounts set verified_email=?,version=version+1 where id=?",
              email,
              p.get("account_id"));
          return ack("Email verified.");
        });
  }

  public Object registerPush(
      Actor a, String installation, String platform, String token, String key) {
    text(installation, 1, 100, "Installation");
    if (platform == null
        || !Set.of("ANDROID", "IOS").contains(platform)
        || token == null
        || !token.matches("(?:ExponentPushToken|ExpoPushToken)\\[[A-Za-z0-9_-]{8,200}\\]"))
      throw fail(422, "INVALID_PUSH_DEVICE", "Use a supported device push token.");
    return commands.execute(
        a.accountId().toString(),
        "account",
        "push-device",
        key,
        map("installationId", installation, "platform", platform, "pushToken", token),
        () -> {
          UUID id = UUID.randomUUID();
          var row =
              db.queryForMap(
                  "insert into push_devices(id,account_id,session_id,installation_id,platform,token_cipher) values (?,?,?,?,?,?) on conflict(account_id,installation_id) do update set session_id=excluded.session_id,platform=excluded.platform,token_cipher=excluded.token_cipher,enabled=true,version=push_devices.version+1 returning id,version",
                  id,
                  a.accountId(),
                  a.sessionId(),
                  installation,
                  platform,
                  crypto.seal(token));
          return map(
              "id",
              row.get("id"),
              "version",
              row.get("version"),
              "installationId",
              installation,
              "platform",
              platform,
              "enabled",
              true);
        });
  }

  public void removePush(Actor a, UUID id) {
    db.update(
        "update push_devices set enabled=false,version=version+1 where id=? and account_id=?",
        id,
        a.accountId());
  }

  public Object slip(Actor actor, UUID school, String type, UUID subject, String key) {
    actor.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    String subjectType = "TEACHER".equals(type) ? "STAFF" : type;
    if (!Set.of("STAFF", "STUDENT", "GUARDIAN").contains(subjectType))
      throw fail(422, "INVALID_SUBJECT_TYPE", "Choose STAFF, TEACHER, STUDENT or GUARDIAN.");
    if ("STAFF".equals(subjectType)
        && "SCHOOL_OFFICE".equals(actor.context().role())
        && db.queryForObject(
                "select count(*) from memberships where school_id=? and subject_id=? and role='TEACHER' and active",
                Integer.class,
                school,
                subject)
            == 0)
      throw fail(
          403,
          "ADMINISTRATOR_INVITATION_FORBIDDEN",
          "Only the principal can issue administrator access instructions.");
    return commands.execute(
        actor.accountId().toString(),
        school.toString(),
        "activation-slip/" + type + "/" + subject,
        key,
        map("subjectId", subject),
        () -> {
          var link =
              one(
                  "select account_id from provisioned_subjects where school_id=? and subject_type=? and subject_id=?",
                  school,
                  subjectType,
                  subject);
          UUID account = (UUID) link.get("account_id");
          var a = account(account);
          if (!"PENDING".equals(a.get("status")))
            throw fail(
                409,
                "ALREADY_ACTIVE",
                "This account is already active. School staff cannot reset its permanent password.");
          db.update(
              "update proofs set consumed_at=now() where account_id=? and kind in ('ACTIVATION_CODE','ACTIVATION_LINK') and consumed_at is null",
              account);
          String code = proof(account, null, "ACTIVATION_CODE", Duration.ofHours(48), null);
          commands.audit(
              school,
              actor.accountId(),
              "ACTIVATION_SLIP_ISSUED",
              type,
              subject,
              "School-issued initial access",
              Map.of());
          return map(
              "loginId",
              a.get("login_id"),
              "activationCode",
              code,
              "expiresAt",
              Instant.now().plusSeconds(172800),
              "delivery",
              "PRIVATE_SCHOOL_HANDOVER");
        });
  }

  public Object firstPrincipalSlip(UUID school, UUID subject, String key) {
    Actor.internalOnly("academics");
    var link =
        one(
            "select p.account_id from provisioned_subjects p join memberships m on m.account_id=p.account_id and m.school_id=p.school_id and m.role='PRINCIPAL' and m.active where p.school_id=? and p.subject_type='STAFF' and p.subject_id=?",
            school,
            subject);
    UUID id = (UUID) link.get("account_id");
    if (!"PENDING".equals(account(id).get("status")))
      throw fail(
          409,
          "ALREADY_ACTIVE",
          "The principal account is already active; this operation cannot reset it.");
    return commands.execute(
        "platform-operator",
        school.toString(),
        "first-principal-slip",
        key,
        map("subjectId", subject),
        () -> {
          db.update(
              "update proofs set consumed_at=now() where account_id=? and kind='ACTIVATION_CODE' and consumed_at is null",
              id);
          String code = proof(id, null, "ACTIVATION_CODE", Duration.ofHours(48), null);
          return map(
              "loginId",
              account(id).get("login_id"),
              "activationCode",
              code,
              "expiresAt",
              Instant.now().plusSeconds(172800),
              "delivery",
              "PRIVATE_SCHOOL_HANDOVER");
        });
  }

  public Object event(UUID eventId, String kind, com.fasterxml.jackson.databind.JsonNode payload) {
    Actor.internalOnly("academics");
    return commands.transaction(
        () -> {
          String digest = Crypto.hash(json.write(payload));
          var previous = db.queryForList("select kind from inbox where event_id=?", eventId);
          if (!previous.isEmpty()) return ack("Event already applied.");
          db.update("insert into inbox(event_id,kind) values (?,?)", eventId, kind);
          UUID school = uuid(payload.path("schoolId").asText());
          String schoolName = text(payload.path("schoolName").asText(), 2, 160, "School name");
          switch (kind) {
            case "SCHOOL_PROVISIONED" -> {
              var p = payload.path("principal");
              UUID subject = uuid(p.path("id").asText());
              UUID account = provision(school, "STAFF", subject, p);
              membership(
                  account, school, schoolName, "PRINCIPAL", null, null, subject, true, false);
            }
            case "STUDENT_ENROLLED" -> {
              var student = payload.path("student");
              UUID studentId = uuid(student.path("id").asText());
              UUID studentAccount = provision(school, "STUDENT", studentId, student);
              membership(
                  studentAccount,
                  school,
                  schoolName,
                  "STUDENT",
                  null,
                  null,
                  studentId,
                  true,
                  false);
              var guardian = payload.path("guardian");
              UUID guardianId = uuid(guardian.path("id").asText());
              UUID guardianAccount = provision(school, "GUARDIAN", guardianId, guardian);
              guardianVersion(
                  school,
                  studentId,
                  guardianId,
                  1,
                  () ->
                      membership(
                          guardianAccount,
                          school,
                          schoolName,
                          "PARENT",
                          studentId,
                          student.path("fullName").asText(),
                          guardianId,
                          true,
                          true));
            }
            case "TEACHER_REGISTERED" -> {
              var teacher = payload.path("teacher");
              applyStaff(school, schoolName, teacher, "TEACHER", true, 1);
            }
            case "STAFF_ACCESS_CHANGED" -> {
              var staff = payload.path("staff");
              applyStaff(
                  school,
                  schoolName,
                  staff,
                  staff.path("role").asText(),
                  staff.path("active").asBoolean(),
                  staff.path("version").asLong());
            }
            case "GUARDIAN_LINK_UPDATED" -> {
              var parent = payload.path("guardian");
              UUID guardian = uuid(parent.path("id").asText()),
                  child = uuid(payload.path("studentId").asText());
              UUID account = provision(school, "GUARDIAN", guardian, parent);
              guardianVersion(
                  school,
                  child,
                  guardian,
                  payload.path("version").asLong(1),
                  () -> {
                    membership(
                        account,
                        school,
                        schoolName,
                        "PARENT",
                        child,
                        payload.path("studentName").asText(),
                        guardian,
                        payload.path("learningAccess").asBoolean(),
                        payload.path("payerAccess").asBoolean());
                    db.update(
                        "update memberships set active=true,learning_access=?,payer_access=? where school_id=? and child_id=? and subject_id=? and role='PARENT'",
                        payload.path("learningAccess").asBoolean(),
                        payload.path("payerAccess").asBoolean(),
                        school,
                        child,
                        guardian);
                  });
            }
            case "GUARDIAN_LINK_REVOKED" -> {
              UUID child = uuid(payload.path("studentId").asText()),
                  guardian = uuid(payload.path("guardianId").asText());
              guardianVersion(
                  school,
                  child,
                  guardian,
                  payload.path("version").asLong(2),
                  () ->
                      db.update(
                          "update memberships set active=false where school_id=? and child_id=? and subject_id=? and role='PARENT'",
                          school,
                          child,
                          guardian));
            }
            default -> throw fail(422, "UNKNOWN_EVENT", "Unsupported identity event.");
          }
          return ack("Event applied.");
        });
  }

  private UUID provision(
      UUID school, String type, UUID subject, com.fasterxml.jackson.databind.JsonNode person) {
    var previous =
        db.queryForList(
            "select account_id from provisioned_subjects where school_id=? and subject_type=? and subject_id=?",
            school,
            type,
            subject);
    if (!previous.isEmpty()) return (UUID) previous.get(0).get("account_id");
    UUID account;
    String existing = person.path("existingLoginId").asText("");
    if (!existing.isBlank()) {
      if (!person.path("relationshipVerified").asBoolean(false))
        throw fail(
            422,
            "RELATIONSHIP_NOT_VERIFIED",
            "School staff must verify the relationship before linking an existing global account.");
      var a = identifier(existing);
      if (a == null || !"ACTIVE".equals(a.get("status")))
        throw fail(422, "ACCOUNT_NOT_LINKABLE", "The supplied existing account cannot be linked.");
      account = (UUID) a.get("id");
    } else {
      account = UUID.randomUUID();
      String login =
          "EC-"
              + type.charAt(0)
              + "-"
              + account.toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
      db.update(
          "insert into accounts(id,login_id,display_name,phone,status) values (?,?,?,?,'PENDING')",
          account,
          login,
          text(person.path("fullName").asText(), 2, 120, "Full name"),
          Api.phone(person.path("phone").asText()));
    }
    if ("STAFF".equals(type)
        && db.queryForObject(
                "select count(*) from provisioned_subjects where school_id=? and account_id=? and subject_type='STAFF' and subject_id<>?",
                Integer.class,
                school,
                account,
                subject)
            > 0)
      throw fail(
          409, "STAFF_ALREADY_LINKED", "This account already has a staff record in this school.");
    db.update(
        "insert into provisioned_subjects(school_id,subject_type,subject_id,account_id) values (?,?,?,?)",
        school,
        type,
        subject,
        account);
    return account;
  }

  private void applyStaff(
      UUID school,
      String schoolName,
      com.fasterxml.jackson.databind.JsonNode person,
      String role,
      boolean active,
      long version) {
    if (!Set.of("PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS", "TEACHER").contains(role) || version < 1)
      throw fail(422, "INVALID_STAFF_ACCESS", "Unsupported staff preset or source version.");
    UUID subject = uuid(person.path("id").asText());
    db.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        "staff-access|" + school);
    var old =
        db.queryForList(
            "select source_version,account_id from provisioned_subjects where school_id=? and subject_type='STAFF' and subject_id=? for update",
            school,
            subject);
    // A v1 teacher registration still needs its initial membership after provision().
    if (!old.isEmpty()
        && ((Number) old.get(0).get("source_version")).longValue() >= version
        && db.queryForObject(
                "select count(*) from memberships where school_id=? and subject_id=? and role in ('PRINCIPAL','SCHOOL_OFFICE','ACCOUNTS','TEACHER')",
                Integer.class,
                school,
                subject)
            > 0) return;
    UUID account = provision(school, "STAFF", subject, person);
    boolean principal =
        db.queryForObject(
                "select count(*) from memberships where school_id=? and subject_id=? and role='PRINCIPAL' and active",
                Integer.class,
                school,
                subject)
            > 0;
    if (principal
        && (!active || !"PRINCIPAL".equals(role))
        && db.queryForObject(
                "select count(*) from memberships where school_id=? and role='PRINCIPAL' and active",
                Integer.class,
                school)
            <= 1) throw fail(409, "LAST_PRINCIPAL", "The school must retain an active principal.");
    db.update(
        "update memberships set active=false where school_id=? and subject_id=? and role in ('PRINCIPAL','SCHOOL_OFFICE','ACCOUNTS','TEACHER')",
        school,
        subject);
    membership(account, school, schoolName, role, null, null, subject, true, false);
    db.update(
        "update memberships set active=? where account_id=? and school_id=? and role=? and subject_id=?",
        active,
        account,
        school,
        role,
        subject);
    db.update(
        "update provisioned_subjects set source_version=? where school_id=? and subject_type='STAFF' and subject_id=?",
        version,
        school,
        subject);
  }

  private void guardianVersion(
      UUID school, UUID child, UUID guardian, long version, Runnable action) {
    db.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        "guardian-access|" + school + "|" + child + "|" + guardian);
    var old =
        db.queryForList(
            "select source_version from guardian_access_versions where school_id=? and child_id=? and guardian_id=?",
            school,
            child,
            guardian);
    if (!old.isEmpty() && ((Number) old.get(0).get("source_version")).longValue() >= version)
      return;
    action.run();
    db.update(
        "insert into guardian_access_versions(school_id,child_id,guardian_id,source_version) values (?,?,?,?) on conflict(school_id,child_id,guardian_id) do update set source_version=excluded.source_version",
        school,
        child,
        guardian,
        version);
  }

  private void membership(
      UUID account,
      UUID school,
      String name,
      String role,
      UUID child,
      String childName,
      UUID subject,
      boolean learning,
      boolean payer) {
    db.update(
        "insert into memberships(id,account_id,school_id,school_name,role,child_id,child_name,subject_id,learning_access,payer_access) values (?,?,?,?,?,?,?,?,?,?) on conflict do nothing",
        UUID.randomUUID(),
        account,
        school,
        name,
        role,
        child,
        childName,
        subject,
        learning,
        payer);
  }

  public Object internalAudience(UUID school, UUID child, String category) {
    Actor.internalOnly("academics", "finance", "communication");
    String cat = category == null ? "CASES" : category;
    var recipients =
        db
            .queryForList(
                "select distinct account_id,role,subject_id from memberships where school_id=? and active and ((role='PARENT' and child_id=? and (case when ?='FEES' then payer_access when ?='LEARNING' then learning_access else true end)) or (role='STUDENT' and subject_id=? and ? in ('LEARNING','SCHOOL')))",
                school,
                child,
                cat,
                cat,
                child,
                cat)
            .stream()
            .map(
                r ->
                    map(
                        "accountId",
                        r.get("account_id"),
                        "role",
                        r.get("role"),
                        "subjectId",
                        r.get("subject_id")))
            .toList();
    return map(
        "items",
        recipients.stream().map(r -> r.get("accountId")).distinct().toList(),
        "recipients",
        recipients);
  }

  public Object schoolAudience(UUID school) {
    Actor.internalOnly("communication");
    return map(
        "items",
        db
            .queryForList(
                "select account_id,role,subject_id,child_id,learning_access,payer_access from memberships where school_id=? and active order by account_id,role,child_id",
                school)
            .stream()
            .map(
                r ->
                    map(
                        "accountId",
                        r.get("account_id"),
                        "role",
                        r.get("role"),
                        "subjectId",
                        r.get("subject_id"),
                        "childId",
                        r.get("child_id"),
                        "learningAccess",
                        r.get("learning_access"),
                        "payerAccess",
                        r.get("payer_access")))
            .toList());
  }

  public Object subjectAccount(UUID school, UUID subject, String role, UUID child) {
    Actor.internalOnly("communication");
    return db
        .queryForList(
            "select account_id as \"accountId\",subject_id as \"subjectId\",role from memberships where school_id=? and subject_id=? and role=? and active and (?::uuid is null or child_id=?::uuid)",
            school,
            subject,
            role,
            child,
            child)
        .stream()
        .findFirst()
        .orElseThrow(() -> fail(404, "NOT_FOUND", "The recipient account is not available."));
  }

  public Object deliveryContext(UUID account, UUID school, UUID child, String category) {
    Actor.internalOnly("communication");
    var rows = db.queryForList("select * from accounts where id=? and status='ACTIVE'", account);
    if (rows.isEmpty()) return map("allowed", false);
    var p = rows.get(0);
    if (db.queryForObject(
            "select count(*) from memberships where account_id=? and school_id=? and active and (?::uuid is null or (role='PARENT' and child_id=?::uuid and (case when ?='FEES' then payer_access when ?='LEARNING' then learning_access else true end)) or (role='STUDENT' and subject_id=?::uuid and ? in ('LEARNING','SCHOOL')) or role in ('PRINCIPAL','SCHOOL_OFFICE','ACCOUNTS','TEACHER'))",
            Integer.class,
            account,
            school,
            child,
            child,
            category,
            category,
            child,
            category)
        == 0) return map("allowed", false);
    boolean categoryOn = false;
    for (var n : json.tree(p.get("categories").toString()))
      if (n.asText().equals(category)) categoryOn = true;
    var devices = new ArrayList<Object>();
    if (Boolean.TRUE.equals(p.get("push_enabled")) && categoryOn)
      for (var d :
          db.queryForList(
              "select d.id,d.token_cipher from push_devices d join sessions s on s.id=d.session_id where d.account_id=? and d.enabled and s.revoked_at is null and s.refresh_expires_at>now()",
              account))
        devices.add(map("id", d.get("id"), "token", crypto.open(d.get("token_cipher").toString())));
    return map(
        "allowed",
        true,
        "devices",
        devices,
        "email",
        Boolean.TRUE.equals(p.get("email_optional_enabled")) && categoryOn
            ? p.get("verified_email")
            : null);
  }

  public Object disablePush(UUID account, UUID device) {
    Actor.internalOnly("communication");
    db.update(
        "update push_devices set enabled=false,version=version+1 where account_id=? and id=?",
        account,
        device);
    return map("accepted", true);
  }

  public static Object ack(String message) {
    return map("accepted", true, "message", message, "requestId", UUID.randomUUID());
  }
}
