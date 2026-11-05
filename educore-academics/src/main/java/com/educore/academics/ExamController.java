package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class ExamController {
  private final ExamService e;

  public ExamController(ExamService e) {
    this.e = e;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @PostMapping("/exam-schedules")
  Object create(
      @PathVariable UUID school,
      @RequestBody ExamService.Schedule b,
      @RequestHeader HttpHeaders h) {
    return e.create(Actor.current(), school, b, key(h));
  }

  @GetMapping("/exam-schedules")
  Object list(@PathVariable UUID school, @RequestParam(required = false) UUID termId) {
    return e.schedules(Actor.current(), school, termId);
  }

  @GetMapping("/exam-schedules/{id}/review")
  Object review(@PathVariable UUID school, @PathVariable UUID id) {
    return e.review(Actor.current(), school, id);
  }

  @PostMapping("/exam-schedules/{id}/events")
  Object add(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody ExamService.Event b,
      @RequestHeader HttpHeaders h) {
    return e.event(Actor.current(), school, id, null, b, version(h), key(h));
  }

  @PutMapping("/exam-schedules/{id}/events/{event}")
  Object edit(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID event,
      @RequestBody ExamService.Event b,
      @RequestHeader HttpHeaders h) {
    return e.event(Actor.current(), school, id, event, b, version(h), key(h));
  }

  @PostMapping("/exam-schedules/{id}/events/{event}/remove")
  Object remove(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID event,
      @RequestHeader HttpHeaders h) {
    return e.remove(Actor.current(), school, id, event, version(h), key(h));
  }

  @PostMapping("/exam-schedules/{id}/publish")
  Object publish(@PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return e.publish(Actor.current(), school, id, version(h), key(h));
  }

  @GetMapping("/examinations")
  Object published(@PathVariable UUID school, @RequestParam UUID termId) {
    return e.published(Actor.current(), school, termId);
  }

  @PostMapping("/students/{id}/external-results")
  Object external(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody ExamService.External b,
      @RequestHeader HttpHeaders h) {
    return e.external(Actor.current(), school, id, b, key(h));
  }

  @GetMapping("/students/{id}/external-results")
  Object external(@PathVariable UUID school, @PathVariable UUID id) {
    return e.external(Actor.current(), school, id);
  }
}
