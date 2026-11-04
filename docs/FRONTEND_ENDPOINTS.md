# Implemented endpoints and request fields

Reviewed 2026-11-04. These 272 public operations exist in the Java controllers. Prefix paths with `/api/v1`. Internal routes are excluded.

Start with [SCREEN_CONNECTIONS.md](SCREEN_CONNECTIONS.md) and [API.md](API.md) for permissions, workflows, versions and response adapters. Views are implemented by the frontend team.

Protected requests use `Authorization: Bearer <accessToken>`. Commands retain their key, body and version when retried. `If-Match` uses the returned resource version, for example `"v3"`.

| Method | Path | Request record | Idempotency-Key | If-Match | Query fields |
| --- | --- | --- | --- | --- | --- |
| POST | `/schools/{school}/timetable-plans` | TimetableService.PlanInput | required | — | — |
| GET | `/schools/{school}/timetable-plans` | — | — | — | termId |
| POST | `/schools/{school}/timetable-plans/{planId}/lessons` | TimetableService.LessonInput | required | required | — |
| PUT | `/schools/{school}/timetable-plans/{planId}/lessons/{lessonId}` | TimetableService.LessonInput | required | required | — |
| POST | `/schools/{school}/timetable-plans/{planId}/lessons/{lessonId}/remove` | — | required | required | — |
| GET | `/schools/{school}/timetable-plans/{planId}/review` | — | — | — | — |
| POST | `/schools/{school}/timetable-plans/{planId}/publish` | — | required | required | — |
| GET | `/schools/{school}/timetable` | — | — | — | classId, on |
| POST | `/schools/{school}/attendance/registers` | AttendanceService.RegisterInput | required | — | — |
| GET | `/schools/{school}/attendance/registers` | — | — | — | classId, from, to, state, cursor, limit |
| GET | `/schools/{school}/attendance/registers/{registerId}` | — | — | — | — |
| PUT | `/schools/{school}/attendance/registers/{registerId}/marks` | AttendanceService.Marks | required | required | — |
| POST | `/schools/{school}/attendance/registers/{registerId}/submit` | — | required | required | — |
| POST | `/schools/{school}/attendance/registers/{registerId}/corrections` | AttendanceService.Mark | required | required | — |
| GET | `/schools/{school}/attendance/overview` | — | — | — | on |
| GET | `/schools/{school}/students/{studentId}/attendance` | — | — | — | from, to |
| GET | `/schools/{school}/terms` | — | — | — | sessionId |
| POST | `/schools/{school}/terms` | SchoolService.TermInput | required | — | — |
| GET | `/schools/{school}/teaching-assignments` | — | — | — | classId, includeInactive |
| POST | `/schools/{school}/teaching-assignments` | SchoolWorkspaceService.Teaching | required | — | — |
| POST | `/schools/{school}/attendance/registers/{registerId}/absence-alerts` | AcademicController.AttendanceTeacherAlert | required | — | — |
| POST | `/schools/{school}/calendar-exceptions` | AcademicController.CalendarInput | required | — | — |
| GET | `/schools/{school}/imports/templates/{kind}` | — | — | — | — |
| GET | `/schools/{school}/imports` | — | — | — | — |
| POST | `/schools/{school}/imports` | AcademicImportService.Preview | required | — | — |
| GET | `/schools/{school}/imports/{jobId}` | — | — | — | — |
| PUT | `/schools/{school}/imports/{jobId}/rows/{rowId}` | AcademicImportService.Correction | required | required | — |
| POST | `/schools/{school}/imports/{jobId}/confirm` | AcademicImportService.Confirmation | required | required | — |
| GET | `/schools/{school}/imports/{jobId}/errors.csv` | — | — | — | — |
| GET | `/schools/{school}/staff` | — | — | — | cursor, search, role, active, limit |
| POST | `/schools/{school}/staff` | AdministrationService.StaffInput | required | — | — |
| GET | `/schools/{school}/staff/{staffId}` | — | — | — | — |
| PATCH | `/schools/{school}/staff/{staffId}` | AdministrationService.StaffEdit | required | required | — |
| POST | `/schools/{school}/staff/{staffId}/access` | AdministrationService.AccessInput | required | required | — |
| PUT | `/schools/{school}/teaching-assignments/{id}` | AdministrationService.AssignmentEdit | required | required | — |
| PATCH | `/schools/{school}/students/{studentId}` | AdministrationService.StudentEdit | required | required | — |
| GET | `/schools/{school}/students/{studentId}` | — | — | — | — |
| POST | `/schools/{school}/students/{studentId}/transfers` | AdministrationService.Transfer | required | required | — |
| POST | `/schools/{school}/students/{studentId}/withdrawal` | AdministrationService.Withdrawal | required | required | — |
| POST | `/schools/{school}/students/{studentId}/guardians` | AdministrationService.GuardianLink | required | conditional | — |
| POST | `/schools/{school}/assessment-cycles` | AssessmentService.CycleInput | required | — | — |
| GET | `/schools/{school}/assessment-cycles` | — | — | — | termId, classId |
| GET | `/schools/{school}/assessment-cycles/{cycleId}` | — | — | — | — |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/open` | AssessmentService.Window | required | required | — |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/roster/sync` | AssessmentService.RosterSync | required | required | — |
| GET | `/schools/{school}/assessment-cycles/{cycleId}/sheets/{assignmentId}` | — | — | — | — |
| PUT | `/schools/{school}/assessment-cycles/{cycleId}/sheets/{assignmentId}/marks` | AssessmentService.Marks | required | required | — |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/sheets/{assignmentId}/submit` | — | required | required | — |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/sheets/{assignmentId}/return` | AssessmentService.Reason | required | required | — |
| PUT | `/schools/{school}/assessment-cycles/{cycleId}/remarks/{student}` | AssessmentService.Remark | required | required | — |
| GET | `/schools/{school}/assessment-cycles/{cycleId}/review` | — | — | — | — |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/publish` | AssessmentService.Publication | required | required | — |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/submit-review` | — | required | required | — |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/return-review` | AssessmentService.Reason | required | required | — |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/reopen` | AssessmentService.Reopen | required | required | — |
| GET | `/schools/{school}/students/{studentId}/reports` | — | — | — | — |
| GET | `/schools/{school}/reports/{reportId}` | — | — | — | — |
| GET | `/schools/{school}/reports/{reportId}/pdf` | — | — | — | — |
| GET | `/schools/{school}/assessment-cycles/{cycleId}/broadsheet.csv` | — | — | — | — |
| POST | `/schools/{school}/assignments` | AssignmentService.Input | required | — | — |
| GET | `/schools/{school}/assignments` | — | — | — | classId, termId, cursor, limit |
| GET | `/schools/{school}/assignments/{assignmentId}` | — | — | — | — |
| PUT | `/schools/{school}/assignments/{assignmentId}` | AssignmentService.Input | required | required | — |
| POST | `/schools/{school}/assignments/{assignmentId}/publish` | — | required | required | — |
| GET | `/schools/{school}/assignments/{assignmentId}/submissions/{studentId}` | — | — | — | — |
| POST | `/schools/{school}/assignments/{assignmentId}/changes` | AssignmentService.Change | required | required | — |
| GET | `/schools/{school}/assignments/{assignmentId}/submissions` | — | — | — | — |
| PUT | `/schools/{school}/assignments/{assignmentId}/my-submission` | AssignmentService.Draft | required | required | — |
| POST | `/schools/{school}/assignments/{assignmentId}/submit` | — | required | required | — |
| POST | `/schools/{school}/assignments/{assignmentId}/submissions/{studentId}/feedback` | AssignmentService.Feedback | required | required | — |
| GET | `/schools/{school}/assignments/{assignmentId}/submissions/{studentId}/history` | — | — | — | — |
| GET | `/schools/{school}/attendance/registers/{register}/correction-history` | — | — | — | studentId |
| POST | `/schools/{school}/attendance/registers/{register}/correction-alerts` | AttendanceWorkflowService.Notices | required | required | — |
| POST | `/schools/{school}/attendance/registers/{register}/correction-requests` | AttendanceWorkflowService.Correction | required | required | — |
| GET | `/schools/{school}/attendance/correction-requests` | — | — | — | classId, state, cursor, limit |
| GET | `/schools/{school}/attendance/correction-requests/{id}` | — | — | — | — |
| PUT | `/schools/{school}/attendance/correction-requests/{id}` | AttendanceWorkflowService.Correction | required | required | — |
| POST | `/schools/{school}/attendance/correction-requests/{id}/decision` | AttendanceWorkflowService.Decision | required | required | — |
| POST | `/schools/{school}/attendance/correction-requests/{id}/cancel` | AttendanceWorkflowService.Reason | required | required | — |
| GET | `/schools/{school}/attendance/correction-requests/{id}/history` | — | — | — | — |
| POST | `/schools/{school}/attendance/follow-up-cases` | AttendanceFollowupService.Open | required | — | — |
| GET | `/schools/{school}/attendance/follow-up-cases` | — | — | — | studentId, state, cursor, limit |
| GET | `/schools/{school}/attendance/follow-up-cases/{id}` | — | — | — | — |
| PATCH | `/schools/{school}/attendance/follow-up-cases/{id}` | AttendanceFollowupService.Change | required | required | — |
| GET | `/schools/{school}/schedule/day` | — | — | — | classId, on |
| POST | `/schools/{school}/exam-schedules` | ExamService.Schedule | required | — | — |
| GET | `/schools/{school}/exam-schedules` | — | — | — | termId |
| GET | `/schools/{school}/exam-schedules/{scheduleId}/review` | — | — | — | — |
| POST | `/schools/{school}/exam-schedules/{scheduleId}/events` | ExamService.Event | required | required | — |
| PUT | `/schools/{school}/exam-schedules/{scheduleId}/events/{eventId}` | ExamService.Event | required | required | — |
| POST | `/schools/{school}/exam-schedules/{scheduleId}/events/{eventId}/remove` | — | required | required | — |
| POST | `/schools/{school}/exam-schedules/{scheduleId}/publish` | — | required | required | — |
| GET | `/schools/{school}/examinations` | — | — | — | termId |
| POST | `/schools/{school}/students/{studentId}/external-results` | ExamService.External | required | — | — |
| GET | `/schools/{school}/students/{studentId}/external-results` | — | — | — | — |
| POST | `/schools/{school}/files` | FileService.Metadata | required | — | — |
| GET | `/schools/{school}/files` | — | — | — | — |
| GET | `/schools/{school}/files/{fileId}` | — | — | — | — |
| PUT | `/schools/{school}/files/{fileId}/content` | — | — | required | — |
| GET | `/schools/{school}/files/{fileId}/content` | — | — | — | — |
| GET | `/schools/{school}/materials` | — | — | — | classId, subjectId, termId, cursor, limit |
| POST | `/schools/{school}/materials` | MaterialService.Input | required | — | — |
| GET | `/schools/{school}/materials/{id}` | — | — | — | — |
| PUT | `/schools/{school}/materials/{id}` | MaterialService.Input | required | required | — |
| POST | `/schools/{school}/materials/{id}/publish` | — | required | required | — |
| POST | `/schools/{school}/materials/{id}/archive` | MaterialService.Reason | required | required | — |
| GET | `/schools/{school}/materials/{id}/history` | — | — | — | — |
| GET | `/schools/{school}/classes/{classId}/workspace` | — | — | — | on |
| PATCH | `/schools/{school}/classes/{classId}` | PlanningService.ClassEdit | required | required | — |
| POST | `/schools/{school}/curricula` | PlanningService.Curriculum | required | — | — |
| GET | `/schools/{school}/curricula` | — | — | — | sessionId |
| GET | `/schools/{school}/curricula/{curriculumId}` | — | — | — | — |
| POST | `/schools/{school}/curricula/{curriculumId}/publish` | — | required | required | — |
| PUT | `/schools/{school}/classes/{classId}/curriculum` | PlanningService.CurriculumApply | required | required | — |
| GET | `/schools/{school}/students/{studentId}/subject-choices` | — | — | — | sessionId |
| PUT | `/schools/{school}/students/{studentId}/subject-choices` | PlanningService.Choices | required | required | — |
| PUT | `/schools/{school}/staff/{staffId}/teaching-scope` | PlanningService.TeacherScope | required | required | — |
| GET | `/schools/{school}/calendar` | — | — | — | sessionId |
| GET | `/schools/{school}/calendar-plans` | — | — | — | sessionId |
| POST | `/schools/{school}/calendar-plans` | PlanningService.CalendarInput | required | — | — |
| POST | `/schools/{school}/calendar-plans/{id}/publish` | — | required | required | — |
| POST | `/schools/{school}/promotion-batches` | PromotionService.Preview | required | — | — |
| GET | `/schools/{school}/promotion-batches` | — | — | — | — |
| GET | `/schools/{school}/promotion-batches/{batchId}` | — | — | — | — |
| PUT | `/schools/{school}/promotion-batches/{batchId}/students/{studentId}` | PromotionService.Decision | required | required | — |
| POST | `/schools/{school}/promotion-batches/{batchId}/confirm` | — | required | required | — |
| GET | `/schools/{school}/promotion-batches/{batchId}/outcomes.csv` | — | — | — | — |
| GET | `/schools/{school}/students/{studentId}/academic-history` | — | — | — | — |
| GET | `/schools/{school}/provisioning/{type}/{subject}` | — | — | — | — |
| POST | `/schools/{school}/provisioning/{type}/{subject}/retry` | ProvisioningService.Retry | required | required | — |
| GET | `/schools/{school}/metadata` | — | — | — | — |
| PUT | `/schools/{school}/metadata` | SchoolController.MetadataInput | required | required | — |
| POST | `/schools/{school}/academic-sessions` | SchoolService.SessionInput | required | — | — |
| GET | `/schools/{school}/academic-sessions` | — | — | — | — |
| POST | `/schools/{school}/classes` | SchoolService.ClassInput | required | — | — |
| GET | `/schools/{school}/classes` | — | — | — | sessionId |
| PUT | `/schools/{school}/classes/{classId}/attendance-teacher` | SchoolController.AttendanceTeacher | required | required | — |
| POST | `/schools/{school}/subjects` | SchoolService.SubjectInput | required | — | — |
| GET | `/schools/{school}/subjects` | — | — | — | — |
| POST | `/schools/{school}/teachers` | SchoolService.TeacherInput | required | — | — |
| GET | `/schools/{school}/teachers` | — | — | — | cursor, search, level, subjectId, classId, active, limit |
| POST | `/schools/{school}/students` | SchoolService.EnrolInput | required | — | — |
| GET | `/schools/{school}/students` | — | — | — | classId, cursor, search, limit |
| POST | `/schools/{school}/students/{studentId}/guardians/{guardianId}/revoke` | SchoolController.Reason | required | — | — |
| GET | `/schools/{school}/audit` | — | — | — | cursor, limit |
| GET | `/schools/{school}/guardians` | — | — | — | cursor, search, childId, activeLink, limit |
| GET | `/schools/{school}/documents` | — | — | — | studentId, staffId, kind, cursor, limit |
| POST | `/schools/{school}/documents` | SchoolDocumentService.Input | required | — | — |
| GET | `/schools/{school}/documents/{id}` | — | — | — | — |
| PUT | `/schools/{school}/documents/{id}` | SchoolDocumentService.Input | required | required | — |
| POST | `/schools/{school}/documents/{id}/publish` | — | required | required | — |
| POST | `/schools/{school}/documents/{id}/archive` | SchoolDocumentService.Reason | required | required | — |
| GET | `/schools/{school}/documents/{id}/history` | — | — | — | — |
| PATCH | `/schools/{school}/academic-sessions/{id}` | SchoolSetupService.Dates | required | required | — |
| PATCH | `/schools/{school}/terms/{id}` | SchoolSetupService.Dates | required | required | — |
| PUT | `/schools/{school}/active-academic-session` | SchoolSetupService.ActiveSession | required | required | — |
| PATCH | `/schools/{school}/subjects/{id}` | SchoolSetupService.Subject | required | required | — |
| GET | `/schools/{school}/rooms` | — | — | — | active, cursor, limit |
| POST | `/schools/{school}/rooms` | SchoolSetupService.Room | required | — | — |
| PATCH | `/schools/{school}/rooms/{id}` | SchoolSetupService.Room | required | required | — |
| GET | `/schools/{school}/settings` | — | — | — | — |
| PUT | `/schools/{school}/settings` | SchoolWorkspaceService.Settings | required | required | — |
| POST | `/schools/{school}/metadata-versions` | SchoolWorkspaceService.Metadata | required | required | — |
| GET | `/schools/{school}/guardians/{guardianId}` | — | — | — | — |
| PATCH | `/schools/{school}/guardians/{guardianId}` | SchoolWorkspaceService.GuardianEdit | required | required | — |
| GET | `/schools/{school}/terms/{termId}/activities` | — | — | — | — |
| PUT | `/schools/{school}/terms/{termId}/activities` | SchoolWorkspaceService.Activity | required | required | — |
| GET | `/schools/{school}/my-classes` | — | — | — | sessionId |
| GET | `/schools/{school}/dashboard` | — | — | — | — |
| GET | `/schools/{school}/attendance/follow-ups` | — | — | — | on |
| GET | `/schools/{school}/students/export.csv` | — | — | — | classId |
| GET | `/schools/{school}/teachers/export.csv` | — | — | — | — |
| POST | `/schools/{school}/assignments/{assignment}/submissions/{student}/physical-receipt` | SubmissionWorkService.Physical | required | required | — |
| GET | `/schools/{school}/assignments/{assignment}/submissions/{student}/review-draft` | — | — | — | — |
| PUT | `/schools/{school}/assignments/{assignment}/submissions/{student}/review-draft` | AssignmentService.Feedback | required | required | — |
| POST | `/schools/{school}/assignments/{assignment}/submissions/{student}/review-draft/release` | — | required | required | — |
| GET | `/me/notifications` | — | — | — | schoolId, category, unread, cursor, limit |
| PUT | `/me/notifications/{notificationId}/read` | — | — | — | — |
| GET | `/me/notifications/{notificationId}/delivery` | — | — | — | — |
| POST | `/schools/{school}/notices` | CommunicationWorkService.Notice | required | — | — |
| GET | `/schools/{school}/notices` | — | — | — | cursor, limit |
| POST | `/schools/{school}/complaints` | CommunicationWorkService.Complaint | required | — | — |
| GET | `/schools/{school}/complaints` | — | — | — | cursor, limit |
| GET | `/schools/{school}/complaints/{caseId}` | — | — | — | — |
| POST | `/schools/{school}/complaints/{caseId}/replies` | CommunicationWorkService.Reply | required | required | — |
| POST | `/schools/{school}/complaints/{caseId}/close` | CommunicationController.Close | required | required | — |
| GET | `/schools/{school}/notices/{id}` | — | — | — | — |
| PUT | `/schools/{school}/notices/{id}` | CommunicationWorkService.Notice | required | required | — |
| POST | `/schools/{school}/notices/{id}/publish` | — | required | required | — |
| POST | `/schools/{school}/notices/{id}/archive` | CommunicationWorkController.Reason | required | required | — |
| GET | `/schools/{school}/threads/contacts` | — | — | — | studentId |
| POST | `/schools/{school}/threads` | CommunicationWorkService.ThreadInput | required | — | — |
| GET | `/schools/{school}/threads` | — | — | — | cursor, limit |
| GET | `/schools/{school}/threads/{threadId}` | — | — | — | — |
| POST | `/schools/{school}/threads/{threadId}/messages` | CommunicationWorkService.Message | required | required | — |
| POST | `/schools/{school}/threads/{threadId}/close` | CommunicationWorkController.Reason | required | required | — |
| PUT | `/schools/{school}/threads/{threadId}/read` | — | — | — | — |
| POST | `/schools/{school}/complaints/{caseId}/status` | CommunicationWorkService.StateChange | required | required | — |
| PUT | `/schools/{school}/complaints/{caseId}/owner` | CommunicationWorkService.Owner | required | required | — |
| GET | `/schools/{school}/complaints/{caseId}/history` | — | — | — | — |
| POST | `/schools/{school}/fee-plans` | FeeAdministrationService.Plan | required | — | — |
| GET | `/schools/{school}/fee-plans` | — | — | — | termId |
| PUT | `/schools/{school}/fee-plans/{planId}` | FeeAdministrationService.Plan | required | required | — |
| GET | `/schools/{school}/fee-plans/{planId}` | — | — | — | — |
| POST | `/schools/{school}/fee-plans/{planId}/publish` | — | required | required | — |
| POST | `/schools/{school}/fee-plans/{planId}/batches` | FeeAdministrationService.Preview | required | — | — |
| GET | `/schools/{school}/fee-batches/{batchId}` | — | — | — | — |
| POST | `/schools/{school}/fee-batches/{batchId}/confirm` | — | required | required | — |
| POST | `/schools/{school}/invoices/{invoiceId}/adjustments` | FeeAdministrationService.Adjustment | required | — | — |
| GET | `/schools/{school}/fee-adjustments` | — | — | — | invoiceId |
| GET | `/schools/{school}/fee-adjustments/{adjustmentId}` | — | — | — | — |
| POST | `/schools/{school}/fee-adjustments/{adjustmentId}/decision` | FeeAdministrationService.Decision | required | required | — |
| POST | `/schools/{school}/fee-adjustments/{adjustmentId}/confirm-refund` | FeeAdministrationService.RefundConfirmation | required | required | — |
| GET | `/schools/{school}/payments` | — | — | — | studentId, cursor, limit |
| GET | `/schools/{school}/invoices/{invoiceId}/pdf` | — | — | — | — |
| GET | `/schools/{school}/payments/{paymentId}/receipt` | — | — | — | — |
| GET | `/schools/{school}/invoices/export.csv` | — | — | — | — |
| POST | `/schools/{school}/invoices` | FinanceService.InvoiceInput | required | — | — |
| GET | `/schools/{school}/invoices` | — | — | — | studentId, cursor, limit |
| GET | `/schools/{school}/invoices/{invoiceId}` | — | — | — | — |
| POST | `/schools/{school}/invoices/{invoiceId}/manual-payments` | FinanceService.Manual | required | required | — |
| POST | `/schools/{school}/invoices/payment-settings` | FinanceController.Settlement | required | — | — |
| GET | `/schools/{school}/invoices/payment-settings` | — | — | — | — |
| PUT | `/schools/{school}/invoices/payment-settings` | PaymentSettingsService.Update | required | required | — |
| POST | `/schools/{school}/invoices/{invoiceId}/checkout` | FinanceService.Checkout | required | — | — |
| GET | `/schools/{school}/payment-intents/{intentId}` | — | — | — | — |
| POST | `/schools/{school}/payment-intents/{intentId}/verify` | — | — | — | — |
| GET | `/schools/{school}/reconciliation/exceptions` | — | — | — | — |
| POST | `/webhooks/paystack` | — | — | — | — |
| GET | `/schools/{school}/invoices/fee-status` | — | — | — | studentId, termId, cursor, limit |
| GET | `/schools/{school}/payment-intents` | — | — | — | studentId, invoiceId, state, reference, from, to, cursor, limit |
| GET | `/schools/{school}/invoices/payment-settings/history` | — | — | — | beforeVersion, limit |
| POST | `/schools/{school}/invoices/opening-balances` | OpeningBalanceService.Preview | required | — | — |
| GET | `/schools/{school}/invoices/opening-balances/{batchId}` | — | — | — | — |
| POST | `/schools/{school}/invoices/opening-balances/{batchId}/confirm` | OpeningBalanceService.Confirm | — | — | — |
| POST | `/schools/{school}/reconciliation/statements` | ReconciliationService.Statement | required | — | — |
| POST | `/schools/{school}/reconciliation/paystack-sync` | ReconciliationService.Period | required | — | — |
| GET | `/schools/{school}/reconciliation/settlements` | — | — | — | cursor, limit |
| GET | `/schools/{school}/reconciliation/settlements/{settlementId}` | — | — | — | — |
| POST | `/schools/{school}/reconciliation/settlements/{settlementId}/rows/{rowId}/resolution` | ReconciliationService.Resolution | — | — | — |
| GET | `/schools/{school}/reconciliation/overview` | — | — | — | — |
| POST | `/auth/login` | IdentityController.Login | — | — | — |
| POST | `/auth/refresh` | IdentityController.Token | — | — | — |
| POST | `/auth/logout` | IdentityController.Token | — | — | — |
| POST | `/auth/activation/review` | IdentityController.Activation | — | — | — |
| POST | `/auth/recovery/email` | IdentityController.Identifier | — | — | — |
| POST | `/auth/recovery/email/review` | IdentityController.Proof | — | — | — |
| POST | `/auth/recovery/code/review` | IdentityController.Recovery | — | — | — |
| POST | `/auth/password/setup` | IdentityController.Setup | — | — | — |
| POST | `/me/password/change` | IdentityController.ChangePassword | — | — | — |
| GET | `/me` | — | — | — | — |
| PATCH | `/me` | IdentityController.Phone | — | required | — |
| GET | `/me/contexts` | — | — | — | — |
| PUT | `/me/context` | IdentityController.Preference | — | — | — |
| POST | `/me/reauthenticate` | IdentityController.Password | — | — | — |
| POST | `/me/recovery-code/rotate` | IdentityController.Recent | — | — | — |
| POST | `/me/sessions/revoke-others` | IdentityController.Recent | required | — | — |
| GET | `/me/sessions` | — | — | — | cursor, limit |
| GET | `/me/notification-preferences` | — | — | — | — |
| PATCH | `/me/notification-preferences` | IdentityController.Preferences | — | — | — |
| POST | `/me/email-change-requests` | IdentityController.Email | required | — | — |
| POST | `/me/email-change-requests/resend` | IdentityController.Resend | required | — | — |
| POST | `/auth/email-verifications/confirm` | IdentityController.Proof | — | — | — |
| POST | `/me/push-devices` | IdentityController.Push | required | — | — |
| DELETE | `/me/push-devices/{deviceId}` | — | — | — | — |
| POST | `/schools/{school}/onboarding/{type}/{subjectId}/activation-slips` | — | required | — | — |
| GET | `/public/access-help` | — | — | — | — |
| GET | `/schools/{school}/onboarding/{type}/{subject}/invitations` | — | — | — | cursor, limit |
| POST | `/schools/{school}/onboarding/{type}/{subject}/invitations/{id}/revoke` | InvitationService.Revoke | required | required | — |
| GET | `/schools/{school}/activity/{domain}` | — | — | — | from, to, cursor, limit |
| GET | `/schools/{school}/activity/{domain}/export.csv` | — | — | — | from, to |
| GET | `/me/security-history` | — | — | — | cursor, limit |

