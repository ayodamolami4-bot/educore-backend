package com.educore.academics;

import static org.junit.jupiter.api.Assertions.*;

import com.educore.platform.Api;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchoolRulesTest {
  @Test
  void lessonsCannotCrossDailyBreak() {
    assertDoesNotThrow(() -> SchoolRules.lesson(1, 630, 60, true));
    assertDoesNotThrow(() -> SchoolRules.lesson(1, 720, 30, true));
    assertEquals(
        "BREAK_CONFLICT",
        assertThrows(Api.Failure.class, () -> SchoolRules.lesson(1, 660, 60, true)).code);
    assertThrows(Api.Failure.class, () -> SchoolRules.lesson(1, 690, 30, true));
  }

  @Test
  void ptIsOptionalButFridaySportsAndClosingTimeAreReserved() {
    assertThrows(Api.Failure.class, () -> SchoolRules.lesson(3, 480, 30, true));
    assertDoesNotThrow(() -> SchoolRules.lesson(3, 480, 30, false));
    assertThrows(Api.Failure.class, () -> SchoolRules.lesson(5, 720, 30, false));
    assertThrows(Api.Failure.class, () -> SchoolRules.lesson(2, 750, 60, false));
    assertThrows(Api.Failure.class, () -> SchoolRules.lesson(6, 480, 30, false));
  }

  @ParameterizedTest
  @CsvSource({
    "70,A", "69.99,B", "60,B", "59.99,C", "50,C", "49.99,D", "45,D", "44.99,E", "40,E", "39.99,F",
    "0,F", "100,A"
  })
  void schoolInternalBandsHaveExactBoundaries(String score, String band) {
    assertEquals(band, SchoolRules.grade(new BigDecimal(score)));
  }

  @Test
  void incompleteOrOversizedComponentsDoNotBecomeZeroOrAValidTotal() {
    assertThrows(Api.Failure.class, () -> SchoolRules.total(null, BigDecimal.TEN, BigDecimal.TEN));
    assertThrows(
        Api.Failure.class,
        () -> SchoolRules.total(new BigDecimal("21"), BigDecimal.TEN, BigDecimal.TEN));
    assertEquals(
        new BigDecimal("100"),
        SchoolRules.total(new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("60")));
  }

  private SchoolRules.AnnualSubject subject(String code, String score) {
    return new SchoolRules.AnnualSubject(
        code, List.of(new BigDecimal(score), new BigDecimal(score), new BigDecimal(score)));
  }

  @Test
  void promotionUsesAllThresholdsAndNeverFeeDebt() {
    var eligible =
        SchoolRules.promotion(
            List.of(
                subject("ENGLISH", "40"), subject("MATHEMATICS", "60"), subject("SCIENCE", "50")));
    assertEquals("PROMOTE", eligible.recommendation());
    var failedEnglish =
        SchoolRules.promotion(
            List.of(
                subject("ENGLISH", "39"), subject("MATHEMATICS", "70"), subject("SCIENCE", "80")));
    assertEquals("REVIEW", failedEnglish.recommendation());
    assertTrue(failedEnglish.reasons().stream().anyMatch(r -> r.contains("English")));
  }

  @Test
  void missingTermsRequireReviewAndCannotBeSilentlyAveraged() {
    var result =
        SchoolRules.promotion(
            List.of(
                new SchoolRules.AnnualSubject("ENGLISH", List.of(new BigDecimal("90"))),
                subject("MATHEMATICS", "80")));
    assertEquals("REVIEW", result.recommendation());
    assertNull(result.annualMean());
  }

  @Test
  void roundedDisplayDoesNotChangePromotionEligibility() {
    var result =
        SchoolRules.promotion(
            List.of(subject("ENGLISH", "49.99"), subject("MATHEMATICS", "50.00")));
    assertEquals("50.00", result.annualMean().toPlainString());
    assertEquals("REVIEW", result.recommendation());
  }

  @Test
  void sssDepartmentDoesNotLeakIntoJssOrAcceptUnknownPathways() {
    assertNull(SchoolRules.department("JSS1", null));
    assertThrows(Api.Failure.class, () -> SchoolRules.department("JSS1", "SCIENCE"));
    assertThrows(Api.Failure.class, () -> SchoolRules.department("SSS1", null));
    assertEquals("SCIENCE", SchoolRules.department("SSS1", "SCIENCE"));
  }
}
