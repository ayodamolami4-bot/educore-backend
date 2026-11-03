package com.educore.academics;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** A dated projection; published weekly plans and examination revisions remain immutable. */
@Service
public class DailyScheduleService {
  private final SchoolService s;
  private final TimetableService timetable;
  private final ExamService exams;

  public DailyScheduleService(SchoolService s, TimetableService timetable, ExamService exams) {
    this.s = s;
    this.timetable = timetable;
    this.exams = exams;
  }

  @SuppressWarnings("unchecked")
  public Object day(Actor a, UUID school, UUID classId, LocalDate requested) {
    var context =
        a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS", "TEACHER", "PARENT", "STUDENT");
    LocalDate on = requested == null ? LocalDate.now(ZoneId.of("Africa/Lagos")) : requested;
    var weekly = (Map<String, Object>) timetable.published(a, school, classId, on);
    var terms =
        s.db.queryForList(
            "select id from terms where school_id=? and starts_on<=? and ends_on>=?",
            school,
            on,
            on);
    var exceptions =
        s.db.queryForList(
            "select teaching_day,title from calendar_exceptions where school_id=? and on_date=?",
            school,
            on);
    boolean teaching =
        !terms.isEmpty()
            && (exceptions.isEmpty()
                ? on.getDayOfWeek().getValue() <= 5
                : Boolean.TRUE.equals(exceptions.get(0).get("teaching_day")));
    var result =
        map(
            "on",
            on,
            "timezone",
            "Africa/Lagos",
            "teachingDay",
            teaching,
            "weeklyPublished",
            weekly.get("published"),
            "items",
            List.of());
    if (!teaching) {
      if (!exceptions.isEmpty()) result.put("calendarTitle", exceptions.get(0).get("title"));
      return result;
    }
    UUID term = (UUID) terms.get(0).get("id");
    var visible = (Map<String, Object>) exams.published(a, school, term);
    var examItems = new ArrayList<>((List<Map<String, Object>>) visible.get("items"));
    examItems.removeIf(
        e ->
            !on.equals(((java.sql.Date) e.get("onDate")).toLocalDate())
                || (classId != null && !classId.equals(e.get("classId")))
                || ("TEACHER".equals(context.role())
                    && !context.subjectId().equals(e.get("invigilatorId"))));
    boolean family = Set.of("PARENT", "STUDENT").contains(context.role());
    // A teacher's normal lesson is released only when every affected pupil is in an exam.
    var replacements = family ? examItems : exams.publishedEvents(school, term);
    var items =
        new ArrayList<>(
            exams.dayLessons(
                school, (List<Map<String, Object>>) weekly.get("items"), replacements, on, family));
    for (var e : examItems) {
      var item = new LinkedHashMap<>(e);
      item.put("type", "EXAM");
      items.add(item);
    }
    activity(items, examItems, "BREAK", 690, 720, family || "TEACHER".equals(context.role()));
    String activity =
        on.getDayOfWeek().getValue() == 5
            ? "FRIDAY_SPORTS"
            : on.getDayOfWeek().getValue() == 3
                    && (Boolean.TRUE.equals(weekly.get("published"))
                        ? Boolean.TRUE.equals(weekly.get("wednesdayPtEnabled"))
                        : Boolean.TRUE.equals(s.school(school).get("wednesday_pt")))
                ? "WEDNESDAY_PT"
                : null;
    if (activity != null
        && (!"TEACHER".equals(context.role())
            || s.db.queryForObject(
                    "select count(*) from activity_supervisors where term_id=? and activity=? and teacher_id=?",
                    Integer.class,
                    term,
                    activity,
                    context.subjectId())
                > 0))
      activity(
          items,
          examItems,
          activity,
          "WEDNESDAY_PT".equals(activity) ? 480 : 720,
          "WEDNESDAY_PT".equals(activity) ? 510 : 780,
          family || "TEACHER".equals(context.role()));
    items.sort(
        Comparator.comparingInt(
                (Map<String, Object> i) -> ((Number) i.get("startMinute")).intValue())
            .thenComparing(i -> i.get("type").toString()));
    result.put("termId", term);
    result.put("examinationsPublished", visible.get("published"));
    result.put("items", items);
    return result;
  }

  private void activity(
      List<Map<String, Object>> items,
      List<Map<String, Object>> exams,
      String type,
      int start,
      int end,
      boolean trim) {
    var boundaries = new TreeSet<Integer>(List.of(start, end));
    if (trim)
      for (var e : exams) {
        int x = ((Number) e.get("startMinute")).intValue(),
            y = ((Number) e.get("endMinute")).intValue();
        if (x > start && x < end) boundaries.add(x);
        if (y > start && y < end) boundaries.add(y);
      }
    var points = new ArrayList<>(boundaries);
    for (int n = 0; n < points.size() - 1; n++) {
      int x = points.get(n), y = points.get(n + 1);
      if (!trim
          || exams.stream()
              .noneMatch(
                  e ->
                      ((Number) e.get("startMinute")).intValue() <= x
                          && ((Number) e.get("endMinute")).intValue() >= y))
        items.add(map("type", type, "startMinute", x, "endMinute", y));
    }
  }
}
