package com.educore.platform;

import static com.educore.platform.Api.*;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Each instance reads only its own service's audit database. The gateway selects the owner. */
@RestController
public class ActivityController {
  private final JdbcTemplate db;
  private final Json json;
  private final String owner;

  public ActivityController(
      JdbcTemplate db, Json json, @Value("${educore.service-name}") String owner) {
    this.db = db;
    this.json = json;
    this.owner = owner;
  }

  private void permission(UUID school, String domain) {
    if (!owner.equals(domain))
      throw fail(404, "NOT_FOUND", "This service does not own that history.");
    var a = Actor.current();
    switch (owner) {
      case "academics" -> a.school(school, "PRINCIPAL", "SCHOOL_OFFICE");
      case "finance" -> a.school(school, "PRINCIPAL", "ACCOUNTS");
      default -> a.school(school, "PRINCIPAL");
    }
  }

  private List<Map<String, Object>> rows(
      UUID school, String domain, LocalDate from, LocalDate to, UUID cursor, int limit) {
    permission(school, domain);
    LocalDate end = to == null ? LocalDate.now(ZoneId.of("Africa/Lagos")) : to,
        start = from == null ? end.minusDays(90) : from;
    if (start.isAfter(end) || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 366)
      throw fail(422, "INVALID_HISTORY_PERIOD", "Select a history period of at most 366 days.");
    var rows =
        db.queryForList(
            "select id,actor_id as \"actorId\",action,resource_type as \"resourceType\",resource_id as \"resourceId\",reason,details,created_at as \"createdAt\" from audit_entries where school_id=? and created_at>=?::date and created_at<?::date+1 and (?::uuid is null or (created_at,id)<(select created_at,id from audit_entries where school_id=? and id=?::uuid)) order by created_at desc,id desc limit ?",
            school,
            start,
            end,
            cursor,
            school,
            cursor,
            limit);
    for (var r : rows) r.put("details", json.value(r.get("details").toString()));
    return rows;
  }

  @GetMapping("/api/v1/schools/{school}/activity/{domain}")
  Object history(
      @PathVariable UUID school,
      @PathVariable String domain,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    int count = pageSize(limit);
    return page(
        rows(school, domain, from, to, cursor, count + 1), count, r -> r.get("id").toString());
  }

  @GetMapping("/api/v1/schools/{school}/activity/{domain}/export.csv")
  ResponseEntity<byte[]> export(
      @PathVariable UUID school,
      @PathVariable String domain,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    var data = rows(school, domain, from, to, null, 10001);
    if (data.size() > 10000)
      throw fail(422, "EXPORT_TOO_LARGE", "Choose a shorter activity-history date range.");
    var out = new ArrayList<List<?>>();
    for (var r : data)
      out.add(
          Arrays.asList(
              r.get("id"),
              r.get("actorId"),
              r.get("action"),
              r.get("resourceType"),
              r.get("resourceId"),
              r.get("reason"),
              r.get("createdAt")));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"activity-" + domain + ".csv\"")
        .body(
            Documents.csv(
                List.of(
                    "entry_id",
                    "actor_id",
                    "action",
                    "resource_type",
                    "resource_id",
                    "reason",
                    "created_at"),
                out));
  }

  @GetMapping("/api/v1/me/security-history")
  Object personal(
      @RequestParam(required = false) UUID cursor, @RequestParam(defaultValue = "30") int limit) {
    if (!"identity".equals(owner))
      throw fail(404, "NOT_FOUND", "This service does not own account history.");
    var a = Actor.current();
    int count = pageSize(limit);
    return page(
        db.queryForList(
            "select id,action,created_at as \"createdAt\" from audit_entries where school_id is null and actor_id=? and (?::uuid is null or id<?::uuid) order by id desc limit ?",
            a.accountId(),
            cursor,
            cursor,
            count + 1),
        count,
        r -> r.get("id").toString());
  }
}