## Request fields

These are declared property names and Java types. Required/conditional values are validated by the services. Lists are JSON arrays; maps are objects; UUIDs are string IDs; dates use ISO format. Money is integer kobo and scores allow one decimal place. See API.md for business rules.

### AcademicController.AttendanceTeacherAlert

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `reviewReason` | `String` |

### AcademicController.CalendarInput

| Field | Type |
| --- | --- |
| `onDate` | `LocalDate` |
| `title` | `String` |
| `teachingDay` | `boolean` |
| `reason` | `String` |

### AcademicImportService.Confirmation

| Field | Type |
| --- | --- |
| `relationshipsVerified` | `boolean` |
| `duplicatesReviewed` | `boolean` |
| `reason` | `String` |

### AcademicImportService.Correction

| Field | Type |
| --- | --- |
| `fields` | `Map<String,String>` |
| `reason` | `String` |

### AcademicImportService.Preview

| Field | Type |
| --- | --- |
| `kind` | `String` |
| `sessionId` | `UUID` |
| `csv` | `String` |
| `columnMapping` | `Map<String,String>` |

### AdministrationService.AccessInput

| Field | Type |
| --- | --- |
| `role` | `String` |
| `active` | `boolean` |
| `reason` | `String` |

### AdministrationService.AssignmentEdit

