# Implemented frontend API

Base URL in local web development: **`http://localhost:8080/api/v1`**. Paths below are relative to that base. `{school}` and other IDs are UUIDs returned by the API. Use actual returned values; display names and admission numbers are not database IDs.

This inventory describes the code in this build. Earlier screen plans also contain endpoints that are not implemented yet. See [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) before connecting a page. Input record types live beside the controllers/services; the examples below show their exact field names.

## Rules every frontend Model follows

- Send `Authorization: Bearer <accessToken>` on protected endpoints. Tokens are private; the app's shared API client owns refresh/logout. A role or school header cannot grant access.
- Choose a returned context with `PUT /me/context` before school operations. The path's school must match the current selected school. A parent must also select the relevant permitted child; a fee payment requires that child's payer permission.
- **K** in a table means send a stable UUID `Idempotency-Key`. Reuse it and the identical request for a retry after a timeout. Create a new key for a new user action. Reusing a key with a different body is rejected. Keep the original request/version while retrying.
- **V** means send `If-Match: "vN"`, using the returned record version. For example, version `3` becomes `If-Match: "v3"`, including quotation marks. A stale version returns `412`; preserve the user's draft, reload and ask them to review before submitting a fresh command. Missing version returns `428`.
- Send JSON request bodies and `Content-Type: application/json`, except Paystack's provider webhook. Identifiers use UUID strings; dates use `YYYY-MM-DD`; times use `HH:mm`; money uses integer kobo (`500000` means ₦5,000).
- Cursor lists return `items`, `nextCursor` and `hasMore`. Pass the cursor back unchanged and use `limit` (default usually 30, maximum 100). Small catalogue lists can return `items` and `hasMore:false` without a cursor.
- A `202` or `QUEUED` result is accepted work, not completed account provisioning/payment/delivery. Show the returned state and reload the appropriate resource. Never invent success after a timeout.

Errors use `application/problem+json`, with `status`, `code`, `detail`, `requestId`, `retryable` and, where provided, `errors`. Handle `401` with the shared session flow; `403/404` mean unavailable access/resource; `409` means a business conflict or duplicate; `412` means stale data; `422` means invalid input; `428` means a version is required; `503` means a dependency could not be reached. Show a safe error and request ID, never a SQL trace.

## Shared account screens — Identity

These endpoints retain the existing shared frontend `SharedModel` contracts.

| Method/path | Screen or action | Body/query | Headers |
| --- | --- | --- | --- |
| POST `/auth/login` | Sign in | `identifier,password,installationId,deviceName` | Public |
| POST `/auth/refresh` | Refresh session | `refreshToken` | Public |
| POST `/auth/logout` | End session | `refreshToken` | Public |
| POST `/auth/activation/review` | Review initial activation | `loginId,activationCode` or `invitationToken` | Public |
| POST `/auth/password/setup` | Save activated/recovered password | `flowToken,newPassword` | Public |
| POST `/auth/recovery/email` | Request private email recovery | `identifier` | Public; generic acknowledgement |
| POST `/auth/recovery/email/review` | Review emailed proof | `token` | Public |
| POST `/auth/recovery/code/review` | Review saved private code | `loginId,recoveryCode` | Public |
| POST `/auth/email-verifications/confirm` | Confirm new email | `token` | Public |
| GET `/public/access-help` | Access help | None | Public |
| GET `/me` | Account/profile | None | Bearer |
| PATCH `/me` | Save own phone | `phone` | Bearer, V |
| GET `/me/contexts` | School/role/child picker | None | Bearer |
| PUT `/me/context` | Select permitted context | `schoolId,role,childId?` | Bearer |
| POST `/me/password/change` | Change private password | `currentPassword,newPassword` | Bearer |
| POST `/me/reauthenticate` | Reconfirm password before sensitive action | `currentPassword` | Bearer |
| POST `/me/recovery-code/rotate` | Replace saved recovery code | `recentAuthToken` | Bearer |
| GET `/me/sessions` | Signed-in devices | `cursor?,limit?` | Bearer |
| POST `/me/sessions/revoke-others` | Sign out other devices | `recentAuthToken` | Bearer, K |
| GET `/me/notification-preferences` | Notification settings | None | Bearer |
| PATCH `/me/notification-preferences` | Save settings | `pushEnabled,emailOptionalEnabled,categories` | Bearer |
| POST `/me/email-change-requests` | Begin verified email change | `email,recentAuthToken` | Bearer, K; returns 202 |
| POST `/me/email-change-requests/resend` | Resend pending verification | `requestId` | Bearer, K; returns 202 |
| POST `/me/push-devices` | Register installation | `installationId,platform,pushToken` | Bearer, K |
| DELETE `/me/push-devices/{id}` | Remove own device | None | Bearer; returns 204 |
| POST `/schools/{school}/onboarding/{type}/{subject}/activation-slips` | Office hands out pending-account access | No body; type `STUDENT`, `GUARDIAN` or `TEACHER`; subject is school record ID | Bearer, K; principal/office |

