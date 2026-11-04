package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/materials")
public class MaterialController {
  private final MaterialService s;

  public MaterialController(MaterialService s) {
    this.s = s;
  }

  @GetMapping
  Object list(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) UUID subjectId,
      @RequestParam(required = false) UUID termId,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.list(Actor.current(), school, classId, subjectId, termId, cursor, Api.pageSize(limit));
  }

  @PostMapping
  Object create(
      @PathVariable UUID school,
      @RequestBody MaterialService.Input b,
      @RequestHeader HttpHeaders h) {
    return s.create(Actor.current(), school, b, h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.detail(Actor.current(), school, id);
  }

  @PutMapping("/{id}")
  Object edit(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody MaterialService.Input b,
      @RequestHeader HttpHeaders h) {
    return s.edit(
        Actor.current(),
        school,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @PostMapping("/{id}/publish")
  Object publish(@PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return s.publish(
        Actor.current(),
        school,
        id,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @PostMapping("/{id}/archive")
  Object archive(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody MaterialService.Reason b,
      @RequestHeader HttpHeaders h) {
    return s.archive(
        Actor.current(),
        school,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/{id}/history")
  Object history(@PathVariable UUID school, @PathVariable UUID id) {
    return s.history(Actor.current(), school, id);
  }
}