| Field | Type |
| --- | --- |
| `teacherId` | `UUID` |
| `weeklyPeriods` | `int` |
| `reason` | `String` |

### AdministrationService.GuardianLink

| Field | Type |
| --- | --- |
| `guardianId` | `UUID` |
| `guardian` | `SchoolService.GuardianInput` |
| `relationship` | `String` |
| `learningAccess` | `boolean` |
| `payerAccess` | `boolean` |
| `primaryContact` | `boolean` |
| `reason` | `String` |

### AdministrationService.StaffEdit

| Field | Type |
| --- | --- |
| `fullName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `staffNumber` | `String` |
| `joinedOn` | `LocalDate` |
| `reason` | `String` |

### AdministrationService.StaffInput

| Field | Type |
| --- | --- |
| `fullName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `staffNumber` | `String` |
| `role` | `String` |
| `joinedOn` | `LocalDate` |
| `existingLoginId` | `String` |
| `relationshipVerified` | `boolean` |

### AdministrationService.StudentEdit

| Field | Type |
| --- | --- |
| `fullName` | `String` |
| `dateOfBirth` | `LocalDate` |
| `sex` | `String` |
| `fields` | `Map<String,Object>` |
| `metadataVersion` | `long` |
| `reason` | `String` |

### AdministrationService.Transfer

