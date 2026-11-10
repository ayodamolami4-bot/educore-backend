package com.educore.communication;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DeliveryOperationsService {
  private final CommunicationService s;
  private final CommunicationWorkService work;

  public DeliveryOperationsService(CommunicationService s, CommunicationWorkService work) {
    this.s = s;
    this.work = work;
  }

  private String scope(Actor a, UUID school) {
    String role = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS").role();
    return switch (role) {
      case "PRINCIPAL" -> "true";
      case "ACCOUNTS" ->
          "(n.category='FEES' or (n.category='CASES' and exists(select 1 from complaints c where c.school_id=n.school_id and c.id::text=n.target->>'resourceId' and c.category='FEES')))";
      default ->
          "(n.category in ('SCHOOL','LEARNING') or (n.category='CASES' and exists(select 1 from complaints c where c.school_id=n.school_id and c.id::text=n.target->>'resourceId' and c.category in ('GENERAL','ATTENDANCE','ACADEMIC_RECORDS'))) or (n.category='MESSAGES' and exists(select 1 from parent_threads t where t.school_id=n.school_id and t.id::text=n.target->>'resourceId' and t.target='OFFICE')))";
    };
  }

  private Map<String, Object> notification(Actor a, UUID school, UUID id) {
    return s.one(
        "select n.* from notifications n where n.school_id=? and n.id=? and " + scope(a, school),
        school,
        id);
  }

  public Object list(
      Actor a,
      UUID school,
      String category,
      UUID resourceId,
      UUID childId,
      boolean failedOnly,
      UUID cursor,
      int limit) {
    String permitted = scope(a, school);
    if (category != null
        && !Set.of("SCHOOL", "LEARNING", "FEES", "MESSAGES", "CASES").contains(category))
      throw fail(422, "INVALID_CATEGORY", "Choose a supported category.");
    return page(
        s.db.queryForList(
            "select n.id,n.category,n.child_id as \"childId\",n.target->>'resourceId' as \"resourceId\",n.created_at as \"createdAt\",n.read_at as \"readAt\",n.delivery_version as version,coalesce(d.state,'NOT_PREPARED') as \"dispatchState\",(select count(*) from push_deliveries p where p.notification_id=n.id and p.state='FAILED') as \"failedPushCount\",(select count(*) from private_mail m where m.event_id=n.id and m.state='FAILED') as \"failedEmailCount\" from notifications n left join notification_dispatch d on d.notification_id=n.id where n.school_id=? and "
                + permitted
                + " and (?::text is null or n.category=?::text) and (?::uuid is null or n.target->>'resourceId'=?::text) and (?::uuid is null or n.child_id=?::uuid) and (not ? or d.state='FAILED' or exists(select 1 from push_deliveries p where p.notification_id=n.id and p.state in ('FAILED','WAITING_CONFIGURATION')) or exists(select 1 from private_mail m where m.event_id=n.id and m.state in ('FAILED','WAITING_CONFIGURATION'))) and (?::uuid is null or n.id<?::uuid) order by n.id desc limit ?",
            school,
            category,
            category,
            resourceId,
            resourceId,
            childId,
            childId,
            failedOnly,
            cursor,
            cursor,
            limit + 1),
        limit,
        r -> r.get("id").toString());
  }

  public Object detail(Actor a, UUID school, UUID id) {
    var n = notification(a, school, id);
    return map(
        "id",
        id,
        "category",
        n.get("category"),
        "version",
        n.get("delivery_version"),
        "readAt",
        n.get("read_at"),
        "providerReceiptIsReadConfirmation",
        false,
        "dispatch",
        s.db.queryForList(
            "select state,attempts,next_attempt_at as \"nextAttemptAt\" from notification_dispatch where notification_id=?",
            id),
        "push",
        s.db.queryForList(
            "select id,state,attempts,error_code as \"errorCode\",sent_at as \"providerConfirmedAt\",next_attempt_at as \"nextAttemptAt\" from push_deliveries where notification_id=? order by id",
            id),
        "email",
        s.db.queryForList(
            "select id,state,attempts,last_error_code as \"errorCode\",sent_at as \"sentAt\",expires_at as \"expiresAt\" from private_mail where event_id=? order by id",
            id),
        "retryHistory",
        s.db.queryForList(
            "select id,channel,delivery_id as \"deliveryId\",actor_id as \"actorId\",reason,previous_state as \"previousState\",created_at as \"createdAt\" from delivery_retry_history where notification_id=? order by created_at desc,id desc limit 20",
            id));
  }

  public Object noticeSummary(Actor a, UUID school, UUID id) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
    work.notice(a, school, id);
    return map(
        "noticeId",
        id,
        "inApp",
        s.db.queryForMap(
            "select count(*) as recipients,count(*) filter(where read_at is not null) as read from notifications where school_id=? and event_id=?",
            school,
            id),
        "dispatch",
        s.db.queryForList(
            "select d.state,count(*) as count from notification_dispatch d join notifications n on n.id=d.notification_id where n.school_id=? and n.event_id=? group by d.state order by d.state",
            school,
            id),
        "push",
        s.db.queryForList(
            "select p.state,count(*) as count from push_deliveries p join notifications n on n.id=p.notification_id where n.school_id=? and n.event_id=? group by p.state order by p.state",
            school,
            id),
        "email",
        s.db.queryForList(
            "select m.state,count(*) as count from private_mail m join notifications n on n.id=m.event_id where n.school_id=? and n.event_id=? group by m.state order by m.state",
            school,
            id),
        "providerReceiptIsReadConfirmation",
        false);
  }

  public record Retry(String channel, UUID deliveryId, String reason) {}

  @SuppressWarnings("unchecked")
  public Object retry(Actor a, UUID school, UUID id, Retry b, long version, String key) {
    var current = notification(a, school, id);
    if ("CASES".equals(current.get("category")) && !"PRINCIPAL".equals(a.context().role())) {
      var target = s.json.tree(current.get("target").toString());
      var complaint =
          s.one(
              "select owner_id from complaints where school_id=? and id=?",
              school,
              uuid(target.path("resourceId").asText()));
      if (complaint.get("owner_id") != null
          && !a.context().subjectId().equals(complaint.get("owner_id")))
        throw fail(
            403,
            "CASE_OWNER_REQUIRED",
            "The assigned owner or principal retries this case's delivery.");
    }
    if (b.channel() == null || !Set.of("DISPATCH", "PUSH", "EMAIL").contains(b.channel()))
      throw fail(422, "INVALID_CHANNEL", "Choose DISPATCH, PUSH or EMAIL.");
    return s.commands.execute(
        a.accountId().toString(),
        school.toString(),
        "delivery/" + id + "/retry",
        key,
        map("input", b, "version", version),
        () -> {
          var n =
              s.one(
                  "select n.*,n.created_at>now()-interval '24 hours' as within_window from notifications n where n.school_id=? and n.id=? for update",
                  school,
                  id);
          expectVersion(version, ((Number) n.get("delivery_version")).longValue());
          Map<String, Object> ctx =
              s.http.post(
                  s.identity,
                  "/internal/delivery-context",
                  map(
                      "accountId",
                      n.get("account_id"),
                      "schoolId",
                      school,
                      "childId",
                      n.get("child_id"),
                      "category",
                      n.get("category")),
                  Map.class);
          if (!Boolean.TRUE.equals(ctx.get("allowed")))
            throw fail(
                409,
                "RECIPIENT_ACCESS_REMOVED",
                "The recipient no longer has the required school relationship.");
          String previous;
          if ("DISPATCH".equals(b.channel())) {
            if (b.deliveryId() != null)
              throw fail(
                  422,
                  "DISPATCH_HAS_NO_DELIVERY_ID",
                  "Dispatch retries identify the notification only.");
            if (!Boolean.TRUE.equals(n.get("within_window")))
              throw fail(
                  409,
                  "DELIVERY_WINDOW_EXPIRED",
                  "This notification is outside its dispatch window.");
            var job =
                s.one(
                    "select state,lease_until>now() as leased from notification_dispatch where notification_id=? for update",
                    id);
            previous = job.get("state").toString();
            if (Boolean.TRUE.equals(job.get("leased")) || !"FAILED".equals(previous))
              throw fail(
                  409,
                  "DELIVERY_NOT_RETRYABLE",
                  "Only a failed dispatch with no active worker can be retried.");
            s.db.update(
                "update notification_dispatch set state='PENDING',attempts=0,next_attempt_at=now(),lease_token=null,lease_until=null where notification_id=?",
                id);
          } else if ("PUSH".equals(b.channel())) {
            var job =
                s.one(
                    "select *,lease_until>now() as leased from push_deliveries where id=? and notification_id=? for update",
                    b.deliveryId(),
                    id);
            previous = job.get("state").toString();
            if (!Boolean.TRUE.equals(n.get("within_window"))
                || Boolean.TRUE.equals(job.get("leased"))
                || !Set.of("FAILED", "WAITING_CONFIGURATION").contains(previous)
                || "DeviceNotRegistered".equals(job.get("error_code")))
              throw fail(
                  409,
                  "DELIVERY_NOT_RETRYABLE",
                  "Confirmed, pending-receipt, expired or unregistered-device deliveries cannot be replayed.");
            boolean eligible = false;
            for (Object value : (List<?>) ctx.getOrDefault("devices", List.of())) {
              Map<?, ?> device = (Map<?, ?>) value;
              if (job.get("device_id").toString().equals(device.get("id").toString())
                  && Crypto.equal(
                      s.crypto.open(job.get("token_cipher").toString()),
                      Objects.toString(device.get("token"), ""))) eligible = true;
            }
            if (!eligible)
              throw fail(
                  409,
                  "DEVICE_OR_PREFERENCE_REMOVED",
                  "The original device is no longer enabled for this notification.");
            s.db.update(
                "update push_deliveries set state='QUEUED',attempts=0,next_attempt_at=now(),lease_token=null,lease_until=null,error_code=null where id=?",
                b.deliveryId());
          } else {
            var job =
                s.one(
                    "select *,lease_until>now() as leased,expires_at>now() as within_window from private_mail where id=? and event_id=? for update",
                    b.deliveryId(),
                    id);
            previous = job.get("state").toString();
            if (!Boolean.TRUE.equals(job.get("within_window"))
                || Boolean.TRUE.equals(job.get("leased"))
                || !Set.of("FAILED", "WAITING_CONFIGURATION").contains(previous))
              throw fail(
                  409,
                  "DELIVERY_NOT_RETRYABLE",
                  "Only unexpired failed or unconfigured email deliveries can be retried.");
            var payload = s.json.tree(s.crypto.open(job.get("payload_cipher").toString()));
            if (!"NOTIFICATION".equals(payload.path("template").asText())
                || !Crypto.equal(
                    payload.path("to").asText(), Objects.toString(ctx.get("email"), "")))
              throw fail(
                  409,
                  "EMAIL_OR_PREFERENCE_REMOVED",
                  "The original verified email is no longer eligible.");
            s.db.update(
                "update private_mail set state='QUEUED',attempts=0,next_attempt_at=now(),lease_token=null,lease_until=null,last_error_code=null where id=?",
                b.deliveryId());
          }
          String reason = text(b.reason(), 5, 500, "Retry reason");
          s.db.update(
              "update notifications set delivery_version=delivery_version+1 where id=?", id);
          s.db.update(
              "insert into delivery_retry_history(id,school_id,notification_id,channel,delivery_id,actor_id,reason,previous_state) values (?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              school,
              id,
              b.channel(),
              b.deliveryId(),
              a.accountId(),
              reason,
              previous);
          s.commands.audit(
              school,
              a.accountId(),
              "NOTIFICATION_RETRY_REQUESTED",
              "NOTIFICATION",
              id,
              reason,
              map("channel", b.channel(), "deliveryId", b.deliveryId(), "previousState", previous));
          return map("id", id, "version", version + 1, "queued", true, "channel", b.channel());
        });
  }
}