Private school-issued activation codes are for pending accounts. An active global parent is linked without resetting their password. A school staff member cannot mint a private recovery code or replace an active parent's identity. A recent-auth token lasts five minutes. Password setup returns the new private recovery code; display it privately and do not put it in logs.

## School records — Academics

All paths in this section start with `/schools/{school}`. Administrative writes require principal/office unless stated. Family reads are filtered and do not expose staff-only fields. Role presets do not imply unfinished onboarding APIs exist.

| Method/path suffix | Page/action | Input/filter | Headers |
| --- | --- | --- | --- |
| GET `/metadata` | Enrolment form settings | None | Bearer |
| PUT `/metadata` | Principal publishes settings | `studentFields:[{key,required,maxLength}],wednesdayPtEnabled?` | Bearer, K, V; principal |
| GET `/academic-sessions` | Session picker | None | Bearer |
| POST `/academic-sessions` | Create session | `name,startsOn,endsOn` | Bearer, K |
| GET `/terms` | Term picker | Query `sessionId` required | Bearer |
| POST `/terms` | Create term | `sessionId,number,startsOn,endsOn` | Bearer, K |
| GET `/classes` | Class/arm picker | Query `sessionId?` | Bearer |
| POST `/classes` | Create class arm | `sessionId,level,arm,attendanceTeacherId?` | Bearer, K |
| PUT `/classes/{class}/attendance-teacher` | Assign register responsibility | `teacherId` | Bearer, K, V |
| GET `/subjects` | Subject picker | None | Bearer |
| POST `/subjects` | Add subject | `code,name,core` | Bearer, K |
| GET `/teachers` | Teacher directory | `cursor?,limit?` | Bearer; permitted staff |
| POST `/teachers` | Register teacher and assignments | Teacher object below | Bearer, K |
| GET `/teaching-assignments` | Timetable teacher/subject choices | `classId?`; a teacher receives own assignments | Bearer; staff/teacher |
| GET `/students` | Student list | `classId?,search?,cursor?,limit?`; server filters scope | Bearer |
| POST `/students` | Enrol child and guardian | Enrolment object below | Bearer, K; returns 202 |
| GET `/students/{student}` | Student profile | None | Bearer; permitted staff/teacher/own child |
| GET `/guardians` | Existing parent picker | `cursor?,limit?` | Bearer; principal/office |
| POST `/students/{student}/guardians/{guardian}/revoke` | Revoke school-child relationship | `reason` | Bearer, K; principal/office |
| GET `/audit` | Academic activity/history | `cursor?,limit?` | Bearer; principal/office |
| POST `/calendar-exceptions` | Principal records holiday/teaching exception | `onDate,title,teachingDay,reason` | Bearer, K; principal |

Metadata is intentionally limited to supported `homeAddress` and `previousSchool` text fields. It cannot add arbitrary executable fields or change core identity/payment rules. Read its current version before enrolment. Session dates and all three term dates must be supplied from the school's actual calendar.

Teacher registration example (replace every `<...>` with an actual returned ID):

```json
{
  "fullName": "Bisi Demo",
  "phone": "08022345678",
  "eligibleLevels": ["JSS1"],
  "subjectIds": ["<english-subject-id>"],
  "assignments": [
    {"classId":"<jss1a-id>","subjectId":"<english-subject-id>","weeklyPeriods":4}
  ]
}
```

`email` is optional. Explicitly linking an existing global teacher uses `existingLoginId` and `relationshipVerified:true`. The eligible level/subject lists describe their permitted combinations; assignments describe the classes they actually teach. A lesson selects the assignment, so its teacher/class/subject cannot be chosen inconsistently. Weekly periods count 30-minute units. `department` is optional on an SSS teaching assignment: omit it for a common subject or specify a supported department for that group's lesson.

Enrolment example:

```json
{
  "admissionNumber": "DEMO/2026/001",
  "fullName": "Tomi Demo",
  "dateOfBirth": "2014-05-10",
  "sex": "FEMALE",
  "classId": "<class-id>",
  "startsOn": "2026-09-14",
  "metadataVersion": 1,
  "fields": {},
  "guardian": {
    "fullName": "Sade Demo",
    "phone": "08032345678",
    "relationship": "MOTHER",
    "relationshipVerified": true,
    "verificationReference": "Office visit DEMO-001"
  }
}
```