| Field | Type |
| --- | --- |
| `classId` | `UUID` |
| `effectiveOn` | `LocalDate` |
| `department` | `String` |
| `reason` | `String` |

### AdministrationService.Withdrawal

| Field | Type |
| --- | --- |
| `effectiveOn` | `LocalDate` |
| `reason` | `String` |

### AssessmentService.CycleInput

| Field | Type |
| --- | --- |
| `termId` | `UUID` |
| `classId` | `UUID` |
| `opensAt` | `Instant` |
| `closesAt` | `Instant` |

### AssessmentService.Marks

| Field | Type |
| --- | --- |
| `rows` | `List<Score>` |

### AssessmentService.Publication

| Field | Type |
| --- | --- |
| `principalRemark` | `String` |
| `nextResumptionOn` | `LocalDate` |

### AssessmentService.Reason

| Field | Type |
| --- | --- |
| `reason` | `String` |

### AssessmentService.Remark

| Field | Type |
| --- | --- |
| `remark` | `String` |
| `conduct` | `int` |
| `punctuality` | `int` |
| `cooperation` | `int` |
| `handwriting` | `int` |
| `practicalSkills` | `int` |

### AssessmentService.Reopen

| Field | Type |
| --- | --- |
| `assignmentId` | `UUID` |
| `reason` | `String` |
| `closesAt` | `Instant` |

