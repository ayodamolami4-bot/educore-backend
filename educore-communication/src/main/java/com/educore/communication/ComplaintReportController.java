package com.educore.communication;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/complaints/reports")
public class ComplaintReportController {
  private final ComplaintReportService s;

  public ComplaintReportController(ComplaintReportService s) {
    this.s = s;
  }

  @GetMapping("/catalogue")
  Object catalogue(@PathVariable UUID school) {
    return s.catalogue(Actor.current(), school);
  }

  @GetMapping("/{kind}")
  ResponseEntity<?> report(
      @PathVariable UUID school,
      @PathVariable String kind,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "JSON") String format) {
    return s.build(Actor.current(), school, kind, category, state, ownerId, from, to)
        .response(format);
  }
}
