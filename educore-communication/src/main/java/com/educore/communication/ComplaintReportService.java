package com.educore.communication;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ComplaintReportService {
  private final CommunicationService s;

  public ComplaintReportService(CommunicationService s) {
    this.s = s;
  }

  public Object catalogue(Actor a, UUID school) {
    a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS");
    return map(
        "items",
        List.of(
            map(
                "kind",
                "BACKLOG",
                "meaning",
                "All cases not closed, including resolutions awaiting parent acceptance."),
            map("kind", "CASE_REGISTER", "meaning", "Permitted open and closed cases.")),
        "filters",
        List.of("category", "state", "ownerId", "from", "to"),
        "formats",
        List.of("JSON", "CSV", "PDF"),
        "maximumRows",
        1000);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ReportData build(
      Actor a,
      UUID school,
      String rawKind,
      String category,
      String state,
      UUID ownerId,
      LocalDate from,
      LocalDate to) {
    String role = a.school(school, "PRINCIPAL", "SCHOOL_OFFICE", "ACCOUNTS").role(),
        kind = rawKind.toUpperCase(Locale.ROOT);
    if (!Set.of("BACKLOG", "CASE_REGISTER").contains(kind))
      throw fail(404, "REPORT_NOT_FOUND", "Choose a report from the complaints catalogue.");
    if (category != null
        && !Set.of("GENERAL", "ATTENDANCE", "ACADEMIC_RECORDS", "STAFF_CONDUCT", "FEES")
            .contains(category))
      throw fail(422, "INVALID_COMPLAINT_CATEGORY", "Choose a supported complaint category.");
    if (state != null
        && !Set.of("NEW", "IN_REVIEW", "WAITING_FOR_PARENT", "RESOLVED", "CLOSED").contains(state))
      throw fail(422, "INVALID_CASE_STATE", "Choose a supported case state.");
    if (from != null && to != null && (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 366))
      throw fail(422, "INVALID_REPORT_DATES", "Use ordered report dates within 367 days.");
    var rows =
        s.db.queryForList(
            "select id as \"caseId\",student_id as \"studentId\",category,state,owner_id as \"ownerId\",intake_channel as \"intakeChannel\",created_at as \"createdAt\",first_response_due_at as \"firstResponseDueAt\",first_response_at as \"firstResponseAt\",first_response_due_at is not null as \"calendarConfigured\",first_response_at is null and first_response_due_at<now() and state<>'CLOSED' as \"firstResponseOverdue\",round(extract(epoch from (coalesce(closed_at,now())-created_at))/3600,1) as \"elapsedHours\",version from complaints where school_id=? and (?<>'ACCOUNTS' or category='FEES') and (?<>'SCHOOL_OFFICE' or category in ('GENERAL','ATTENDANCE','ACADEMIC_RECORDS')) and (?<>'BACKLOG' or state<>'CLOSED') and (?::text is null or category=?::text) and (?::text is null or state=?::text) and (?::uuid is null or owner_id=?::uuid) and (?::date is null or (created_at at time zone 'Africa/Lagos')::date>=?::date) and (?::date is null or (created_at at time zone 'Africa/Lagos')::date<=?::date) order by created_at,id limit 1001",
            school,
            role,
            role,
            kind,
            category,
            category,
            state,
            state,
            ownerId,
            ownerId,
            from,
            from,
            to,
            to);
    return new ReportData(
        kind,
        "Complaints: " + kind.replace('_', ' '),
        List.of(
            "caseId",
            "studentId",
            "category",
            "state",
            "ownerId",
            "intakeChannel",
            "createdAt",
            "firstResponseDueAt",
            "firstResponseAt",
            "calendarConfigured",
            "firstResponseOverdue",
            "elapsedHours",
            "version"),
        rows,
        List.of(
            "Only the selected staff preset's permitted categories are included. Message bodies, attachments and private notes are excluded.",
            "Response deadlines use configured teaching days. An unconfigured calendar is labelled rather than replaced by an invented deadline.",
            "Elapsed hours are descriptive; they are not the teaching-day response deadline."));
  }
}