### AssessmentService.RosterSync

| Field | Type |
| --- | --- |
| `reason` | `String` |

### AssessmentService.Score

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `ca1` | `BigDecimal` |
| `ca2` | `BigDecimal` |
| `exam` | `BigDecimal` |
| `examState` | `String` |
| `note` | `String` |

### AssessmentService.Window

| Field | Type |
| --- | --- |
| `opensAt` | `Instant` |
| `closesAt` | `Instant` |
| `reason` | `String` |

### AssignmentService.Change

| Field | Type |
| --- | --- |
| `action` | `String` |
| `dueAt` | `Instant` |
| `reason` | `String` |

### AssignmentService.Draft

| Field | Type |
| --- | --- |
| `responseText` | `String` |
| `fileIds` | `List<UUID>` |

### AssignmentService.Feedback

| Field | Type |
| --- | --- |
| `action` | `String` |
| `score` | `BigDecimal` |
| `feedback` | `String` |
| `reason` | `String` |
| `extraDeadline` | `Instant` |

### AssignmentService.Input

| Field | Type |
| --- | --- |
| `teachingAssignmentId` | `UUID` |
| `termId` | `UUID` |
| `title` | `String` |
| `instructions` | `String` |
| `dueAt` | `Instant` |
| `maximumScore` | `BigDecimal` |
| `fileIds` | `List<UUID>` |
| `submissionMode` | `String` |