Use a start date inside the selected session, current `metadataVersion`, and any required metadata fields. JSS omits `department`; SSS must send `SCIENCE`, `ARTS` or `COMMERCIAL`. Email is optional. For an existing guardian in this school, send `guardian:{"existingGuardianId":"<id>","relationship":"MOTHER"}`. For a verified existing global parent in a different school, provide the new school guardian details plus `existingLoginId`; no matching by unverified phone/email happens automatically.

The response contains `id`, `enrolmentId`, `guardianId`, `classId`, `version`, `onboardingEventId` and `onboardingStatus:"ACCOUNTS_PENDING"`. Then request the student and guardian activation slips by their separate subject IDs. A previously active linked parent continues using their existing credentials. Do not offer a reset button for that parent.

## Timetable — Academics

Prefix: `/schools/{school}`. Principal/office prepare; principal publishes. The same effective plan feeds teacher, student and parent views with server-side class/department/scope filters.

| Method/path suffix | Page/action | Input/filter | Headers |
| --- | --- | --- | --- |
| POST `/timetable-plans` | New weekly plan | `termId,effectiveFrom` | Bearer, K |
| POST `/timetable-plans/{plan}/lessons` | Add subject period | `assignmentId,weekday,start,durationMinutes,room?` | Bearer, K, V (plan version) |
| GET `/timetable-plans/{plan}/review` | Review draft and weekly targets | None | Bearer; principal/office |
| POST `/timetable-plans/{plan}/publish` | Principal approval/publication | No body | Bearer, K, V (plan version) |
| GET `/timetable` | Effective published schedule | `classId?,on?` (`YYYY-MM-DD`) | Bearer; server filters teacher/family |

`weekday` uses Monday=1 through Friday=5. Example lesson: `{"assignmentId":"<assignment-id>","weekday":1,"start":"08:30","durationMinutes":60,"room":"Room 1"}`. A successful add returns `planVersion`; use that latest plan version on the next add/review/publish. The review supplies `lessons`, `weeklyTargets` and `readyToPublish`. Publishing requires all registered assignment targets to match and cannot be forced from the app. Editing/deleting a lesson and listing drafts are still to build; do not show functioning edit controls for unsupported routes.

## Attendance — Academics

Prefix: `/schools/{school}`. The class attendance teacher records the register. The office sees submitted registers and missing-register coverage. Families see their own child's submitted history.

| Method/path suffix | Page/action | Input/filter | Headers |
| --- | --- | --- | --- |
| POST `/attendance/registers` | Open dated roster | `classId,onDate` | Bearer, K; assigned teacher |
| GET `/attendance/registers/{register}` | Load register | None | Bearer; assigned teacher or office after submission |
| PUT `/attendance/registers/{register}/marks` | Save draft marks | `rows:[{studentId,status,reason?}]` | Bearer, K, V; assigned teacher |
| POST `/attendance/registers/{register}/submit` | Submit complete register | No body | Bearer, K, V; assigned teacher |
| GET `/attendance/overview` | Submitted attendance dashboard | `on?` | Bearer; principal/office |
| POST `/attendance/registers/{register}/corrections` | Reasoned submitted correction | `studentId,status,reason` | Bearer, K, V; principal/office |
| POST `/attendance/registers/{register}/absence-alerts` | Office approves parent notification | `studentId,reviewReason` | Bearer, K; principal/office |
| GET `/students/{student}/attendance` | Student/parent attendance history | `from,to` required | Bearer; authorised own-child/student/staff view |

Marks are `PRESENT`, `ABSENT`, `LATE` or `EXCUSED`. A new roster's marks are empty, not automatically present or absent. All rostered pupils need a valid status before submission. Read back the returned register/version after saving; use the latest version for submission or correction. An absence alert is queued only after office review of a submitted `ABSENT` row. Missing teacher submissions must stay separate from actual absences.

## Invoices and payments — Finance

Prefix: `/schools/{school}`. Principal/accounts manage money; a selected parent with payer permission can read/pay the relevant child's invoice. Student and office read-only fee permissions are not implemented by these routes yet.

