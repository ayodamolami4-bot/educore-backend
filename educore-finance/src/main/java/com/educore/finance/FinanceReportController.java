package com.educore.finance;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/reconciliation/reports")
public class FinanceReportController {
  private final FinanceReportService s;

  public FinanceReportController(FinanceReportService s) {
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
      @RequestParam(required = false) UUID termId,
      @RequestParam(required = false) UUID studentId,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "JSON") String format) {
    return s.build(Actor.current(), school, kind, termId, studentId, source, from, to)
        .response(format);
  }
}