### AttendanceFollowupService.Change

| Field | Type |
| --- | --- |
| `state` | `String` |
| `ownerId` | `UUID` |
| `note` | `String` |
| `reason` | `String` |

### AttendanceFollowupService.Open

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `onDate` | `LocalDate` |
| `ownerId` | `UUID` |
| `note` | `String` |
| `reason` | `String` |

### AttendanceService.Mark

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `status` | `String` |
| `reason` | `String` |

### AttendanceService.Marks

| Field | Type |
| --- | --- |
| `rows` | `List<Mark>` |

### AttendanceService.RegisterInput

| Field | Type |
| --- | --- |
| `classId` | `UUID` |
| `onDate` | `LocalDate` |

### AttendanceWorkflowService.Correction

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `status` | `String` |
| `reason` | `String` |
| `registerVersion` | `Long` |

### AttendanceWorkflowService.Decision

| Field | Type |
| --- | --- |
| `action` | `String` |
| `reason` | `String` |

### AttendanceWorkflowService.Notices

| Field | Type |
| --- | --- |
| `studentIds` | `List<UUID>` |
| `reason` | `String` |

### AttendanceWorkflowService.Reason

| Field | Type |
| --- | --- |
| `reason` | `String` |

### CommunicationController.Close

| Field | Type |
| --- | --- |
| `reason` | `String` |

### CommunicationWorkController.Reason

| Field | Type |
| --- | --- |
| `reason` | `String` |

### CommunicationWorkService.Complaint

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `guardianId` | `UUID` |
| `category` | `String` |
| `subject` | `String` |
| `body` | `String` |
| `intakeChannel` | `String` |
| `fileIds` | `List<UUID>` |

### CommunicationWorkService.Message

| Field | Type |
| --- | --- |
| `body` | `String` |
| `fileIds` | `List<UUID>` |

### CommunicationWorkService.Notice

| Field | Type |
| --- | --- |
| `title` | `String` |
| `body` | `String` |
| `scope` | `String` |
| `classId` | `UUID` |
| `department` | `String` |
| `fileIds` | `List<UUID>` |

### CommunicationWorkService.Owner

| Field | Type |
| --- | --- |
| `staffId` | `UUID` |
| `reason` | `String` |

### CommunicationWorkService.Reply

| Field | Type |
| --- | --- |
| `body` | `String` |
| `internal` | `boolean` |
| `fileIds` | `List<UUID>` |

### CommunicationWorkService.StateChange

| Field | Type |
| --- | --- |
| `action` | `String` |
| `reason` | `String` |

### CommunicationWorkService.ThreadInput

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `guardianId` | `UUID` |
| `target` | `String` |
| `subject` | `String` |
| `body` | `String` |
| `fileIds` | `List<UUID>` |

### ExamService.Event

| Field | Type |
| --- | --- |
| `classId` | `UUID` |
| `subjectId` | `UUID` |
| `department` | `String` |
| `invigilatorId` | `UUID` |
| `onDate` | `LocalDate` |
| `start` | `String` |
| `durationMinutes` | `int` |
| `room` | `String` |
| `exceptionReason` | `String` |

### ExamService.External

| Field | Type |
| --- | --- |
| `board` | `String` |
| `examYear` | `int` |
| `candidateReference` | `String` |
| `issuedResult` | `Map<String,Object>` |
| `reason` | `String` |

### ExamService.Schedule

| Field | Type |
| --- | --- |
| `termId` | `UUID` |

### FeeAdministrationService.Adjustment

| Field | Type |
| --- | --- |
| `kind` | `String` |
| `amountMinor` | `long` |
| `paymentId` | `UUID` |
| `reason` | `String` |
| `evidenceReference` | `String` |

### FeeAdministrationService.Decision

| Field | Type |
| --- | --- |
| `approve` | `boolean` |
| `reason` | `String` |

### FeeAdministrationService.Plan

| Field | Type |
| --- | --- |
| `termId` | `UUID` |
| `name` | `String` |
| `classIds` | `List<UUID>` |
| `lines` | `List<FinanceService.Line>` |
| `dueOn` | `LocalDate` |
| `partialAllowed` | `boolean` |

### FeeAdministrationService.Preview

| Field | Type |
| --- | --- |
| `classIds` | `List<UUID>` |

### FeeAdministrationService.RefundConfirmation

| Field | Type |
| --- | --- |
| `reference` | `String` |
| `verificationReference` | `String` |

### FileService.Metadata

| Field | Type |
| --- | --- |
| `purpose` | `String` |
| `studentId` | `UUID` |
| `classId` | `UUID` |
| `filename` | `String` |
| `mediaType` | `String` |
| `size` | `int` |
| `sha256` | `String` |
| `familyVisible` | `boolean` |
| `staffId` | `UUID` |

### FinanceController.Settlement

| Field | Type |
| --- | --- |
| `subaccount` | `String` |

### FinanceService.Checkout

| Field | Type |
| --- | --- |
| `amountMinor` | `long` |
| `email` | `String` |

