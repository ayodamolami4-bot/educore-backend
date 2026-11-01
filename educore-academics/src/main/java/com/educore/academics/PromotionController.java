package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class PromotionController {
  private final PromotionService s;

  public PromotionController(PromotionService s) {
    this.s = s;
  }

  @PostMapping("/promotion-batches")
  Object prepare(
      @PathVariable UUID school,
      @RequestBody PromotionService.Preview b,
      @RequestHeader("Idempotency-Key") String key) {
    return s.prepare(Actor.current(), school, b, key);
  }

  @GetMapping("/promotion-batches")
  Object list(@PathVariable UUID school) {
    return s.list(Actor.current(), school);
  }

  @GetMapping("/promotion-batches/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.detail(Actor.current(), school, id);
  }

  @PutMapping("/promotion-batches/{id}/students/{student}")
  Object decide(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID student,
      @RequestBody PromotionService.Decision b,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("If-Match") String version) {
    return s.decide(Actor.current(), school, id, student, b, Api.version(version), key);
  }

  @PostMapping("/promotion-batches/{id}/confirm")
  Object confirm(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("If-Match") String version) {
    return s.confirm(Actor.current(), school, id, Api.version(version), key);
  }

  @GetMapping("/promotion-batches/{id}/outcomes.csv")
  ResponseEntity<byte[]> csv(@PathVariable UUID school, @PathVariable UUID id) {
    return AssessmentController.download(
        s.csv(Actor.current(), school, id),
        MediaType.parseMediaType("text/csv;charset=UTF-8"),
        "outcomes-" + id + ".csv");
  }

  @GetMapping("/students/{student}/academic-history")
  Object history(@PathVariable UUID school, @PathVariable UUID student) {
    return s.history(Actor.current(), school, student);
  }
}
