# Implemented endpoint and request catalogue

Source checkpoint: `3f7bbd716932b3a550ab2877156943130a2b1148`. This file lists 220 public gateway operations and 104 request record types. Start with [API.md](API.md) for shared rules, permissions and workflows. [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) lists remaining work.

Paths are relative to `http://localhost:8080/api/v1`. Path placeholder names are standardised here; replace them with the returned UUIDs. **K** = stable UUID `Idempotency-Key`. **V** = quoted `If-Match` version. Protected routes also need the account's bearer token and current school/role/child context. A header or hidden button cannot grant access.

[OpenAPI request catalogue](openapi.json) · [Filterable endpoint CSV](ENDPOINT_CATALOGUE.csv)

The OpenAPI file describes request fields and transport. Required/conditional business rules remain in the service. Responses that the implementation builds dynamically are not assigned invented strict schemas. For example, fee-plan details and adjustment details currently retain some snake_case database column names; the frontend Model should map those responses explicitly.

## Shared identity and sign-in

| Method | Path | Input record | Query | Headers |
| --- | --- | --- | --- | --- |
| POST | `/auth/login` | [IdentityController.Login](#identitycontrollerlogin) | — | Public |
| POST | `/auth/refresh` | [IdentityController.Token](#identitycontrollertoken) | — | Public |
| POST | `/auth/logout` | [IdentityController.Token](#identitycontrollertoken) | — | Public |
| POST | `/auth/activation/review` | [IdentityController.Activation](#identitycontrolleractivation) | — | Public |
| POST | `/auth/recovery/email` | [IdentityController.Identifier](#identitycontrolleridentifier) | — | Public |
| POST | `/auth/recovery/email/review` | [IdentityController.Proof](#identitycontrollerproof) | — | Public |
| POST | `/auth/recovery/code/review` | [IdentityController.Recovery](#identitycontrollerrecovery) | — | Public |
| POST | `/auth/password/setup` | [IdentityController.Setup](#identitycontrollersetup) | — | Public |
| POST | `/me/password/change` | [IdentityController.ChangePassword](#identitycontrollerchangepassword) | — | Bearer |
| GET | `/me` | — | — | Bearer |
| PATCH | `/me` | [IdentityController.Phone](#identitycontrollerphone) | — | Bearer, V |
| GET | `/me/contexts` | — | — | Bearer |
| PUT | `/me/context` | [IdentityController.Preference](#identitycontrollerpreference) | — | Bearer |
| POST | `/me/reauthenticate` | [IdentityController.Password](#identitycontrollerpassword) | — | Bearer |
| POST | `/me/recovery-code/rotate` | [IdentityController.Recent](#identitycontrollerrecent) | — | Bearer |
| POST | `/me/sessions/revoke-others` | [IdentityController.Recent](#identitycontrollerrecent) | — | Bearer, K |
| GET | `/me/sessions` | — | `cursor?`, `limit?` | Bearer |
| GET | `/me/notification-preferences` | — | — | Bearer |
| PATCH | `/me/notification-preferences` | [IdentityController.Preferences](#identitycontrollerpreferences) | — | Bearer |
| POST | `/me/email-change-requests` | [IdentityController.Email](#identitycontrolleremail) | — | Bearer, K |
| POST | `/me/email-change-requests/resend` | [IdentityController.Resend](#identitycontrollerresend) | — | Bearer, K |
| POST | `/auth/email-verifications/confirm` | [IdentityController.Proof](#identitycontrollerproof) | — | Public |
| POST | `/me/push-devices` | [IdentityController.Push](#identitycontrollerpush) | — | Bearer, K |
| DELETE | `/me/push-devices/{deviceId}` | — | — | Bearer |
| POST | `/schools/{school}/onboarding/{type}/{subjectId}/activation-slips` | — | — | Bearer, K |
| GET | `/public/access-help` | — | — | Public |

## School records and learning

| Method | Path | Input record | Query | Headers |
| --- | --- | --- | --- | --- |
| POST | `/schools/{school}/timetable-plans` | [TimetableService.PlanInput](#timetableserviceplaninput) | — | Bearer, K |
| POST | `/schools/{school}/timetable-plans/{planId}/lessons` | [TimetableService.LessonInput](#timetableservicelessoninput) | — | Bearer, K, V |
| GET | `/schools/{school}/timetable-plans` | — | `termId?` | Bearer |
| PUT | `/schools/{school}/timetable-plans/{planId}/lessons/{lessonId}` | [TimetableService.LessonInput](#timetableservicelessoninput) | — | Bearer, K, V |
| POST | `/schools/{school}/timetable-plans/{planId}/lessons/{lessonId}/remove` | — | — | Bearer, K, V |
| GET | `/schools/{school}/timetable-plans/{planId}/review` | — | — | Bearer |
| POST | `/schools/{school}/timetable-plans/{planId}/publish` | — | — | Bearer, K, V |
| GET | `/schools/{school}/timetable` | — | `classId?`, `on?` | Bearer |
| POST | `/schools/{school}/attendance/registers` | [AttendanceService.RegisterInput](#attendanceserviceregisterinput) | — | Bearer, K |
| GET | `/schools/{school}/attendance/registers/{registerId}` | — | — | Bearer |
| PUT | `/schools/{school}/attendance/registers/{registerId}/marks` | [AttendanceService.Marks](#attendanceservicemarks) | — | Bearer, K, V |
| POST | `/schools/{school}/attendance/registers/{registerId}/submit` | — | — | Bearer, K, V |
| POST | `/schools/{school}/attendance/registers/{registerId}/corrections` | [AttendanceService.Mark](#attendanceservicemark) | — | Bearer, K, V |
| GET | `/schools/{school}/attendance/overview` | — | `on?` | Bearer |
| GET | `/schools/{school}/students/{studentId}/attendance` | — | `from`, `to` | Bearer |
| GET | `/schools/{school}/terms` | — | `sessionId` | Bearer |
| GET | `/schools/{school}/teaching-assignments` | — | `classId?` | Bearer |
| POST | `/schools/{school}/attendance/registers/{registerId}/absence-alerts` | [AcademicController.AttendanceTeacherAlert](#academiccontrollerattendanceteacheralert) | — | Bearer, K |
| POST | `/schools/{school}/calendar-exceptions` | [AcademicController.CalendarInput](#academiccontrollercalendarinput) | — | Bearer, K |
| GET | `/schools/{school}/imports/templates/{kind}` | — | — | Bearer |
| GET | `/schools/{school}/imports` | — | — | Bearer |
| POST | `/schools/{school}/imports` | [AcademicImportService.Preview](#academicimportservicepreview) | — | Bearer, K |
| GET | `/schools/{school}/imports/{jobId}` | — | — | Bearer |
| PUT | `/schools/{school}/imports/{jobId}/rows/{rowId}` | [AcademicImportService.Correction](#academicimportservicecorrection) | — | Bearer, K, V |
| POST | `/schools/{school}/imports/{jobId}/confirm` | [AcademicImportService.Confirmation](#academicimportserviceconfirmation) | — | Bearer, K, V |
| GET | `/schools/{school}/imports/{jobId}/errors.csv` | — | — | Bearer |
| GET | `/schools/{school}/staff` | — | `cursor?`, `limit?` | Bearer |
| POST | `/schools/{school}/staff` | [AdministrationService.StaffInput](#administrationservicestaffinput) | — | Bearer, K |
| GET | `/schools/{school}/staff/{staffId}` | — | — | Bearer |
| PATCH | `/schools/{school}/staff/{staffId}` | [AdministrationService.StaffEdit](#administrationservicestaffedit) | — | Bearer, K, V |
| POST | `/schools/{school}/staff/{staffId}/access` | [AdministrationService.AccessInput](#administrationserviceaccessinput) | — | Bearer, K, V |
| PUT | `/schools/{school}/teaching-assignments/{id}` | [AdministrationService.AssignmentEdit](#administrationserviceassignmentedit) | — | Bearer, K, V |
| PATCH | `/schools/{school}/students/{studentId}` | [AdministrationService.StudentEdit](#administrationservicestudentedit) | — | Bearer, K, V |
| POST | `/schools/{school}/students/{studentId}/transfers` | [AdministrationService.Transfer](#administrationservicetransfer) | — | Bearer, K, V |
| POST | `/schools/{school}/students/{studentId}/withdrawal` | [AdministrationService.Withdrawal](#administrationservicewithdrawal) | — | Bearer, K, V |
| POST | `/schools/{school}/students/{studentId}/guardians` | [AdministrationService.GuardianLink](#administrationserviceguardianlink) | — | Bearer, K, V |
| POST | `/schools/{school}/assessment-cycles` | [AssessmentService.CycleInput](#assessmentservicecycleinput) | — | Bearer, K |
| GET | `/schools/{school}/assessment-cycles` | — | `termId?`, `classId?` | Bearer |
| GET | `/schools/{school}/assessment-cycles/{cycleId}` | — | — | Bearer |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/open` | [AssessmentService.Window](#assessmentservicewindow) | — | Bearer, K, V |
| GET | `/schools/{school}/assessment-cycles/{cycleId}/sheets/{assignmentId}` | — | — | Bearer |
| PUT | `/schools/{school}/assessment-cycles/{cycleId}/sheets/{assignmentId}/marks` | [AssessmentService.Marks](#assessmentservicemarks) | — | Bearer, K, V |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/sheets/{assignmentId}/submit` | — | — | Bearer, K, V |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/sheets/{assignmentId}/return` | [AssessmentService.Reason](#assessmentservicereason) | — | Bearer, K, V |
| PUT | `/schools/{school}/assessment-cycles/{cycleId}/remarks/{student}` | [AssessmentService.Remark](#assessmentserviceremark) | — | Bearer, K, V |
| GET | `/schools/{school}/assessment-cycles/{cycleId}/review` | — | — | Bearer |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/publish` | [AssessmentService.Publication](#assessmentservicepublication) | — | Bearer, K, V |
| POST | `/schools/{school}/assessment-cycles/{cycleId}/reopen` | [AssessmentService.Reopen](#assessmentservicereopen) | — | Bearer, K, V |
| GET | `/schools/{school}/students/{studentId}/reports` | — | — | Bearer |
| GET | `/schools/{school}/reports/{reportId}` | — | — | Bearer |
| GET | `/schools/{school}/reports/{reportId}/pdf` | — | — | Bearer |
| GET | `/schools/{school}/assessment-cycles/{cycleId}/broadsheet.csv` | — | — | Bearer |
| POST | `/schools/{school}/assignments` | [AssignmentService.Input](#assignmentserviceinput) | — | Bearer, K |
| GET | `/schools/{school}/assignments` | — | `classId?`, `termId?`, `cursor?`, `limit?` | Bearer |
| GET | `/schools/{school}/assignments/{assignmentId}` | — | — | Bearer |
| PUT | `/schools/{school}/assignments/{assignmentId}` | [AssignmentService.Input](#assignmentserviceinput) | — | Bearer, K, V |
| POST | `/schools/{school}/assignments/{assignmentId}/publish` | — | — | Bearer, K, V |
| GET | `/schools/{school}/assignments/{assignmentId}/submissions/{studentId}` | — | — | Bearer |
| POST | `/schools/{school}/assignments/{assignmentId}/changes` | [AssignmentService.Change](#assignmentservicechange) | — | Bearer, K, V |
| GET | `/schools/{school}/assignments/{assignmentId}/submissions` | — | — | Bearer |
| PUT | `/schools/{school}/assignments/{assignmentId}/my-submission` | [AssignmentService.Draft](#assignmentservicedraft) | — | Bearer, K, V |
| POST | `/schools/{school}/assignments/{assignmentId}/submit` | — | — | Bearer, K, V |
| POST | `/schools/{school}/assignments/{assignmentId}/submissions/{studentId}/feedback` | [AssignmentService.Feedback](#assignmentservicefeedback) | — | Bearer, K, V |
| GET | `/schools/{school}/assignments/{assignmentId}/submissions/{studentId}/history` | — | — | Bearer |
| POST | `/schools/{school}/exam-schedules` | [ExamService.Schedule](#examserviceschedule) | — | Bearer, K |
| GET | `/schools/{school}/exam-schedules` | — | `termId?` | Bearer |
| GET | `/schools/{school}/exam-schedules/{scheduleId}/review` | — | — | Bearer |
| POST | `/schools/{school}/exam-schedules/{scheduleId}/events` | [ExamService.Event](#examserviceevent) | — | Bearer, K, V |
| PUT | `/schools/{school}/exam-schedules/{scheduleId}/events/{eventId}` | [ExamService.Event](#examserviceevent) | — | Bearer, K, V |
| POST | `/schools/{school}/exam-schedules/{scheduleId}/events/{eventId}/remove` | — | — | Bearer, K, V |
| POST | `/schools/{school}/exam-schedules/{scheduleId}/publish` | — | — | Bearer, K, V |
| GET | `/schools/{school}/examinations` | — | `termId` | Bearer |
| POST | `/schools/{school}/students/{studentId}/external-results` | [ExamService.External](#examserviceexternal) | — | Bearer, K |
| GET | `/schools/{school}/students/{studentId}/external-results` | — | — | Bearer |
| POST | `/schools/{school}/files` | [FileService.Metadata](#fileservicemetadata) | — | Bearer, K |
| GET | `/schools/{school}/files` | — | — | Bearer |
| GET | `/schools/{school}/files/{fileId}` | — | — | Bearer |
| PUT | `/schools/{school}/files/{fileId}/content` | Raw bytes | — | Bearer, V |
| GET | `/schools/{school}/files/{fileId}/content` | — | — | Bearer |
| GET | `/schools/{school}/classes/{classId}/workspace` | — | `on?` | Bearer |
| PATCH | `/schools/{school}/classes/{classId}` | [PlanningService.ClassEdit](#planningserviceclassedit) | — | Bearer, K, V |
| POST | `/schools/{school}/curricula` | [PlanningService.Curriculum](#planningservicecurriculum) | — | Bearer, K |
| GET | `/schools/{school}/curricula` | — | `sessionId?` | Bearer |
| GET | `/schools/{school}/curricula/{curriculumId}` | — | — | Bearer |
| POST | `/schools/{school}/curricula/{curriculumId}/publish` | — | — | Bearer, K, V |
| PUT | `/schools/{school}/classes/{classId}/curriculum` | [PlanningService.CurriculumApply](#planningservicecurriculumapply) | — | Bearer, K, V |
| GET | `/schools/{school}/students/{studentId}/subject-choices` | — | `sessionId` | Bearer |
| PUT | `/schools/{school}/students/{studentId}/subject-choices` | [PlanningService.Choices](#planningservicechoices) | — | Bearer, K, V |
| PUT | `/schools/{school}/staff/{staffId}/teaching-scope` | [PlanningService.TeacherScope](#planningserviceteacherscope) | — | Bearer, K, V |
| GET | `/schools/{school}/calendar` | — | `sessionId` | Bearer |
| GET | `/schools/{school}/calendar-plans` | — | `sessionId` | Bearer |
| POST | `/schools/{school}/calendar-plans` | [PlanningService.CalendarInput](#planningservicecalendarinput) | — | Bearer, K |
| POST | `/schools/{school}/calendar-plans/{id}/publish` | — | — | Bearer, K, V |
| POST | `/schools/{school}/promotion-batches` | [PromotionService.Preview](#promotionservicepreview) | — | Bearer, K |
| GET | `/schools/{school}/promotion-batches` | — | — | Bearer |
| GET | `/schools/{school}/promotion-batches/{batchId}` | — | — | Bearer |
| PUT | `/schools/{school}/promotion-batches/{batchId}/students/{studentId}` | [PromotionService.Decision](#promotionservicedecision) | — | Bearer, K, V |
| POST | `/schools/{school}/promotion-batches/{batchId}/confirm` | — | — | Bearer, K, V |
| GET | `/schools/{school}/promotion-batches/{batchId}/outcomes.csv` | — | — | Bearer |
| GET | `/schools/{school}/students/{studentId}/academic-history` | — | — | Bearer |
| GET | `/schools/{school}/metadata` | — | — | Bearer |
| POST | `/schools/{school}/academic-sessions` | [SchoolService.SessionInput](#schoolservicesessioninput) | — | Bearer, K |
| GET | `/schools/{school}/academic-sessions` | — | — | Bearer |
| POST | `/schools/{school}/terms` | [SchoolService.TermInput](#schoolserviceterminput) | — | Bearer, K |
| POST | `/schools/{school}/classes` | [SchoolService.ClassInput](#schoolserviceclassinput) | — | Bearer, K |
| GET | `/schools/{school}/classes` | — | `sessionId?` | Bearer |
| PUT | `/schools/{school}/classes/{classId}/attendance-teacher` | [SchoolController.AttendanceTeacher](#schoolcontrollerattendanceteacher) | — | Bearer, K, V |
| POST | `/schools/{school}/subjects` | [SchoolService.SubjectInput](#schoolservicesubjectinput) | — | Bearer, K |
| GET | `/schools/{school}/subjects` | — | — | Bearer |
| POST | `/schools/{school}/teachers` | [SchoolService.TeacherInput](#schoolserviceteacherinput) | — | Bearer, K |
| GET | `/schools/{school}/teachers` | — | `cursor?`, `limit?` | Bearer |
| POST | `/schools/{school}/students` | [SchoolService.EnrolInput](#schoolserviceenrolinput) | — | Bearer, K |
| GET | `/schools/{school}/students` | — | `classId?`, `cursor?`, `search?`, `limit?` | Bearer |
| GET | `/schools/{school}/students/{studentId}` | — | — | Bearer |
| POST | `/schools/{school}/students/{studentId}/guardians/{guardianId}/revoke` | [SchoolController.Reason](#schoolcontrollerreason) | — | Bearer, K |
| GET | `/schools/{school}/audit` | — | `cursor?`, `limit?` | Bearer |
| PUT | `/schools/{school}/metadata` | [SchoolController.MetadataInput](#schoolcontrollermetadatainput) | — | Bearer, K, V |
| GET | `/schools/{school}/guardians` | — | `cursor?`, `limit?` | Bearer |
| GET | `/schools/{school}/settings` | — | — | Bearer |
| PUT | `/schools/{school}/settings` | [SchoolWorkspaceService.Settings](#schoolworkspaceservicesettings) | — | Bearer, K, V |
| POST | `/schools/{school}/metadata-versions` | [SchoolWorkspaceService.Metadata](#schoolworkspaceservicemetadata) | — | Bearer, K, V |
| GET | `/schools/{school}/guardians/{guardianId}` | — | — | Bearer |
| PATCH | `/schools/{school}/guardians/{guardianId}` | [SchoolWorkspaceService.GuardianEdit](#schoolworkspaceserviceguardianedit) | — | Bearer, K, V |
| POST | `/schools/{school}/teaching-assignments` | [SchoolWorkspaceService.Teaching](#schoolworkspaceserviceteaching) | — | Bearer, K |
| GET | `/schools/{school}/terms/{termId}/activities` | — | — | Bearer |
| PUT | `/schools/{school}/terms/{termId}/activities` | [SchoolWorkspaceService.Activity](#schoolworkspaceserviceactivity) | — | Bearer, K, V |
| GET | `/schools/{school}/my-classes` | — | `sessionId?` | Bearer |
| GET | `/schools/{school}/dashboard` | — | — | Bearer |
| GET | `/schools/{school}/attendance/follow-ups` | — | `on?` | Bearer |
| GET | `/schools/{school}/students/export.csv` | — | `classId?` | Bearer |
| GET | `/schools/{school}/teachers/export.csv` | — | — | Bearer |

## Fees and reconciliation

| Method | Path | Input record | Query | Headers |
| --- | --- | --- | --- | --- |
| POST | `/schools/{school}/fee-plans` | [FeeAdministrationService.Plan](#feeadministrationserviceplan) | — | Bearer, K |
| PUT | `/schools/{school}/fee-plans/{planId}` | [FeeAdministrationService.Plan](#feeadministrationserviceplan) | — | Bearer, K, V |
| GET | `/schools/{school}/fee-plans` | — | `termId?` | Bearer |
| GET | `/schools/{school}/fee-plans/{planId}` | — | — | Bearer |
| POST | `/schools/{school}/fee-plans/{planId}/publish` | — | — | Bearer, K, V |
| POST | `/schools/{school}/fee-plans/{planId}/batches` | [FeeAdministrationService.Preview](#feeadministrationservicepreview) | — | Bearer, K |
| GET | `/schools/{school}/fee-batches/{batchId}` | — | — | Bearer |
| POST | `/schools/{school}/fee-batches/{batchId}/confirm` | — | — | Bearer, K, V |
| POST | `/schools/{school}/invoices/{invoiceId}/adjustments` | [FeeAdministrationService.Adjustment](#feeadministrationserviceadjustment) | — | Bearer, K |
| GET | `/schools/{school}/fee-adjustments` | — | `invoiceId?` | Bearer |
| GET | `/schools/{school}/fee-adjustments/{adjustmentId}` | — | — | Bearer |
| POST | `/schools/{school}/fee-adjustments/{adjustmentId}/decision` | [FeeAdministrationService.Decision](#feeadministrationservicedecision) | — | Bearer, K, V |
| POST | `/schools/{school}/fee-adjustments/{adjustmentId}/confirm-refund` | [FeeAdministrationService.RefundConfirmation](#feeadministrationservicerefundconfirmation) | — | Bearer, K, V |
| GET | `/schools/{school}/payments` | — | `studentId?`, `cursor?`, `limit?` | Bearer |
| GET | `/schools/{school}/invoices/{invoiceId}/pdf` | — | — | Bearer |
| GET | `/schools/{school}/payments/{paymentId}/receipt` | — | — | Bearer |
| GET | `/schools/{school}/invoices/export.csv` | — | — | Bearer |
| POST | `/schools/{school}/invoices` | [FinanceService.InvoiceInput](#financeserviceinvoiceinput) | — | Bearer, K |
| GET | `/schools/{school}/invoices` | — | `studentId?`, `cursor?`, `limit?` | Bearer |
| GET | `/schools/{school}/invoices/{invoiceId}` | — | — | Bearer |
| POST | `/schools/{school}/invoices/{invoiceId}/manual-payments` | [FinanceService.Manual](#financeservicemanual) | — | Bearer, K, V |
| POST | `/schools/{school}/invoices/payment-settings` | [FinanceController.Settlement](#financecontrollersettlement) | — | Bearer, K |
| POST | `/schools/{school}/invoices/{invoiceId}/checkout` | [FinanceService.Checkout](#financeservicecheckout) | — | Bearer, K |
| GET | `/schools/{school}/payment-intents/{intentId}` | — | — | Bearer |
| POST | `/schools/{school}/payment-intents/{intentId}/verify` | — | — | Bearer |
| GET | `/schools/{school}/reconciliation/exceptions` | — | — | Bearer |
| POST | `/webhooks/paystack` | Raw bytes | — | Provider signature |
| POST | `/schools/{school}/invoices/opening-balances` | [OpeningBalanceService.Preview](#openingbalanceservicepreview) | — | Bearer, K |
| GET | `/schools/{school}/invoices/opening-balances/{batchId}` | — | — | Bearer |
| POST | `/schools/{school}/invoices/opening-balances/{batchId}/confirm` | [OpeningBalanceService.Confirm](#openingbalanceserviceconfirm) | — | Bearer |
| POST | `/schools/{school}/reconciliation/statements` | [ReconciliationService.Statement](#reconciliationservicestatement) | — | Bearer, K |
| POST | `/schools/{school}/reconciliation/paystack-sync` | [ReconciliationService.Period](#reconciliationserviceperiod) | — | Bearer, K |
| GET | `/schools/{school}/reconciliation/settlements` | — | `cursor?`, `limit?` | Bearer |
| GET | `/schools/{school}/reconciliation/settlements/{settlementId}` | — | — | Bearer |
| POST | `/schools/{school}/reconciliation/settlements/{settlementId}/rows/{rowId}/resolution` | [ReconciliationService.Resolution](#reconciliationserviceresolution) | — | Bearer |
| GET | `/schools/{school}/reconciliation/overview` | — | — | Bearer |

## Notices, parent conversations and complaints

| Method | Path | Input record | Query | Headers |
| --- | --- | --- | --- | --- |
| GET | `/me/notifications` | — | `schoolId`, `category?`, `unread?`, `cursor?`, `limit?` | Bearer |
| PUT | `/me/notifications/{notificationId}/read` | — | — | Bearer |
| GET | `/me/notifications/{notificationId}/delivery` | — | — | Bearer |
| POST | `/schools/{school}/notices` | [CommunicationWorkService.Notice](#communicationworkservicenotice) | — | Bearer, K |
| GET | `/schools/{school}/notices` | — | `cursor?`, `limit?` | Bearer |
| POST | `/schools/{school}/complaints` | [CommunicationWorkService.Complaint](#communicationworkservicecomplaint) | — | Bearer, K |
| GET | `/schools/{school}/complaints` | — | `cursor?`, `limit?` | Bearer |
| GET | `/schools/{school}/complaints/{caseId}` | — | — | Bearer |
| POST | `/schools/{school}/complaints/{caseId}/replies` | [CommunicationWorkService.Reply](#communicationworkservicereply) | — | Bearer, K, V |
| POST | `/schools/{school}/complaints/{caseId}/close` | [CommunicationController.Close](#communicationcontrollerclose) | — | Bearer, K, V |
| GET | `/schools/{school}/notices/{id}` | — | — | Bearer |
| PUT | `/schools/{school}/notices/{id}` | [CommunicationWorkService.Notice](#communicationworkservicenotice) | — | Bearer, K, V |
| POST | `/schools/{school}/notices/{id}/publish` | — | — | Bearer, K, V |
| POST | `/schools/{school}/notices/{id}/archive` | [CommunicationWorkController.Reason](#communicationworkcontrollerreason) | — | Bearer, K, V |
| GET | `/schools/{school}/threads/contacts` | — | `studentId` | Bearer |
| POST | `/schools/{school}/threads` | [CommunicationWorkService.ThreadInput](#communicationworkservicethreadinput) | — | Bearer, K |
| GET | `/schools/{school}/threads` | — | `cursor?`, `limit?` | Bearer |
| GET | `/schools/{school}/threads/{threadId}` | — | — | Bearer |
| POST | `/schools/{school}/threads/{threadId}/messages` | [CommunicationWorkService.Message](#communicationworkservicemessage) | — | Bearer, K, V |
| POST | `/schools/{school}/threads/{threadId}/close` | [CommunicationWorkController.Reason](#communicationworkcontrollerreason) | — | Bearer, K, V |
| PUT | `/schools/{school}/threads/{threadId}/read` | — | — | Bearer |
| POST | `/schools/{school}/complaints/{caseId}/status` | [CommunicationWorkService.StateChange](#communicationworkservicestatechange) | — | Bearer, K, V |
| PUT | `/schools/{school}/complaints/{caseId}/owner` | [CommunicationWorkService.Owner](#communicationworkserviceowner) | — | Bearer, K, V |
| GET | `/schools/{school}/complaints/{caseId}/history` | — | — | Bearer |

## Service-owned activity and account history

| Method | Path | Input record | Query | Headers |
| --- | --- | --- | --- | --- |
| GET | `/schools/{school}/activity/{domain}` | — | `from?`, `to?`, `cursor?`, `limit?` | Bearer |
| GET | `/schools/{school}/activity/{domain}/export.csv` | — | `from?`, `to?` | Bearer |
| GET | `/me/security-history` | — | `cursor?`, `limit?` | Bearer |

## Request fields

Names below are exact JSON property names. Use JSON arrays for lists, `YYYY-MM-DD` for dates, `HH:mm` for lesson/exam start times and ISO timestamps for deadlines. Amounts ending in `Minor` are **integer kobo**. Score limits and one-decimal rules are enforced by the backend. Optional fields can be omitted; do not fill unused UUID properties with empty strings.

### AcademicController.AttendanceTeacherAlert

| Field | Java/wire type |
| --- | --- |
| `studentId` | `UUID` |
| `reviewReason` | `String` |

### AcademicController.CalendarInput

| Field | Java/wire type |
| --- | --- |
| `onDate` | `LocalDate` |
| `title` | `String` |
| `teachingDay` | `boolean` |
| `reason` | `String` |

### AcademicImportService.Confirmation

| Field | Java/wire type |
| --- | --- |
| `relationshipsVerified` | `boolean` |
| `duplicatesReviewed` | `boolean` |
| `reason` | `String` |

### AcademicImportService.Correction

| Field | Java/wire type |
| --- | --- |
| `fields` | `Map<String, String>` |
| `reason` | `String` |

### AcademicImportService.Preview

| Field | Java/wire type |
| --- | --- |
| `kind` | `String` |
| `sessionId` | `UUID` |
| `csv` | `String` |
| `columnMapping` | `Map<String, String>` |

### AdministrationService.AccessInput

| Field | Java/wire type |
| --- | --- |
| `role` | `String` |
| `active` | `boolean` |
| `reason` | `String` |

### AdministrationService.AssignmentEdit

| Field | Java/wire type |
| --- | --- |
| `teacherId` | `UUID` |
| `weeklyPeriods` | `int` |
| `reason` | `String` |

### AdministrationService.GuardianLink

| Field | Java/wire type |
| --- | --- |
| `guardianId` | `UUID` |
| `guardian` | `SchoolService.GuardianInput` |
| `relationship` | `String` |
| `learningAccess` | `boolean` |
| `payerAccess` | `boolean` |
| `primaryContact` | `boolean` |
| `reason` | `String` |

### AdministrationService.StaffEdit

| Field | Java/wire type |
| --- | --- |
| `fullName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `staffNumber` | `String` |
| `joinedOn` | `LocalDate` |
| `reason` | `String` |

### AdministrationService.StaffInput

| Field | Java/wire type |
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

| Field | Java/wire type |
| --- | --- |
| `fullName` | `String` |
| `dateOfBirth` | `LocalDate` |
| `sex` | `String` |
| `fields` | `Map<String, Object>` |
| `metadataVersion` | `long` |
| `reason` | `String` |

### AdministrationService.Transfer

| Field | Java/wire type |
| --- | --- |
| `classId` | `UUID` |
| `effectiveOn` | `LocalDate` |
| `department` | `String` |
| `reason` | `String` |

### AdministrationService.Withdrawal

| Field | Java/wire type |
| --- | --- |
| `effectiveOn` | `LocalDate` |
| `reason` | `String` |

### AssessmentService.CycleInput

| Field | Java/wire type |
| --- | --- |
| `termId` | `UUID` |
| `classId` | `UUID` |
| `opensAt` | `Instant` |
| `closesAt` | `Instant` |

### AssessmentService.Marks

| Field | Java/wire type |
| --- | --- |
| `rows` | `List<Score>` |

### AssessmentService.Publication

| Field | Java/wire type |
| --- | --- |
| `principalRemark` | `String` |
| `nextResumptionOn` | `LocalDate` |

### AssessmentService.Reason

| Field | Java/wire type |
| --- | --- |
| `reason` | `String` |

### AssessmentService.Remark

| Field | Java/wire type |
| --- | --- |
| `remark` | `String` |
| `conduct` | `int` |
| `punctuality` | `int` |
| `cooperation` | `int` |
| `handwriting` | `int` |
| `practicalSkills` | `int` |

### AssessmentService.Reopen

| Field | Java/wire type |
| --- | --- |
| `assignmentId` | `UUID` |
| `reason` | `String` |
| `closesAt` | `Instant` |

### AssessmentService.Score

| Field | Java/wire type |
| --- | --- |
| `studentId` | `UUID` |
| `ca1` | `BigDecimal` |
| `ca2` | `BigDecimal` |
| `exam` | `BigDecimal` |
| `examState` | `String` |
| `note` | `String` |

### AssessmentService.Window

| Field | Java/wire type |
| --- | --- |
| `opensAt` | `Instant` |
| `closesAt` | `Instant` |
| `reason` | `String` |

### AssignmentService.Change

| Field | Java/wire type |
| --- | --- |
| `action` | `String` |
| `dueAt` | `Instant` |
| `reason` | `String` |

### AssignmentService.Draft

| Field | Java/wire type |
| --- | --- |
| `responseText` | `String` |
| `fileIds` | `List<UUID>` |

### AssignmentService.Feedback

| Field | Java/wire type |
| --- | --- |
| `action` | `String` |
| `score` | `BigDecimal` |
| `feedback` | `String` |
| `reason` | `String` |
| `extraDeadline` | `Instant` |

### AssignmentService.Input

| Field | Java/wire type |
| --- | --- |
| `teachingAssignmentId` | `UUID` |
| `termId` | `UUID` |
| `title` | `String` |
| `instructions` | `String` |
| `dueAt` | `Instant` |
| `maximumScore` | `BigDecimal` |
| `fileIds` | `List<UUID>` |

### AttendanceService.Mark

| Field | Java/wire type |
| --- | --- |
| `studentId` | `UUID` |
| `status` | `String` |
| `reason` | `String` |

### AttendanceService.Marks

| Field | Java/wire type |
| --- | --- |
| `rows` | `List<Mark>` |

### AttendanceService.RegisterInput

| Field | Java/wire type |
| --- | --- |
| `classId` | `UUID` |
| `onDate` | `LocalDate` |

### CommunicationController.Close

| Field | Java/wire type |
| --- | --- |
| `reason` | `String` |

### CommunicationWorkController.Reason

| Field | Java/wire type |
| --- | --- |
| `reason` | `String` |

### CommunicationWorkService.Complaint

| Field | Java/wire type |
| --- | --- |
| `studentId` | `UUID` |
| `guardianId` | `UUID` |
| `category` | `String` |
| `subject` | `String` |
| `body` | `String` |
| `intakeChannel` | `String` |
| `fileIds` | `List<UUID>` |

### CommunicationWorkService.Message

| Field | Java/wire type |
| --- | --- |
| `body` | `String` |
| `fileIds` | `List<UUID>` |

### CommunicationWorkService.Notice

| Field | Java/wire type |
| --- | --- |
| `title` | `String` |
| `body` | `String` |
| `scope` | `String` |
| `classId` | `UUID` |
| `department` | `String` |
| `fileIds` | `List<UUID>` |

### CommunicationWorkService.Owner

| Field | Java/wire type |
| --- | --- |
| `staffId` | `UUID` |
| `reason` | `String` |

### CommunicationWorkService.Reply

| Field | Java/wire type |
| --- | --- |
| `body` | `String` |
| `internal` | `boolean` |
| `fileIds` | `List<UUID>` |

### CommunicationWorkService.StateChange

| Field | Java/wire type |
| --- | --- |
| `action` | `String` |
| `reason` | `String` |

### CommunicationWorkService.ThreadInput

| Field | Java/wire type |
| --- | --- |
| `studentId` | `UUID` |
| `guardianId` | `UUID` |
| `target` | `String` |
| `subject` | `String` |
| `body` | `String` |
| `fileIds` | `List<UUID>` |

### ExamService.Event

| Field | Java/wire type |
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

| Field | Java/wire type |
| --- | --- |
| `board` | `String` |
| `examYear` | `int` |
| `candidateReference` | `String` |
| `issuedResult` | `Map<String, Object>` |
| `reason` | `String` |

### ExamService.Schedule

| Field | Java/wire type |
| --- | --- |
| `termId` | `UUID` |

### FeeAdministrationService.Adjustment

| Field | Java/wire type |
| --- | --- |
| `kind` | `String` |
| `amountMinor` | `long` |
| `paymentId` | `UUID` |
| `reason` | `String` |
| `evidenceReference` | `String` |

### FeeAdministrationService.Decision

| Field | Java/wire type |
| --- | --- |
| `approve` | `boolean` |
| `reason` | `String` |

### FeeAdministrationService.Plan

| Field | Java/wire type |
| --- | --- |
| `termId` | `UUID` |
| `name` | `String` |
| `classIds` | `List<UUID>` |
| `lines` | `List<FinanceService.Line>` |
| `dueOn` | `LocalDate` |
| `partialAllowed` | `boolean` |

### FeeAdministrationService.Preview

| Field | Java/wire type |
| --- | --- |
| `classIds` | `List<UUID>` |

### FeeAdministrationService.RefundConfirmation

| Field | Java/wire type |
| --- | --- |
| `reference` | `String` |
| `verificationReference` | `String` |

### FileService.Metadata

| Field | Java/wire type |
| --- | --- |
| `purpose` | `String` |
| `studentId` | `UUID` |
| `classId` | `UUID` |
| `filename` | `String` |
| `mediaType` | `String` |
| `size` | `int` |
| `sha256` | `String` |
| `familyVisible` | `boolean` |

### FinanceController.Settlement

| Field | Java/wire type |
| --- | --- |
| `subaccount` | `String` |

### FinanceService.Checkout

| Field | Java/wire type |
| --- | --- |
| `amountMinor` | `long` |
| `email` | `String` |

### FinanceService.InvoiceInput

| Field | Java/wire type |
| --- | --- |
| `studentId` | `UUID` |
| `termId` | `UUID` |
| `description` | `String` |
| `lines` | `List<Line>` |
| `dueOn` | `LocalDate` |
| `partialAllowed` | `boolean` |

### FinanceService.Line

| Field | Java/wire type |
| --- | --- |
| `description` | `String` |
| `amountMinor` | `long` |
| `quantity` | `int` |

### FinanceService.Manual

| Field | Java/wire type |
| --- | --- |
| `amountMinor` | `long` |
| `method` | `String` |
| `reference` | `String` |
| `verificationReference` | `String` |
| `reason` | `String` |

### IdentityController.Activation

| Field | Java/wire type |
| --- | --- |
| `loginId` | `String` |
| `activationCode` | `String` |
| `invitationToken` | `String` |

### IdentityController.ChangePassword

| Field | Java/wire type |
| --- | --- |
| `currentPassword` | `String` |
| `newPassword` | `String` |

### IdentityController.Email

| Field | Java/wire type |
| --- | --- |
| `email` | `String` |
| `recentAuthToken` | `String` |

### IdentityController.Identifier

| Field | Java/wire type |
| --- | --- |
| `identifier` | `String` |

### IdentityController.Login

| Field | Java/wire type |
| --- | --- |
| `identifier` | `String` |
| `password` | `String` |
| `installationId` | `String` |
| `deviceName` | `String` |

### IdentityController.Password

| Field | Java/wire type |
| --- | --- |
| `currentPassword` | `String` |

### IdentityController.Phone

| Field | Java/wire type |
| --- | --- |
| `phone` | `String` |

### IdentityController.Preference

| Field | Java/wire type |
| --- | --- |
| `schoolId` | `UUID` |
| `role` | `String` |
| `childId` | `UUID` |

### IdentityController.Preferences

| Field | Java/wire type |
| --- | --- |
| `pushEnabled` | `boolean` |
| `emailOptionalEnabled` | `boolean` |
| `categories` | `List<String>` |

### IdentityController.Proof

| Field | Java/wire type |
| --- | --- |
| `token` | `String` |

### IdentityController.Push

| Field | Java/wire type |
| --- | --- |
| `installationId` | `String` |
| `platform` | `String` |
| `pushToken` | `String` |

### IdentityController.Recent

| Field | Java/wire type |
| --- | --- |
| `recentAuthToken` | `String` |

### IdentityController.Recovery

| Field | Java/wire type |
| --- | --- |
| `loginId` | `String` |
| `recoveryCode` | `String` |

### IdentityController.Resend

| Field | Java/wire type |
| --- | --- |
| `requestId` | `UUID` |

### IdentityController.Setup

| Field | Java/wire type |
| --- | --- |
| `flowToken` | `String` |
| `newPassword` | `String` |

### IdentityController.Token

| Field | Java/wire type |
| --- | --- |
| `refreshToken` | `String` |

### OpeningBalanceService.Confirm

| Field | Java/wire type |
| --- | --- |
| `reason` | `String` |

### OpeningBalanceService.Preview

| Field | Java/wire type |
| --- | --- |
| `termId` | `UUID` |
| `csv` | `String` |
| `columnMapping` | `Map<String, String>` |

### PlanningService.Availability

| Field | Java/wire type |
| --- | --- |
| `weekday` | `int` |
| `startMinute` | `int` |
| `endMinute` | `int` |
| `reason` | `String` |

### PlanningService.CalendarChange

| Field | Java/wire type |
| --- | --- |
| `onDate` | `LocalDate` |
| `title` | `String` |
| `teachingDay` | `boolean` |
| `reason` | `String` |

### PlanningService.CalendarInput

| Field | Java/wire type |
| --- | --- |
| `sessionId` | `UUID` |
| `changes` | `List<CalendarChange>` |

### PlanningService.Choices

| Field | Java/wire type |
| --- | --- |
| `sessionId` | `UUID` |
| `subjectIds` | `List<UUID>` |
| `reason` | `String` |

### PlanningService.ClassEdit

| Field | Java/wire type |
| --- | --- |
| `capacity` | `Integer` |
| `room` | `String` |
| `reason` | `String` |

### PlanningService.Curriculum

| Field | Java/wire type |
| --- | --- |
| `sessionId` | `UUID` |
| `level` | `String` |
| `department` | `String` |
| `name` | `String` |
| `subjects` | `List<SubjectRule>` |

### PlanningService.CurriculumApply

| Field | Java/wire type |
| --- | --- |
| `curriculumId` | `UUID` |
| `teachers` | `Map<UUID, UUID>` |
| `reason` | `String` |

### PlanningService.SubjectRule

| Field | Java/wire type |
| --- | --- |
| `subjectId` | `UUID` |
| `required` | `boolean` |
| `choiceGroup` | `String` |
| `weeklyPeriods` | `int` |

### PlanningService.TeacherScope

| Field | Java/wire type |
| --- | --- |
| `eligibleLevels` | `List<String>` |
| `subjectIds` | `List<UUID>` |
| `unavailable` | `List<Availability>` |
| `reason` | `String` |

### PromotionService.Decision

| Field | Java/wire type |
| --- | --- |
| `decision` | `String` |
| `targetClassId` | `UUID` |
| `department` | `String` |
| `reason` | `String` |
| `evidenceReference` | `String` |

### PromotionService.Preview

| Field | Java/wire type |
| --- | --- |
| `sourceSessionId` | `UUID` |
| `targetSessionId` | `UUID` |
| `sourceClassId` | `UUID` |

### ReconciliationService.Period

| Field | Java/wire type |
| --- | --- |
| `from` | `LocalDate` |
| `to` | `LocalDate` |

### ReconciliationService.Resolution

| Field | Java/wire type |
| --- | --- |
| `resolution` | `String` |
| `reason` | `String` |

### ReconciliationService.Row

| Field | Java/wire type |
| --- | --- |
| `reference` | `String` |
| `amountMinor` | `long` |
| `feeMinor` | `long` |
| `netMinor` | `long` |

### ReconciliationService.Statement

| Field | Java/wire type |
| --- | --- |
| `reference` | `String` |
| `rows` | `List<Row>` |

### SchoolController.AttendanceTeacher

| Field | Java/wire type |
| --- | --- |
| `teacherId` | `UUID` |

### SchoolController.FieldDefinition

| Field | Java/wire type |
| --- | --- |
| `key` | `String` |
| `required` | `boolean` |
| `maxLength` | `int` |

### SchoolController.MetadataInput

| Field | Java/wire type |
| --- | --- |
| `studentFields` | `List<FieldDefinition>` |
| `wednesdayPtEnabled` | `Boolean` |

### SchoolController.Reason

| Field | Java/wire type |
| --- | --- |
| `reason` | `String` |

### SchoolService.AssignmentInput

| Field | Java/wire type |
| --- | --- |
| `classId` | `UUID` |
| `subjectId` | `UUID` |
| `department` | `String` |
| `weeklyPeriods` | `int` |

### SchoolService.ClassInput

| Field | Java/wire type |
| --- | --- |
| `sessionId` | `UUID` |
| `level` | `String` |
| `arm` | `String` |
| `attendanceTeacherId` | `UUID` |

### SchoolService.EnrolInput

| Field | Java/wire type |
| --- | --- |
| `admissionNumber` | `String` |
| `fullName` | `String` |
| `dateOfBirth` | `LocalDate` |
| `sex` | `String` |
| `classId` | `UUID` |
| `startsOn` | `LocalDate` |
| `department` | `String` |
| `metadataVersion` | `long` |
| `fields` | `Map<String, Object>` |
| `guardian` | `GuardianInput` |
| `admissionType` | `String` |
| `legacyReference` | `String` |
| `subjectIds` | `List<UUID>` |

### SchoolService.GuardianInput

| Field | Java/wire type |
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

| Field | Java/wire type |
| --- | --- |
| `name` | `String` |
| `startsOn` | `LocalDate` |
| `endsOn` | `LocalDate` |

### SchoolService.SubjectInput

| Field | Java/wire type |
| --- | --- |
| `code` | `String` |
| `name` | `String` |
| `core` | `boolean` |

### SchoolService.TeacherInput

| Field | Java/wire type |
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

| Field | Java/wire type |
| --- | --- |
| `sessionId` | `UUID` |
| `number` | `int` |
| `startsOn` | `LocalDate` |
| `endsOn` | `LocalDate` |

### SchoolWorkspaceService.Activity

| Field | Java/wire type |
| --- | --- |
| `activity` | `String` |
| `teacherIds` | `List<UUID>` |
| `reason` | `String` |

### SchoolWorkspaceService.FormField

| Field | Java/wire type |
| --- | --- |
| `key` | `String` |
| `label` | `String` |
| `maxLength` | `int` |
| `required` | `boolean` |

### SchoolWorkspaceService.GuardianEdit

| Field | Java/wire type |
| --- | --- |
| `fullName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `reason` | `String` |

### SchoolWorkspaceService.Metadata

| Field | Java/wire type |
| --- | --- |
| `studentFields` | `List<FormField>` |
| `reason` | `String` |

### SchoolWorkspaceService.Settings

| Field | Java/wire type |
| --- | --- |
| `phone` | `String` |
| `email` | `String` |
| `address` | `String` |
| `wednesdayPtEnabled` | `boolean` |
| `reason` | `String` |

### SchoolWorkspaceService.Teaching

| Field | Java/wire type |
| --- | --- |
| `teacherId` | `UUID` |
| `assignment` | `SchoolService.AssignmentInput` |
| `reason` | `String` |

### TimetableService.LessonInput

| Field | Java/wire type |
| --- | --- |
| `assignmentId` | `UUID` |
| `weekday` | `int` |
| `start` | `String` |
| `durationMinutes` | `int` |
| `room` | `String` |

### TimetableService.PlanInput

| Field | Java/wire type |
| --- | --- |
| `termId` | `UUID` |
| `effectiveFrom` | `LocalDate` |
| `sourcePlanId` | `UUID` |

