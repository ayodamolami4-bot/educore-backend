package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ProvisioningService {
  private final SchoolService s;
  private final Crypto crypto;

  public ProvisioningService(SchoolService s, Crypto crypto) {
    this.s = s;
    this.crypto = crypto;
  }

  // Older outbox records have encrypted payloads. Index them without modifying or replaying them.
  @Scheduled(fixedDelayString = "${educore.provisioning-index-delay-ms:2000}")
  public void indexExisting() {
    for (var event :
        s.db.queryForList(
            "select o.id,o.kind,o.payload_cipher from outbox o where o.destination='identity' and not exists(select 1 from provisioning_events p where p.event_id=o.id) order by o.created_at,o.id limit 50")) {
      s.commands.transaction(
          () -> {
            var payload = s.json.tree(crypto.open((String) event.get("payload_cipher")));
            UUID school = uuid(payload.path("schoolId").asText());
            s.db.update(
                "insert into provisioning_events(event_id,school_id) values (?,?) on conflict do nothing",
                event.get("id"),
                school);
            for (String name : List.of("principal", "staff", "teacher", "student", "guardian")) {
              JsonNode person = payload.path(name);
              if (!person.path("id").isTextual()) continue;
              String type =
                  switch (name) {
                    case "principal", "staff", "teacher" -> "STAFF";
                    case "student" -> "STUDENT";
                    default -> "GUARDIAN";
                  };
              target((UUID) event.get("id"), type, uuid(person.path("id").asText()));
            }
            if (payload.path("guardianId").isTextual())
              target((UUID) event.get("id"), "GUARDIAN", uuid(payload.path("guardianId").asText()));
            return null;
          });
    }
  }

  private void target(UUID event, String type, UUID subject) {
    s.db.update(
        "insert into provisioning_targets(event_id,subject_type,subject_id) values (?,?,?) on conflict do nothing",
        event,
        type,
        subject);
  }

  private String authorize(Actor a, UUID school, String rawType, UUID subject) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    String type = rawType.toUpperCase(Locale.ROOT);
    if ("TEACHER".equals(type)) type = "STAFF";
    switch (type) {
      case "STAFF" -> {
        var staff =
            s.one("select staff_role from staff where school_id=? and id=?", school, subject);
        if (!"TEACHER".equals(staff.get("staff_role"))) a.school(school, "PRINCIPAL");
      }
      case "STUDENT" ->
          s.one("select id from students where school_id=? and id=?", school, subject);
      case "GUARDIAN" ->
          s.one("select id from guardians where school_id=? and id=?", school, subject);
      default ->
          throw fail(422, "INVALID_SUBJECT_TYPE", "Choose STAFF, TEACHER, STUDENT or GUARDIAN.");
    }
    return type;
  }

  public Object status(Actor a, UUID school, String rawType, UUID subject) {
    String type = authorize(a, school, rawType, subject);
    var rows =
        s.db.queryForList(
            "select o.id as \"eventId\",o.kind,o.attempts,o.next_attempt_at as \"nextAttemptAt\",o.delivered_at as \"deliveredAt\",o.lease_until as \"leaseUntil\",o.created_at as \"createdAt\",o.last_error_code as \"errorCode\",p.version,case when o.delivered_at is not null then 'PROVISIONED' when o.lease_until>now() then 'PROCESSING' when o.attempts>=20 then 'FAILED' when o.last_error_code is not null then 'RETRYING' else 'PENDING' end as state from provisioning_events p join provisioning_targets t on t.event_id=p.event_id join outbox o on o.id=p.event_id where p.school_id=? and t.subject_type=? and t.subject_id=? order by o.created_at desc,o.id desc limit 20",
            school,
            type,
            subject);
    return map(
        "subjectId", subject, "type", type, "items", rows, "indexingPending", rows.isEmpty());
  }

  public record Retry(UUID eventId, String reason) {}

  public Object retry(
      Actor a, UUID school, String rawType, UUID subject, Retry b, long version, String key) {
    String type = authorize(a, school, rawType, subject);
    // Check this event's current school/subject ownership before returning a cached command result.
    s.one(
        "select p.event_id from provisioning_events p join provisioning_targets t on t.event_id=p.event_id where p.school_id=? and p.event_id=? and t.subject_type=? and t.subject_id=?",
        school,
        b.eventId(),
        type,
        subject);
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "provisioning/" + type + "/" + subject + "/retry",
        key,
        map("input", b, "version", version),
        () -> {
          var p =
              s.one(
                  "select p.version,o.delivered_at,o.last_error_code,o.lease_until>now() as leased from provisioning_events p join outbox o on o.id=p.event_id where p.school_id=? and p.event_id=? for update of p,o",
                  school,
                  b.eventId());
          expectVersion(version, ((Number) p.get("version")).longValue());
          if (p.get("delivered_at") != null)
            throw fail(
                409, "ALREADY_PROVISIONED", "This event has already been accepted by Identity.");
          if (Boolean.TRUE.equals(p.get("leased")))
            throw fail(
                409, "PROVISIONING_IN_PROGRESS", "A worker is already processing this event.");
          if (p.get("last_error_code") == null)
            throw fail(
                409,
                "PROVISIONING_NOT_FAILED",
                "This event is already queued for its first delivery.");
          s.db.update(
              "update outbox set attempts=0,next_attempt_at=now(),lease_until=null,last_error_code=null where id=?",
              b.eventId());
          s.db.update(
              "update provisioning_events set version=version+1 where event_id=?", b.eventId());
          s.commands.audit(
              school,
              a.accountId(),
              "PROVISIONING_RETRY_REQUESTED",
              type,
              subject,
              text(b.reason(), 5, 500, "Retry reason"),
              map("eventId", b.eventId(), "previousVersion", version));
          return map("eventId", b.eventId(), "version", version + 1, "state", "PENDING");
        });
  }
}
