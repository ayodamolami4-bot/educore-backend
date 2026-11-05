package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/assignments")
public class AssignmentController {
  private final AssignmentService s;

  public AssignmentController(AssignmentService s) {
    this.s = s;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @PostMapping
  Object create(
      @PathVariable UUID school,
      @RequestBody AssignmentService.Input b,
      @RequestHeader HttpHeaders h) {
    return s.create(Actor.current(), school, b, key(h));
  }

  @GetMapping
  Object list(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) UUID termId,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.list(Actor.current(), school, classId, termId, cursor, Api.pageSize(limit));
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.detail(Actor.current(), school, id);
  }

  @PutMapping("/{id}")
  Object edit(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssignmentService.Input b,
      @RequestHeader HttpHeaders h) {
    return s.edit(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/{id}/publish")
  Object publish(@PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return s.publish(Actor.current(), school, id, version(h), key(h));
  }

  @GetMapping("/{id}/submissions/{student}")
  Object submission(@PathVariable UUID school, @PathVariable UUID id, @PathVariable UUID student) {
    return s.getSubmission(Actor.current(), school, id, student);
  }

  @PostMapping("/{id}/changes")
  Object change(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssignmentService.Change b,
      @RequestHeader HttpHeaders h) {
    return s.change(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/{id}/submissions")
  Object submissions(@PathVariable UUID school, @PathVariable UUID id) {
    return s.submissions(Actor.current(), school, id);
  }

  @PutMapping("/{id}/my-submission")
  Object save(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssignmentService.Draft b,
      @RequestHeader HttpHeaders h) {
    return s.save(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/{id}/submit")
  Object submit(@PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return s.submit(Actor.current(), school, id, version(h), key(h));
  }

  @PostMapping("/{id}/submissions/{student}/feedback")
  Object feedback(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID student,
      @RequestBody AssignmentService.Feedback b,
      @RequestHeader HttpHeaders h) {
    return s.feedback(Actor.current(), school, id, student, b, version(h), key(h));
  }

  @GetMapping("/{id}/submissions/{student}/history")
  Object history(@PathVariable UUID school, @PathVariable UUID id, @PathVariable UUID student) {
    return s.history(Actor.current(), school, id, student);
  }
}
