package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/documents")
public class SchoolDocumentController {
  private final SchoolDocumentService s;

  public SchoolDocumentController(SchoolDocumentService s) {
    this.s = s;
  }

  @GetMapping
  Object list(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID studentId,
      @RequestParam(required = false) UUID staffId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.list(Actor.current(), school, studentId, staffId, kind, cursor, Api.pageSize(limit));
  }

  @PostMapping
  Object create(
      @PathVariable UUID school,
      @RequestBody SchoolDocumentService.Input b,
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
      @RequestBody SchoolDocumentService.Input b,
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
      @RequestBody SchoolDocumentService.Reason b,
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