### FinanceService.InvoiceInput

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `termId` | `UUID` |
| `description` | `String` |
| `lines` | `List<Line>` |
| `dueOn` | `LocalDate` |
| `partialAllowed` | `boolean` |

### FinanceService.Line

| Field | Type |
| --- | --- |
| `description` | `String` |
| `amountMinor` | `long` |
| `quantity` | `int` |

### FinanceService.Manual

| Field | Type |
| --- | --- |
| `amountMinor` | `long` |
| `method` | `String` |
| `reference` | `String` |
| `verificationReference` | `String` |
| `reason` | `String` |

### IdentityController.Activation

| Field | Type |
| --- | --- |
| `loginId` | `String` |
| `activationCode` | `String` |
| `invitationToken` | `String` |

### IdentityController.ChangePassword

| Field | Type |
| --- | --- |
| `currentPassword` | `String` |
| `newPassword` | `String` |

### IdentityController.Email

| Field | Type |
| --- | --- |
| `email` | `String` |
| `recentAuthToken` | `String` |

### IdentityController.Identifier

| Field | Type |
| --- | --- |
| `identifier` | `String` |

### IdentityController.Login

| Field | Type |
| --- | --- |
| `identifier` | `String` |
| `password` | `String` |
| `installationId` | `String` |
| `deviceName` | `String` |

### IdentityController.Password

| Field | Type |
| --- | --- |
| `currentPassword` | `String` |

### IdentityController.Phone

| Field | Type |
| --- | --- |
| `phone` | `String` |

### IdentityController.Preference

| Field | Type |
| --- | --- |
| `schoolId` | `UUID` |
| `role` | `String` |
| `childId` | `UUID` |

### IdentityController.Preferences

| Field | Type |
| --- | --- |
| `pushEnabled` | `boolean` |
| `emailOptionalEnabled` | `boolean` |
| `categories` | `List<String>` |

### IdentityController.Proof

| Field | Type |
| --- | --- |
| `token` | `String` |

### IdentityController.Push

| Field | Type |
| --- | --- |
| `installationId` | `String` |
| `platform` | `String` |
| `pushToken` | `String` |

### IdentityController.Recent

| Field | Type |
| --- | --- |
| `recentAuthToken` | `String` |

### IdentityController.Recovery

| Field | Type |
| --- | --- |
| `loginId` | `String` |
| `recoveryCode` | `String` |

### IdentityController.Resend

| Field | Type |
| --- | --- |
| `requestId` | `UUID` |

### IdentityController.Setup

| Field | Type |
| --- | --- |
| `flowToken` | `String` |
| `newPassword` | `String` |

### IdentityController.Token

| Field | Type |
| --- | --- |
| `refreshToken` | `String` |

### InvitationService.Revoke

| Field | Type |
| --- | --- |
| `reason` | `String` |

### MaterialService.Input

| Field | Type |
| --- | --- |
| `teachingAssignmentId` | `UUID` |
| `termId` | `UUID` |
| `title` | `String` |
| `body` | `String` |
| `fileIds` | `List<UUID>` |

### MaterialService.Reason

| Field | Type |
| --- | --- |
| `reason` | `String` |

### OpeningBalanceService.Confirm

| Field | Type |
| --- | --- |
| `reason` | `String` |

### OpeningBalanceService.Preview

| Field | Type |
| --- | --- |
| `termId` | `UUID` |
| `csv` | `String` |
| `columnMapping` | `Map<String,String>` |

### PaymentSettingsService.Update

| Field | Type |
| --- | --- |
| `subaccount` | `String` |
| `checkoutEnabled` | `Boolean` |
| `reason` | `String` |

### PlanningService.Availability

| Field | Type |
| --- | --- |
| `weekday` | `int` |
| `startMinute` | `int` |
| `endMinute` | `int` |
| `reason` | `String` |

### PlanningService.CalendarChange

| Field | Type |
| --- | --- |
| `onDate` | `LocalDate` |
| `title` | `String` |
| `teachingDay` | `boolean` |
| `reason` | `String` |

### PlanningService.CalendarInput

| Field | Type |
| --- | --- |
| `sessionId` | `UUID` |
| `changes` | `List<CalendarChange>` |

### PlanningService.Choices

| Field | Type |
| --- | --- |
| `sessionId` | `UUID` |
| `subjectIds` | `List<UUID>` |
| `reason` | `String` |

### PlanningService.ClassEdit

| Field | Type |
| --- | --- |
| `capacity` | `Integer` |
| `room` | `String` |
| `reason` | `String` |

### PlanningService.Curriculum

| Field | Type |
| --- | --- |
| `sessionId` | `UUID` |
| `level` | `String` |
| `department` | `String` |
| `name` | `String` |
| `subjects` | `List<SubjectRule>` |

### PlanningService.CurriculumApply

| Field | Type |
| --- | --- |
| `curriculumId` | `UUID` |
| `teachers` | `Map<UUID,UUID>` |
| `reason` | `String` |

### PlanningService.SubjectRule

| Field | Type |
| --- | --- |
| `subjectId` | `UUID` |
| `required` | `boolean` |
| `choiceGroup` | `String` |
| `weeklyPeriods` | `int` |

### PlanningService.TeacherScope

| Field | Type |
| --- | --- |
| `eligibleLevels` | `List<String>` |
| `subjectIds` | `List<UUID>` |
| `unavailable` | `List<Availability>` |
| `reason` | `String` |

### PromotionService.Decision

| Field | Type |
| --- | --- |
| `decision` | `String` |
| `targetClassId` | `UUID` |
| `department` | `String` |
| `reason` | `String` |
| `evidenceReference` | `String` |

### PromotionService.Preview

| Field | Type |
| --- | --- |
| `sourceSessionId` | `UUID` |
| `targetSessionId` | `UUID` |
| `sourceClassId` | `UUID` |

### ProvisioningService.Retry

| Field | Type |
| --- | --- |
| `eventId` | `UUID` |
| `reason` | `String` |

### ReconciliationService.Period

| Field | Type |
| --- | --- |
| `from` | `LocalDate` |
| `to` | `LocalDate` |
| `subaccount` | `String` |

### ReconciliationService.Resolution

| Field | Type |
| --- | --- |
| `resolution` | `String` |
| `reason` | `String` |

