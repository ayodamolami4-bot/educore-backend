package com.educore.academics;

import com.educore.platform.Api;
import java.math.*;
import java.time.*;
import java.util.*;

public final class SchoolRules {
  private SchoolRules() {}

  public static void lesson(int weekday, int start, int duration, boolean pt) {
    int end = start + duration;
    if (weekday < 1
        || weekday > 5
        || start < 480
        || end > 780
        || start % 30 != 0
        || duration != 30 && duration != 60)
      throw Api.fail(
          422,
          "INVALID_PERIOD",
          "Use a 30 or 60 minute lesson between 08:00 and 13:00 on a teaching weekday.");
    if (start < 720 && end > 690)
      throw Api.fail(422, "BREAK_CONFLICT", "The daily 11:30–12:00 break is reserved.");
    if (weekday == 5 && end > 720)
      throw Api.fail(422, "SPORTS_CONFLICT", "Friday 12:00–13:00 is reserved for sports.");
    if (weekday == 3 && pt && start < 510)
      throw Api.fail(422, "PT_CONFLICT", "Wednesday 08:00–08:30 is reserved while PT is enabled.");
  }

  public static String department(String level, String value) {
    if (level.startsWith("JSS")) {
      if (value != null && !value.isBlank())
        throw Api.fail(422, "JSS_DEPARTMENT", "JSS classes do not use SSS departments.");
      return null;
    }
    if (value == null || !Set.of("SCIENCE", "ARTS", "COMMERCIAL").contains(value))
      throw Api.fail(
          422, "DEPARTMENT_REQUIRED", "Select SCIENCE, ARTS or COMMERCIAL for an SSS student.");
    return value;
  }

  public static BigDecimal total(BigDecimal ca1, BigDecimal ca2, BigDecimal exam) {
    score(ca1, 20);
    score(ca2, 20);
    score(exam, 60);
    return ca1.add(ca2).add(exam);
  }

  private static void score(BigDecimal value, int max) {
    if (value == null
        || value.signum() < 0
        || value.compareTo(BigDecimal.valueOf(max)) > 0
        || value.stripTrailingZeros().scale() > 1)
      throw Api.fail(
          422, "INVALID_SCORE", "A required component is missing or exceeds its maximum.");
  }

  public static String grade(BigDecimal total) {
    if (total == null || total.signum() < 0 || total.compareTo(new BigDecimal("100")) > 0)
      throw Api.fail(422, "INVALID_TOTAL", "Total must be between zero and 100.");
    int[] thresholds = {70, 60, 50, 45, 40};
    String[] bands = {"A", "B", "C", "D", "E"};
    for (int i = 0; i < thresholds.length; i++)
      if (total.compareTo(BigDecimal.valueOf(thresholds[i])) >= 0) return bands[i];
    return "F";
  }

  public record AnnualSubject(String code, List<BigDecimal> publishedTermTotals) {}

  public record PromotionDecision(
      String recommendation, BigDecimal annualMean, BigDecimal passPercent, List<String> reasons) {}

  public static PromotionDecision promotion(List<AnnualSubject> subjects) {
    var reasons = new ArrayList<String>();
    var means = new HashMap<String, BigDecimal>();
    if (subjects == null || subjects.isEmpty())
      return new PromotionDecision(
          "REVIEW", null, null, List.of("No complete subject combination."));
    for (var s : subjects) {
      if (s.publishedTermTotals() == null
          || s.publishedTermTotals().size() != 3
          || s.publishedTermTotals().stream().anyMatch(Objects::isNull)) {
        reasons.add("Three published terms are required for " + s.code());
        continue;
      }
      if (means.containsKey(s.code()))
        throw Api.fail(422, "DUPLICATE_SUBJECT", "Each annual subject must occur once.");
      s.publishedTermTotals().forEach(SchoolRules::grade);
      means.put(
          s.code(),
          s.publishedTermTotals().stream()
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_UP));
    }
    if (!reasons.isEmpty() || !means.containsKey("ENGLISH") || !means.containsKey("MATHEMATICS")) {
      reasons.add("Required English/Mathematics or term evidence is incomplete.");
      return new PromotionDecision("REVIEW", null, null, List.copyOf(reasons));
    }
    BigDecimal mean =
        means.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(means.size()), 8, RoundingMode.HALF_UP);
    long passed =
        means.values().stream().filter(v -> v.compareTo(new BigDecimal("40")) >= 0).count();
    BigDecimal percent =
        BigDecimal.valueOf(passed * 100)
            .divide(BigDecimal.valueOf(means.size()), 8, RoundingMode.HALF_UP);
    if (mean.compareTo(new BigDecimal("50")) < 0) reasons.add("Annual mean is below 50.");
    if (means.get("ENGLISH").compareTo(new BigDecimal("40")) < 0)
      reasons.add("English is below 40.");
    if (means.get("MATHEMATICS").compareTo(new BigDecimal("40")) < 0)
      reasons.add("Mathematics is below 40.");
    if (percent.compareTo(new BigDecimal("60")) < 0)
      reasons.add("Fewer than 60 percent of enrolled subjects passed.");
    return new PromotionDecision(
        reasons.isEmpty() ? "PROMOTE" : "REVIEW",
        mean.setScale(2, RoundingMode.HALF_UP),
        percent.setScale(2, RoundingMode.HALF_UP),
        List.copyOf(reasons));
  }
}
