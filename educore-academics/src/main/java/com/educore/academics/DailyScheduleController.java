package com.educore.academics;

import com.educore.platform.Actor;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class DailyScheduleController {
  private final DailyScheduleService schedules;

  public DailyScheduleController(DailyScheduleService schedules) {
    this.schedules = schedules;
  }

  @GetMapping("/api/v1/schools/{school}/schedule/day")
  Object day(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) LocalDate on) {
    return schedules.day(Actor.current(), school, classId, on);
  }
}