### ReconciliationService.Row

| Field | Type |
| --- | --- |
| `reference` | `String` |
| `amountMinor` | `long` |
| `feeMinor` | `long` |
| `netMinor` | `long` |

### ReconciliationService.Statement

| Field | Type |
| --- | --- |
| `reference` | `String` |
| `rows` | `List<Row>` |

### SchoolController.AttendanceTeacher

| Field | Type |
| --- | --- |
| `teacherId` | `UUID` |

### SchoolController.FieldDefinition

| Field | Type |
| --- | --- |
| `key` | `String` |
| `required` | `boolean` |
| `maxLength` | `int` |

### SchoolController.MetadataInput

| Field | Type |
| --- | --- |
| `studentFields` | `List<FieldDefinition>` |
| `wednesdayPtEnabled` | `Boolean` |

### SchoolController.Reason

| Field | Type |
| --- | --- |
| `reason` | `String` |

### SchoolDocumentService.Input

| Field | Type |
| --- | --- |
| `studentId` | `UUID` |
| `staffId` | `UUID` |
| `kind` | `String` |
| `title` | `String` |
| `fileId` | `UUID` |
| `issuedOn` | `LocalDate` |
| `sessionName` | `String` |
| `termNumber` | `Integer` |
| `issuer` | `String` |
| `verificationReference` | `String` |
| `familyVisible` | `boolean` |
| `staffVisible` | `boolean` |
| `reason` | `String` |

### SchoolDocumentService.Reason

| Field | Type |
| --- | --- |
| `reason` | `String` |

### SchoolService.AssignmentInput

| Field | Type |
| --- | --- |
| `classId` | `UUID` |
| `subjectId` | `UUID` |
| `department` | `String` |
| `weeklyPeriods` | `int` |

### SchoolService.ClassInput

| Field | Type |
| --- | --- |
| `sessionId` | `UUID` |
| `level` | `String` |
| `arm` | `String` |
| `attendanceTeacherId` | `UUID` |

### SchoolService.EnrolInput

| Field | Type |
| --- | --- |
| `admissionNumber` | `String` |
| `fullName` | `String` |
| `dateOfBirth` | `LocalDate` |
| `sex` | `String` |
| `classId` | `UUID` |
| `startsOn` | `LocalDate` |
| `department` | `String` |
| `metadataVersion` | `long` |
| `fields` | `Map<String,Object>` |
| `guardian` | `GuardianInput` |
| `admissionType` | `String` |
| `legacyReference` | `String` |
| `subjectIds` | `List<UUID>` |

### SchoolService.GuardianInput

| Field | Type |
| --- | --- |
| `existingGuardianId` | `UUID` |
| `fullName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `existingLoginId` | `String` |
| `relationshipVerified` | `boolean` |
| `verificationReference` | `String` |
| `relationship` | `String` |

### SchoolService.SessionInput

| Field | Type |
| --- | --- |
| `name` | `String` |
| `startsOn` | `LocalDate` |
| `endsOn` | `LocalDate` |

### SchoolService.SubjectInput

| Field | Type |
| --- | --- |
| `code` | `String` |
| `name` | `String` |
| `core` | `boolean` |

### SchoolService.TeacherInput

| Field | Type |
| --- | --- |
| `fullName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `existingLoginId` | `String` |
| `relationshipVerified` | `boolean` |
| `eligibleLevels` | `List<String>` |
| `subjectIds` | `List<UUID>` |
| `assignments` | `List<AssignmentInput>` |

### SchoolService.TermInput

| Field | Type |
| --- | --- |
| `sessionId` | `UUID` |
| `number` | `int` |
| `startsOn` | `LocalDate` |
| `endsOn` | `LocalDate` |

### SchoolSetupService.ActiveSession

| Field | Type |
| --- | --- |
| `sessionId` | `UUID` |
| `reason` | `String` |

### SchoolSetupService.Dates

| Field | Type |
| --- | --- |
| `name` | `String` |
| `startsOn` | `LocalDate` |
| `endsOn` | `LocalDate` |
| `reason` | `String` |

### SchoolSetupService.Room

| Field | Type |
| --- | --- |
| `code` | `String` |
| `name` | `String` |
| `capacity` | `Integer` |
| `active` | `boolean` |
| `reason` | `String` |

### SchoolSetupService.Subject

| Field | Type |
| --- | --- |
| `name` | `String` |
| `active` | `boolean` |
| `reason` | `String` |

### SchoolWorkspaceService.Activity

| Field | Type |
| --- | --- |
| `activity` | `String` |
| `teacherIds` | `List<UUID>` |
| `reason` | `String` |

### SchoolWorkspaceService.FormField

| Field | Type |
| --- | --- |
| `key` | `String` |
| `label` | `String` |
| `maxLength` | `int` |
| `required` | `boolean` |

### SchoolWorkspaceService.GuardianEdit

| Field | Type |
| --- | --- |
| `fullName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `reason` | `String` |

### SchoolWorkspaceService.Metadata

| Field | Type |
| --- | --- |
| `studentFields` | `List<FormField>` |
| `reason` | `String` |

### SchoolWorkspaceService.Settings

| Field | Type |
| --- | --- |
| `phone` | `String` |
| `email` | `String` |
| `address` | `String` |
| `wednesdayPtEnabled` | `boolean` |
| `reason` | `String` |
| `logoFileId` | `UUID` |
| `clearLogo` | `boolean` |

### SchoolWorkspaceService.Teaching

| Field | Type |
| --- | --- |
| `teacherId` | `UUID` |
| `assignment` | `SchoolService.AssignmentInput` |
| `reason` | `String` |

### SubmissionWorkService.Physical

| Field | Type |
| --- | --- |
| `receivedOn` | `LocalDate` |
| `note` | `String` |
| `reason` | `String` |
| `fileIds` | `List<UUID>` |

### TimetableService.LessonInput

| Field | Type |
| --- | --- |
| `assignmentId` | `UUID` |
| `weekday` | `int` |
| `start` | `String` |
| `durationMinutes` | `int` |
| `room` | `String` |

### TimetableService.PlanInput

| Field | Type |
| --- | --- |
| `termId` | `UUID` |
| `effectiveFrom` | `LocalDate` |
| `sourcePlanId` | `UUID` |