| Method/path suffix | Page/action | Input/filter | Headers |
| --- | --- | --- | --- |
| POST `/invoices` | Issue invoice | Invoice object below | Bearer, K; principal/accounts |
| GET `/invoices` | Invoice list | `studentId?,cursor?,limit?`; parent needs selected child | Bearer |
| GET `/invoices/{invoice}` | Invoice and receipt/allocations | None | Bearer |
| POST `/invoices/{invoice}/manual-payments` | Record cash or checked bank transfer | `amountMinor,method,reference,verificationReference?,reason` | Bearer, K, V; principal/accounts |
| POST `/invoices/payment-settings` | Configure approved test subaccount | `subaccount` | Bearer, K; principal |
| POST `/invoices/{invoice}/checkout` | Start hosted test checkout | `amountMinor,email` | Bearer, K; returns 202 |
| GET `/payment-intents/{intent}` | Checkout outcome/poll | None | Bearer |
| POST `/payment-intents/{intent}/verify` | Ask server to recheck provider | No body | Bearer; repeat-safe provider verification |
| GET `/reconciliation/exceptions` | Payment mismatch/surplus review list | None | Bearer; principal/accounts |

Invoice body:

```json
{
  "studentId": "<student-id>",
  "termId": "<term-id>",
  "description": "First term fees",
  "lines": [{"description":"Tuition","amountMinor":5000000,"quantity":1}],
  "dueOn": "2026-10-01",
  "partialAllowed": true
}
```

That line is ₦50,000. The backend calculates totals from the issued lines. Manual `method` is `CASH` or `BANK`; a bank entry needs an independently checked `verificationReference`. Provide a permanent unique `reference` and meaningful `reason`. A fresh idempotency key cannot bypass a previously used payment reference.

The app opens Paystack's returned hosted URL, then uses the returned intent ID to poll. Display pending/unknown/review states as returned. Only a server-verified allocation reduces the balance. `/reconciliation/exceptions` is not a complete settlement reconciliation feature, and receipt data is not a PDF download yet.

The provider alone sends `POST /webhooks/paystack` to the gateway base, with original raw JSON bytes and its `x-paystack-signature`. The mobile app does not call this webhook or possess the secret used to sign it. The backend verifies signature, deduplicates and independently verifies the transaction; a callback page cannot declare an invoice paid.

## Notifications, notices and complaints — Communication

| Method/path | Page/action | Input/filter | Headers |
| --- | --- | --- | --- |
| GET `/me/notifications` | Shared notification centre | `schoolId` required; `category?,unread?,cursor?,limit?` | Bearer; own notifications only |
| PUT `/me/notifications/{id}/read` | Mark own notification read | No body | Bearer |
| POST `/schools/{school}/notices` | Publish school-wide notice | `title,body` | Bearer, K; principal/office |
| GET `/schools/{school}/notices` | Role notice list | `cursor?,limit?` | Bearer; current school member |
| POST `/schools/{school}/complaints` | Parent creates private case | `studentId,category,subject,body` | Bearer, K; selected permitted child |
| GET `/schools/{school}/complaints` | Own/permitted case list | `cursor?,limit?` | Bearer; parent/principal/office/accounts scope |
| GET `/schools/{school}/complaints/{case}` | Private case detail | None | Bearer; permitted case participant/staff |
| POST `/schools/{school}/complaints/{case}/replies` | Case reply or private staff note | `body,internal` | Bearer, K, V |
| POST `/schools/{school}/complaints/{case}/close` | Reasoned closure | `reason` | Bearer, K, V; permitted staff |

Complaint categories: `GENERAL`, `ACADEMIC_RECORDS`, `ATTENDANCE`, `FEES`, `STAFF_CONDUCT`. The principal can handle all; office handles general/academic/attendance; accounts handles fees; staff conduct stays with the principal. Parents see only cases they opened and cannot create internal notes. Internal notes are excluded from family responses. Teachers do not receive case access automatically.

Notification targets such as `screenId`, `schoolId`, `childId` and `resourceId` are navigation hints. The target page still needs to load its authorised API resource; a notification cannot grant access. In-app visibility does not prove a push/email reached a device/mailbox.

## Internal endpoints

`/internal/schools`, `/internal/principal-activation`, `/internal/introspect`, `/internal/events`, `/internal/bootstrap-slip`, `/internal/audience`, `/internal/school-audience`, `/internal/student-context` and `/internal/response-target` are service/operator APIs. They are deliberately unavailable through the public gateway and must never appear in React Native screen code. Use `scripts/bootstrap.mjs` for the first development school.

## Pages with no connected API yet

Marks entry/review, published report cards/PDFs, promotion/graduation execution, assignments/submissions, message threads, attachments, CSV imports/exports, full fee plans/adjustments/refunds, staff role administration and full record editing still need endpoints. Do not point a screen at an invented path or use gateway routing alone as proof of implementation. Agree these additions with the lead and update this inventory as each workflow is built.
