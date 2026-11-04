package com.educore.identity;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class InvitationService {
  private final JdbcTemplate db;
  private final Commands commands;

  public InvitationService(JdbcTemplate db, Commands commands) {
    this.db = db;
    this.commands = commands;
  }

  private String type(String value) {
    String type = value.toUpperCase(Locale.ROOT);
    if ("TEACHER".equals(type)) type = "STAFF";
    if (!Set.of("STAFF", "STUDENT", "GUARDIAN").contains(type))
      throw fail(422, "INVALID_SUBJECT_TYPE", "Choose STAFF, TEACHER, STUDENT or GUARDIAN.");
    return type;
  }

  private Map<String, Object> subject(Actor a, UUID school, String type, UUID subject) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    var rows =
        db.queryForList(
            "select a.id,a.status,a.login_id from provisioned_subjects p join accounts a on a.id=p.account_id where p.school_id=? and p.subject_type=? and p.subject_id=?",
            school,
            type,
            subject);
    if (rows.isEmpty())
      throw fail(404, "NOT_FOUND", "This school account has not been provisioned.");
    if ("STAFF".equals(type)
        && "SCHOOL_OFFICE".equals(a.context().role())
        && db.queryForObject(
                "select count(*) from memberships where school_id=? and subject_id=? and active and role='TEACHER'",
                Integer.class,
                school,
                subject)
            == 0)
      throw fail(
          403,
          "ADMINISTRATOR_INVITATION_FORBIDDEN",
          "Only the principal manages administrator invitations.");
    return rows.get(0);
  }

  private void pending(Map<String, Object> account) {
    if (!"PENDING".equals(account.get("status")))
      throw fail(
          409,
          "ALREADY_ACTIVE",
          "Activation invitations cannot reset an active or disabled global account.");
  }

  public Object list(Actor a, UUID school, String rawType, UUID id, UUID cursor, int limit) {
    String type = type(rawType);
    var account = subject(a, school, type, id);
    return map(
        "accountStatus",
        account.get("status"),
        "loginId",
        account.get("login_id"),
        "invitations",
        page(
            db.queryForList(
                "select i.id,i.version,i.created_at as \"issuedAt\",i.issued_by as \"issuedBy\",i.ended_at as \"endedAt\",i.reason,p.expires_at as \"expiresAt\",case when i.state<>'ISSUED' then i.state when a.status='ACTIVE' then 'ACTIVATED' when p.expires_at<=now() then 'EXPIRED' when p.consumed_at is not null then 'ACTIVATION_STARTED' else 'PENDING' end as state from school_invitations i join proofs p on p.id=i.proof_id join accounts a on a.id=i.account_id where i.school_id=? and i.subject_type=? and i.subject_id=? and (?::uuid is null or i.id<?::uuid) order by i.id desc limit ?",
                school,
                type,
                id,
                cursor,
                cursor,
                limit + 1),
            limit,
            r -> r.get("id").toString()));
  }

  public Object issue(Actor a, UUID school, String rawType, UUID id, String key) {
    String type = type(rawType);
    var link = subject(a, school, type, id);
    pending(link);
    UUID account = (UUID) link.get("id");
    if (db.queryForObject(
            "select count(*) from memberships where account_id=? and school_id=? and subject_id=? and active",
            Integer.class,
            account,
            school,
            id)
        == 0)
      throw fail(
          409,
          "NO_ACTIVE_SCHOOL_ACCESS",
          "Restore or provision the permitted school relationship before inviting this account.");
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "activation-slip/" + rawType.toUpperCase(Locale.ROOT) + "/" + id,
        key,
        map("subjectId", id),
        () -> {
          var locked = db.queryForMap("select * from accounts where id=? for update", account);
          pending(locked);
          db.update(
              "update school_invitations set state='REPLACED',ended_at=now(),version=version+1 where account_id=? and state='ISSUED'",
              account);
          // Replacing an invitation also invalidates an activation already reviewed but not
          // completed.
          db.update(
              "update proofs set consumed_at=now() where account_id=? and kind in ('ACTIVATION_CODE','ACTIVATION_LINK','FLOW_ACTIVATE') and consumed_at is null",
              account);
          UUID invitation = UUID.randomUUID(), proof = UUID.randomUUID();
          String code = Crypto.token();
          var expiry =
              db.queryForObject(
                  "insert into proofs(id,account_id,kind,token_hash,expires_at) values (?,?,'ACTIVATION_CODE',?,now()+interval '48 hours') returning expires_at",
                  java.sql.Timestamp.class,
                  proof,
                  account,
                  Crypto.hash(code));
          db.update(
              "insert into school_invitations(id,school_id,subject_type,subject_id,account_id,proof_id,issued_by) values (?,?,?,?,?,?,?)",
              invitation,
              school,
              type,
              id,
              account,
              proof,
              a.accountId());
          db.update("update proofs set invitation_id=? where id=?", invitation, proof);
          commands.audit(
              school,
              a.accountId(),
              "ACTIVATION_SLIP_ISSUED",
              type,
              id,
              "School-issued initial access",
              map("invitationId", invitation));
          return map(
              "invitationId",
              invitation,
              "version",
              1,
              "loginId",
              locked.get("login_id"),
              "activationCode",
              code,
              "expiresAt",
              expiry.toInstant(),
              "delivery",
              "PRIVATE_SCHOOL_HANDOVER");
        });
  }

  public record Revoke(String reason) {}

  public Object revoke(
      Actor a,
      UUID school,
      String rawType,
      UUID subjectId,
      UUID id,
      Revoke b,
      long version,
      String key) {
    String type = type(rawType);
    var account = subject(a, school, type, subjectId);
    var allowed =
        db.queryForList(
            "select id from school_invitations where school_id=? and subject_type=? and subject_id=? and id=?",
            school,
            type,
            subjectId,
            id);
    if (allowed.isEmpty()) throw fail(404, "NOT_FOUND", "The invitation is unavailable.");
    return commands.execute(
        a.accountId().toString(),
        school.toString(),
        "invitation/" + id + "/revoke",
        key,
        map("input", b, "version", version),
        () -> {
          var locked =
              db.queryForMap("select * from accounts where id=? for update", account.get("id"));
          pending(locked);
          var invitation =
              db.queryForMap(
                  "select * from school_invitations where school_id=? and id=? for update",
                  school,
                  id);
          expectVersion(version, ((Number) invitation.get("version")).longValue());
          if (!"ISSUED".equals(invitation.get("state")))
            throw fail(
                409, "INVITATION_NOT_PENDING", "Only an outstanding invitation can be revoked.");
          String reason = text(b.reason(), 5, 500, "Revocation reason");
          db.update(
              "update school_invitations set state='REVOKED',ended_at=now(),reason=?,version=version+1 where id=?",
              reason,
              id);
          db.update(
              "update proofs set consumed_at=now() where invitation_id=? and consumed_at is null",
              id);
          commands.audit(
              school,
              a.accountId(),
              "INVITATION_REVOKED",
              type,
              subjectId,
              reason,
              map("invitationId", id));
          return map("id", id, "version", version + 1, "state", "REVOKED");
        });
  }
}
