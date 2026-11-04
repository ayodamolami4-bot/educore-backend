package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/assignments/{assignment}/submissions/{student}")
public class SubmissionWorkController {
  private final SubmissionWorkService s;

  public SubmissionWorkController(SubmissionWorkService s) {
    this.s = s;
  }

  @PostMapping("/physical-receipt")
  Object physical(
      @PathVariable UUID school,
      @PathVariable UUID assignment,
      @PathVariable UUID student,
      @RequestBody SubmissionWorkService.Physical b,
      @RequestHeader HttpHeaders h) {
    return s.physical(
        Actor.current(),
        school,
        assignment,
        student,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/review-draft")
  Object draft(
      @PathVariable UUID school, @PathVariable UUID assignment, @PathVariable UUID student) {
    return s.getDraft(Actor.current(), school, assignment, student);
  }

  @PutMapping("/review-draft")
  Object save(
      @PathVariable UUID school,
      @PathVariable UUID assignment,
      @PathVariable UUID student,
      @RequestBody AssignmentService.Feedback b,
      @RequestHeader HttpHeaders h) {
    return s.saveDraft(
        Actor.current(),
        school,
        assignment,
        student,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @PostMapping("/review-draft/release")
  Object release(
      @PathVariable UUID school,
      @PathVariable UUID assignment,
      @PathVariable UUID student,
      @RequestHeader HttpHeaders h) {
    return s.release(
        Actor.current(),
        school,
        assignment,
        student,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }
}
