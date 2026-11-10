# EduCore frontend API — current backend checkpoint

Use **`http://localhost:8080/api/v1`** in local web development. The GitHub repository URL is source code, not a running API. A physical phone needs the development computer's reachable address.

The complete, source-derived list is in [FRONTEND_ENDPOINTS.md](FRONTEND_ENDPOINTS.md): **285 gateway operations and 126 catalogued request record types**. Import [openapi.json](openapi.json) into an API client, or filter [ENDPOINT_CATALOGUE.csv](ENDPOINT_CATALOGUE.csv). [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) identifies remaining work. Earlier proposal PDFs are not the runtime contract.

## Shared rules

- Protected requests send `Authorization: Bearer <accessToken>`. The shared app client manages access/refresh tokens. Tokens are opaque, not client-generated JWT claims.
- Select a returned school/role/child context using `PUT /me/context`. The URL's school must match that context. Parent requests also require the selected, currently permitted child.
- **K** means a UUID `Idempotency-Key`. Keep the same key, exact body and original version when retrying one action after a timeout. New actions use new keys.
- **V** means `If-Match: "v3"`, including quotation marks, using the relevant resource's returned version. Missing versions return 428; stale versions return 412. Reload, preserve the draft and review changes before creating a new command.
- JSON names and nested fields are listed in the request catalogue. Dates use `YYYY-MM-DD`; weekly lesson times use `HH:mm`; deadlines use ISO timestamps.
- Money is **integer kobo**: `500000` means NGN 5,000. Fractional kobo is rejected. The backend calculates invoice totals, balances, report grades and promotion recommendations.
- Cursor lists return `items,nextCursor,hasMore`. Pass the cursor unchanged; `limit` is capped at 100. Some bounded catalogues return `items,hasMore:false`.
- A 202/QUEUED/PENDING response means accepted work. Poll the returned job, onboarding resource or payment intent; a callback or timeout does not prove success.
- Errors use `application/problem+json`: `status,code,detail,requestId,retryable` and optional field errors. Handle 401 in shared sign-in, 403/404 as unavailable access, 409 as conflict, 412 as stale data, 422 as invalid input, 428 as missing version and 503 as a dependency/configuration problem.
- File content is uploaded separately as `application/octet-stream`. Never put a payment-provider secret, service key or database credential in the React Native app.

The admin interface contains three server presets: **PRINCIPAL**, **SCHOOL_OFFICE** and **ACCOUNTS**. Teacher, student and parent contexts use **TEACHER**, **STUDENT** and **PARENT**. There is no generic `ADMIN` API permission.

## Which frontend pages use which APIs

[SCREEN_CONNECTIONS.md](SCREEN_CONNECTIONS.md) maps all 94 agreed pages to current routes and marks each remaining planned control. The table here groups the main workflows. Frontend page coverage is not the same as an implemented React Native screen.

All paths below are relative to the base URL. Placeholder names stand for returned UUIDs.

| Interface/page group | Main APIs | Scope |
| --- | --- | --- |
| Shared sign-in, activation and recovery | `/auth/login`, `/auth/activation/review`, `/auth/password/setup`, `/auth/recovery/email`, `/auth/recovery/code/review` | Printed/private activation instructions; saved private code or verified email for recovery |
| Shared account and context picker | `/me`, `/me/contexts`, `/me/context`, `/me/password/change`, `/me/sessions` | Own account; only returned permitted school/role/child contexts |
| Shared notifications and preferences | `/me/notifications`, `/me/notifications/{notificationId}/delivery`, `/me/notification-preferences`, `/me/push-devices` | Own account; navigation targets do not grant resource access |
| Admin home and school settings | `/schools/{school}/dashboard`, `/settings`, `/metadata`, `/metadata-versions` | School-scoped; principal controls school/form revisions |
| Admin sessions, terms and calendar | `/academic-sessions`, `/terms`, `/calendar`, `/calendar-plans` | Calendar drafts are reviewed and published; dates belong to the school's own session |
| Admin student directory and enrolment | `/students`, `/students/{studentId}`, `/students/{studentId}/transfers`, `/students/{studentId}/withdrawal` | Principal/office; family reads require the current child relationship |
| Admin guardian directory and permissions | `/guardians`, `/guardians/{guardianId}`, `/students/{studentId}/guardians` | School contacts and link permissions; does not change another school's/global sign-in identity |
| Admin staff and teacher management | `/staff`, `/teachers`, `/staff/{staffId}/teaching-scope`, `/teaching-assignments` | Principal manages administrative access; office manages permitted teacher records |
| Admin classes, subjects and choices | `/classes`, `/classes/{classId}/workspace`, `/subjects`, `/curricula`, `/students/{studentId}/subject-choices` | Separate eligibility, actual teaching assignments, required subjects and alternatives |
| Admin timetable editor and review | `/timetable-plans`, `/timetable-plans/{planId}/lessons`, `/review`, `/publish` | School staff draft; principal publishes; published versions are retained |
| Teacher/student/parent timetable | `/schools/{school}/timetable`, `/my-classes`, `/terms/{termId}/activities` | Actual teaching scope or selected child's placement |
| Teacher daily register | `/attendance/registers`, `/{registerId}/marks`, `/{registerId}/submit` | Assigned class attendance teacher |
| Admin attendance review | `/attendance/overview`, `/attendance/follow-ups`, register corrections and absence alerts | Submitted registers only; missing registers are not absences |
| Family attendance | `/students/{studentId}/attendance` | Own/selected permitted child |
| Admin examinations | `/exam-schedules`, events, review and publication | Dated exam schedules; see current scheduling limitations in implementation status |
| Teacher marks and subject reports | `/assessment-cycles`, `/{cycleId}/sheets/{assignmentId}`, mark-save and submit actions | Only the responsible subject teacher writes marks |
| Class-teacher review and remarks | Cycle review, remarks and sheet-return actions | Class-teacher duty is separate from teaching a subject in that class |
| Principal report approval | `/assessment-cycles/{cycleId}/publish`, `/reopen` | Only a class-lead-submitted REVIEW package; corrections preserve old publications |
| Student/parent report cards | `/students/{studentId}/reports`, `/reports/{reportId}`, report PDF | Published versions only; authorised child |
| Admin promotion/graduation | `/promotion-batches`, row decisions, confirm and CSV export | Three published terms, source checks, JSS3 pathway decisions and principal approval |
| Assignments | `/assignments`, assignment publication/change actions | Actual subject teacher creates; eligible learners read after publication |
| Student submission | Assignment `/my-submission`, `/submit` | Student submits own work; parents cannot submit for the child |
| Teacher feedback and family progress | Assignment submissions, feedback and history | Draft content stays private; accepted responses and reviews are preserved |
| Fees, plans and bulk invoices | `/fee-plans`, `/fee-batches`, `/invoices` | Principal/accounts; parent reads/payments require payer access |
| Parent payment and receipts | Invoice checkout, `/payment-intents/{intentId}`, `/payments/{paymentId}/receipt` | Hosted Paystack test checkout or school-confirmed cash/bank records |
| Credits, discounts and refunds | Invoice adjustments, adjustment decisions and refund confirmation | Another principal approves a financial request; proof is required for confirmed refunds |
| Accounts reconciliation | `/reconciliation/statements`, `/paystack-sync`, settlements, exceptions and overview | Settlement matching is separate from payment confirmation |
| Notices | Notice draft/detail/edit, publish and archive | School, class or department audience; publication controls attachment visibility |
| Parent/class-teacher/office conversations | `/threads`, contacts, messages, read and close actions | Child-linked current participants; former class teachers lose access |
| Complaints | `/complaints`, replies, owner, status and history | Parent or authorised walk-in/phone intake; category and owner restrictions |
| Imports and exports | `/imports`, template/preview/row-correction/confirm/error CSV; `/invoices/opening-balances`; directory/report/invoice exports | Preview first; stable row references; successful rows are not imported twice |
| Activity history | `/activity/academics`, `/activity/finance`, `/activity/communication`, `/activity/identity` | Each service owns its history; principal-only access/security and full communication history |

The full catalogue supplies the exact complete path for suffixes abbreviated in this table.

## Registration and account handover

Fresh enrolment sends `SchoolService.EnrolInput` to `POST /schools/{school}/students` with K. Provide `fullName,dateOfBirth,sex,classId,startsOn,metadataVersion,fields,guardian`, and a department for SSS. Fresh admissions receive a generated admission number.

A new guardian needs `fullName,phone,relationship,relationshipVerified:true,verificationReference`; email is optional. Use an explicitly verified `existingGuardianId` from that school to link a sibling, or a verified `existingLoginId` when linking the person's existing global login. Phone/email matches never silently merge identities.

Existing same-school records use `admissionType:"LEGACY_MIGRATION"`, their preserved `admissionNumber` and `legacyReference`; principal review is required. New external SSS3 entry is restricted for the configured 2026/27 cohort, while internal SSS2 promotion is a separate workflow.

The 202 response contains the student, guardian and onboarding event IDs. Account provisioning is asynchronous. Authorised staff request a private activation slip at `/schools/{school}/onboarding/{type}/{subjectId}/activation-slips`. It is for pending accounts; it does not reset an existing parent's password or private recovery code.

School guardian-contact edits do not change the account's global recovery identity. Existing guardian-link permission changes require that **link's** If-Match version.

## Timetable, attendance and academic results

Normal lessons use 30/60-minute periods inside 08:00–13:00, with 11:30–12:00 break, optional Wednesday PT and Friday 12:00–13:00 sports. Timetable drafts use actual teaching assignments and check teacher, class and room conflicts. Lesson edits/removal use the **plan version**, not the individual lesson version. Clone a published plan using `sourcePlanId` to create a revision.

For daily attendance, open the dated class register, save its complete marks, then submit. Admin absence counts use submitted registers. A missing register stays incomplete coverage. Office corrections and parent absence alerts require a reviewed reason. `/attendance/follow-ups` flags three confirmed absences in ten teaching days.

Assessment flow is: prepare cycle → principal opens window → subject teacher saves/submits sheet → class teacher reviews, returns incomplete sheets and adds remarks/five ratings → class teacher submits the complete package with `POST /assessment-cycles/{cycleId}/submit-review` → principal publishes. A class teacher who also teaches a subject uses the normal subject-teacher action for that own sheet.

CA1 is /20, CA2 /20 and examination /60, with at most one decimal place. Pending make-up, evidenced absent-zero and actual zero are distinct. Teachers cannot silently replace published results. Reopen with a reason, correct, review and publish a new report revision. Earlier PDFs/snapshots remain available.

A submitted class package is `REVIEW` and locked for mark/remark edits. Principal/School Office can return it with a reason using `POST /assessment-cycles/{cycleId}/return-review`. Returning a subject sheet also reopens a submitted package; its class lead must submit the package again before principal publication.

Use the sheet version for marks/submission, the student's remark version for remarks, and the latest cycle version for publication. Initial remarks use version 1 when no remark exists. Reload review after other teachers change sheets.

The backend determines promotion recommendations from all three published terms and blocks stale previews. Confirmed outcomes create next-session placements; school fee debt does not change an academic recommendation. JSS3 requires an explicit reviewed SSS1 pathway. SSS3 school completion is not a national examination certificate. External BECE/WAEC/NECO results are recorded as issued.

## Assignment and file flow

1. Create file metadata with purpose, filename, media type, exact size and SHA-256. Use returned ID/version to PUT raw bytes to `/files/{fileId}/content`.
2. Attach ready file IDs when creating the assignment, saving a student response, or sending a permitted communication.
3. Subject teacher drafts/publishes the assignment. Families see published eligible work.
4. Student saves a private draft and submits it. Submission retries use the original K/V.
5. Teacher grades within the assignment maximum, or returns the accepted response with a reason and future extension. Earlier accepted responses and reviews stay in history.

Files support PDF/JPG/PNG up to 10 MB each, at most five attachments per action, with configured school/account quotas. Download through the authorised API; there are no permanent public file URLs. Content validation and encryption are implemented; malware-scanning integration is not claimed.

Assignment feedback uses the **submission version**. Parents can read accepted work and feedback but cannot impersonate student submission.

## Fees and reconciliation

All invoice amounts are kobo, and issued lines are preserved. Approved credits/discounts and confirmed refunds are separate records. `paidMinor` is net of allocated refunds; `grossPaidMinor`, `creditMinor`, `refundedMinor`, `chargeMinor`, `creditBalanceMinor` and `balanceMinor` explain the ledger.

A fee plan is drafted and published, then a batch preview lists eligible students and previously issued invoices. Confirm the reviewed batch. A permanent plan/student/term reference prevents a second invoice when someone uploads or retries again.

Cash/bank confirmation needs a permanent unique reference, independently checked evidence and a reason. Hosted checkout returns a Paystack test URL and intent ID. The app polls intent status; signed webhooks are provider-to-server messages, not frontend actions. Only authoritative server verification allocates money.

Accounts staff can request an adjustment; a different principal approves. For a refund, process the approved disbursement through the school's bank/provider process and then confirm it with evidence. Paystack confirmation fetches the refund and original transaction and requires the exact processed NGN test refund. Approval alone does not mark money returned.

Reconciliation imports a bank statement or fetches the configured school's Paystack settlement data, comparing permanent references, gross, fees and net amounts. Unknown/mismatched entries remain exceptions. Evidence-based review is recorded; it does not rewrite the underlying payment. PDFs are available for invoices and confirmed payment receipts.

**Only Paystack TEST keys are accepted in this build.** No live payment or refund has been performed.

## Communications and delivery

Notice creation makes a **draft** using `title,body,scope,classId?,department?,fileIds?`. Publish after review. A published notice is not silently overwritten.

Threads target `CLASS_TEACHER` or `OFFICE`, belong to a child and verified parent, and check current participation on every read/send/download. Messages are plain text with permitted ready attachments. Close a finished conversation with a reason.

Cases use `NEW → IN_REVIEW → WAITING_FOR_PARENT → RESOLVED → CLOSED`. Parents can accept a resolution or explicitly request a review. No inactivity rule automatically closes a case. Accounts handles fees; office handles general/attendance/academic records; staff conduct is principal-only. Current owner or principal performs staff work. Internal notes and their files stay out of parent responses.

Use `/complaints/{caseId}/status` actions `START_REVIEW,WAIT_FOR_PARENT,RESOLVE,CLOSE` for authorised staff and `ACCEPT,REQUEST_REVIEW` for parents. A reason is required. The legacy `/close` action now also requires an already resolved case.

In-app notifications are stored first. Configured email/push workers use leases, retries, expiry and current preferences. SMTP and Expo were tested with local provider doubles. Expo tickets/receipts do not prove a person read the notification. Unconfigured delivery remains waiting; it is never fake-sent. Real device/provider validation remains separate.

## CSV import flow

Academic imports accept `STUDENTS` or `TEACHERS`, a session, CSV text and optional mapping from target field name to CSV header. Use the template endpoint for exact columns. Maximum 1 MB / 1,000 rows; each row needs a distinct stable `row_reference`.

Preview does not enrol anyone. Correct invalid rows, review duplicate candidates and verified relationships, then confirm with the **job version**. Confirmation returns 202 and processing is asynchronous. Poll the job's rows. Correct failed rows using their **row versions** and resume; successful rows remain immutable.

Students use readable class keys such as `JSS1-A`. Teacher lists use semicolons, and assignments use `JSS1-A/ENGLISH/4;JSS1-B/ENGLISH/4`, with an optional fourth department component. Use a consistent `guardian_reference` and identical verified parent details to link siblings within one import.

Opening balances are separate Finance previews at `/invoices/opening-balances`. Their CSV fields are `row_reference,admission_number,amount_minor,due_on,description,evidence_reference`. Accounts prepares; another principal confirms. The permanent source reference prevents duplicate charges.

## Response mapping and frontend MVP

Requests use the exact camelCase record names shown in the catalogue. Some detail responses currently retain snake_case columns: curriculum details, the nested `class` in a workspace, fee-plan details and adjustment details. Map these explicitly in the frontend Model; do not assume every database-shaped response is camelCase. Most list rows and official report/invoice responses already use explicit wire names.

The **Model** calls these endpoints and maps wire data; the **Presenter** coordinates loading, errors, selection and actions; the **View** renders the page. Keep access/refresh and generic API handling in the shared frontend area. Official calculations remain on Java.

Internal service endpoints and their keys never belong in frontend code. Use the protected gateway APIs above. See the implementation status before treating a prototype page or declared database table as a completed workflow.

## Dated schedules, roster changes and directory filters

`GET /schedule/day?on=2026-11-03` combines the effective weekly timetable, latest published exam revision and fixed activities for that date. Optional `classId` is allowed only inside the caller's scope. Families see their selected child's dated class, department and chosen alternatives; a teacher sees their continuing lessons, invigilation and supervised activities. Closed calendar dates return `teachingDay:false` and no activities.

An exam removes only the affected lesson segment for that date; it does not edit the published weekly plan. Publication checks both directions: an invigilator/room cannot overlap a normal lesson unless the affected lesson's pupils are fully covered by exams. Alternative subjects in the same choice group may run together with separate teachers/rooms. Activity-supervisor duties also block overlapping invigilation. Older draft revisions cannot replace newer already-published revisions.

Wednesday PT settings select the default for new timetable drafts. A settings response with `timetableReviewRequired:true` means the principal must publish a reviewed timetable revision. Existing published weekly plans retain their PT setting; cloning rechecks fixed-activity conflicts. The daily view uses the effective plan's setting.

Late admission, class-arm transfer or withdrawal can change a prepared assessment scope. Review returns `rosterSyncRequired` and `rosterIssues`, and publication is blocked. Principal reviews the changes and sends `POST /assessment-cycles/{cycleId}/roster/sync` with `{ "reason": "Reviewed the dated admission or placement evidence" }`, K and the latest cycle V. A published cycle must first be reopened through its correction action.

Synchronisation adds eligible new rows with blank marks, preserves existing marks, archives departed rows with a reason and returns active sheets for teacher review. A submitted REVIEW package returns to OPEN when its roster, class lead or subject teacher changes; the current teachers must review and submit again. Historical sheet rows return `active:false` and are read-only. Empty optional-subject sheets are inactive and do not require an impossible empty submission. After teacher confirmation and class-lead package submission, principal can publish a new revision. No marks are silently copied to another class, and old issued report snapshots remain unchanged. A class lead's report access is limited to their own class publications.

Directory parameters are optional and combine with cursor/limit pagination:

| Directory | Filters |
| --- | --- |
| `GET /staff` | `search`, `role`, `active` |
| `GET /teachers` | `search`, `level`, `subjectId`, `classId`, `active` |
| `GET /guardians` | `search`, `childId`, `activeLink`; search also matches child name/admission number |
| `GET /students` | `search`, `classId` |
| `GET /teaching-assignments` | `classId`, `includeInactive=false`; only office staff can include inactive history |

Teacher directory rows contain teachers only. Eligibility filters search qualifications; actual class filters search active teaching assignments. The app must not treat eligibility as a work assignment. Searches are limited to 80 characters and remain school-scoped. School guardian contact edits never change the parent's global login contact. Another principal must have activated their account before the last activated principal can be removed.

## Core coverage and release boundaries

[SCREEN_CONNECTIONS.md](SCREEN_CONNECTIONS.md) maps all 94 pages: 93 core contracts are mapped and SH09 retains its help-only policy boundary. Provisioning, invitations, setup/resource administration, supported report/export types and delivery operations have the explicit workflows below. The mapping describes backend coverage, not completed frontend screens or production/provider validation. Read [REVIEW_HANDOVER.md](REVIEW_HANDOVER.md) for the account, device and review steps.

SH09 remains contact/help only: no staff-assisted global password reset is adopted. Failed or missing recovery proofs cannot consume an active account's school activation slip or reveal whether an account has verified email. Actual provider/device validation and deployment remain separate from local test doubles.

## Materials, physical work and school documents

Materials are separate from homework. A subject teacher uses `POST /materials` with an actual `teachingAssignmentId`, `termId`, `title`, text `body` and optional ready `fileIds`. Upload attachments with purpose `MATERIAL` and their class ID. `GET /materials` accepts class/subject/term filters and pagination. `PUT /materials/{id}` saves a revision draft; `POST /materials/{id}/publish` releases it. Families continue to receive the previous published snapshot while a draft is edited. `/history` returns accepted revisions; `/archive` requires a reason and removes family access. Current class, department, subject choices and school/child permissions are checked on reads and downloads.

Assignment input now accepts `submissionMode: APP | PHYSICAL`, defaulting to APP for existing clients. A physical assignment cannot accept an app response. Its assigned teacher records `POST /assignments/{assignment}/submissions/{student}/physical-receipt` with `receivedOn` (Africa/Lagos school date), an optional `note`, a required evidence `reason` and optional `fileIds`. Use purpose `PHYSICAL_EVIDENCE` for child-linked receipt photographs/scans. Initial V is 1; later V is the submission version. Receipts are accepted revisions, not editable dates. Return the work through its existing feedback action before recording another receipt. Late-on-date records are labelled; they do not automatically subtract marks.

A teacher can prepare private feedback with `PUT /assignments/{assignment}/submissions/{student}/review-draft` using the normal feedback fields (GRADE/RETURN, score, text, correction/return reason and return deadline). GET on that path is teacher-only. Use the review draft's version, initially 1, then `POST .../review-draft/release` to release it. The draft records the accepted submission version/revision it reviewed. A changed response or released grade makes an old draft stale (412); save a fresh review. Student/parent reads and accepted history never include the private draft. The existing direct feedback endpoint remains an immediate release action.

School staff use `/documents` for verified admission copies, historical paper reports and teacher credentials. The record specifies exactly one `studentId` or `staffId`, its `kind`, title, ready `fileId`, original issue date/issuer, verification reference, visibility and intake/revision reason. HISTORICAL_REPORT also needs its original session and term 1–3. A scanned paper copy is labelled VERIFIED_DOCUMENT_COPY and `usedInAutomaticGrading:false`; attaching it does not create marks or silently satisfy promotion evidence.

- Upload pupil documents with purpose STUDENT_DOCUMENT and their student ID. Upload staff credentials with purpose STAFF_DOCUMENT and their staff ID.
- Principal/School Office can prepare and edit document drafts. Historical report publication requires Principal; admission/staff documents can be verified by School Office. PUT saves a next draft; POST `/{id}/publish` releases a version; `/{id}/history` retains accepted copies and `/{id}/archive` requires a reason.
- Student/parent visibility requires the published document's `familyVisible:true` and current learning permission for that child. A teacher's credential requires `staffVisible:true` and that exact teacher profile. Other teachers cannot read it. Only the current class lead can read a pupil's published historical reports; admission evidence remains private to authorised office/family scopes.
- A raw upload's familyVisible flag is publication intent. It does not publish an unattached office document. Current record visibility and each accepted revision's visibility are checked again for downloads and history. Owner access is also restricted to the upload's role/purpose.

`GET /documents` supports `studentId`, `staffId`, `kind`, cursor and limit. Families must choose their permitted pupil. The school's logo uses a ready image upload with purpose SCHOOL_ASSET; Principal selects `logoFileId` in PUT `/settings`. Omitting it preserves the existing logo, and `clearLogo:true` clears it. Settings and shared metadata expose the selected logo file ID. No public database/file URL is needed.

## Attendance correction requests and saved follow-ups

The class attendance teacher can list their dated registers with `GET /attendance/registers` (class/date/state filters). Teachers keep submitted registers locked. `POST /attendance/registers/{register}/correction-requests` proposes a pupil status/reason using the register's V; it creates a PENDING request without changing official attendance. Duplicate pending requests for the same register/pupil are rejected. Families cannot read these private requests.

Use `/attendance/correction-requests` to list the permitted queue, `/{id}` to view, `/{id}/history` for its history and PUT `/{id}` to recheck a pending request against a new `registerVersion`. Only its requesting teacher can edit or cancel it, and current class duty is checked for changes. Principal/School Office uses POST `/{id}/decision` with APPROVE or DECLINE, a reason and the request's V. Approval requires the original register version to remain current; stale source returns 412 until the teacher rechecks it. Decline/cancel retain history and leave the register unchanged. Approval and the official correction commit together.

The office's approval/correction reason is the reason shown with official attendance. The teacher's private request explanation is not copied into family history or push/email previews. `GET /attendance/registers/{register}/correction-history?studentId=...` returns official corrections only; families must select their permitted child. Office/Principal can review selected accepted corrections with POST `.../correction-alerts`, the current register V, `studentIds` and a review reason. Each accepted correction is queued once for that child even when the action is retried. This is separate from the existing reviewed absence-alert action.

`GET /attendance/follow-ups` remains the statistical flag: three confirmed absences in the last ten teaching days, including missing-register coverage. It also returns the latest linked follow-up case. Missing registers never become absences.

Principal/School Office opens POST `/attendance/follow-up-cases` with a flagged `studentId`, reviewed `onDate`, optional active office/principal `ownerId`, a private office `note` and a reason. At most one active follow-up exists per pupil. GET list/detail supplies original evidence, current flag and private office entries. PATCH `/{id}` uses V to record CONTACTED, MONITORING, RESOLVED or a reasoned reopening to OPEN, preserving all entries and original counts. Later corrections can clear the current flag without silently deleting or resolving the case. These internal contact notes are not available to teachers or families.


## Finance administration and office fee status

School Office uses `GET /invoices/fee-status?studentId=...` for AD04. `studentId` is required; optional `termId`, cursor and limit scope the result. The school and pupil/term are checked with Academics. The response has pupil identity, NGN, school date, readOnly:true, invoice status/balance/due-date rows and full-filter summary counts/outstanding balance. Pagination changes the rows, not the full-filter summary. It does not include invoice lines, payment references, payer emails, cash/bank evidence or adjustment actions. Principal/Accounts can also read this summary. Parents use their existing own-child invoice APIs; teachers/students cannot use the office summary. School Office still cannot use full invoice/payment detail, receipt or money-changing endpoints.

`GET /invoices/payment-settings` is Principal/Accounts only. It reports school configuration, provider configuration, enabled state, ready-for-test-checkout, current version and the saved subaccount. Missing school setup returns configured:false, ready:false and no saved version; it does not fabricate a saved configuration. Provider readiness is only configuration presence, not bank/subaccount ownership verification (`providerOwnershipValidation:NOT_PERFORMED_BY_THIS_API`). Server Paystack keys are never returned.

Principal creates initial settings using the existing POST with `{subaccount}` and K. Repeating that K/body returns its original result. A new POST cannot overwrite saved settings (409); use PUT with `{subaccount,checkoutEnabled,reason}`, K and current V. Accounts can read configuration/history but cannot create/change it. `GET .../history?beforeVersion=...&limit=...` supplies append-only revisions; nextCursor is the next beforeVersion value. MIGRATION_BASELINE marks a configuration observed during migration, with no invented earlier actor/change date.

Subaccount references remain bound to their school even after a configuration change. A reference reserved for another school is unavailable (409), including concurrent claims. Each new checkout retains the subaccount/settings version and the school pupil/payer names observed at initialization. Those snapshots do not change when current school settings or profiles change. Legacy intents without a recorded snapshot remain explicitly snapshotAvailable:false; names/settings are not reconstructed. Disabling checkout stops new attempts; existing attempts remain readable/verifiable, and an idempotent retry still returns its original accepted reference.

AD24 uses `GET /payment-intents` for Principal/Accounts. Filters: studentId, invoiceId, exact reference, state, from, to, cursor and limit. Dates are inclusive Africa/Lagos school dates, defaulting to the last 30 days through today, with at most a 366-day difference. Pages are ordered by createdAt/id descending and use the returned UUID nextCursor. Student/invoice/cursor lookup is school-scoped. States are PENDING, INIT_UNKNOWN, CONFIRMED, FAILED and REQUIRES_REVIEW. The response includes method PAYSTACK, invoice/pupil/term IDs, amount/currency, verification attempts/time, version and initialization/settlement snapshots. It omits payer email and checkout authorization URL. Parents retain their permitted per-intent status endpoint; they cannot list the school's attempts.

`POST /reconciliation/paystack-sync` now accepts optional `subaccount` with from/to. Omit it for the current configuration, or choose a historical reference from the school's knownSubaccounts/history. Only a permanently school-bound reference can be used; another school's reference returns 404. This permits reconciliation of older payments after changing the current settlement destination.

A malformed provider initialization response is an unknown outcome, not proof of failure. The original payment reference is retained as INIT_UNKNOWN. Retrying the original K/body cannot initialize another transaction; authoritative verification can later confirm that same reference. These flows remain Paystack test-only and have no automatic refund-money execution.

## Remaining administration controls (review branch)

- `GET /provisioning/{type}/{subject}` returns school-scoped Identity outbox state, bounded event history, safe error codes and retry versions. The asynchronous index can briefly report `indexingPending`. It returns no event payload or private credentials. `POST .../retry` takes `eventId`, `reason`, `If-Match` and `Idempotency-Key`; it requeues the original failed event, rejects an active lease, and never recreates the enrolment. Principal manages administrator records; School Office manages pupil/guardian/teacher records.
- `GET /onboarding/{type}/{subject}/invitations` returns the linked account state and a paginated invitation history without proof values. Existing `POST .../activation-slips` returns a new `invitationId` and version as well as private initial-access details. Replacement invalidates previous activation codes **and an activation flow already reviewed on a phone**. `POST .../invitations/{id}/revoke` takes a reason and version; it invalidates all proofs for that invitation. Active global accounts cannot be reset, replaced or disabled by these controls. School Office cannot inspect/manage administrator invitations.
- `PATCH /academic-sessions/{id}` and `PATCH /terms/{id}` take reviewed dates, a reason, `If-Match` and a stable command key. Session edits must retain child term/enrolment/calendar dates. Terms with dated timetable/exam/assessment/attendance work cannot change boundaries; use a published calendar revision for closures/exceptions. Term numbering/session ownership stay fixed.
- Principal `PUT /active-academic-session` takes `sessionId`, `reason` and the **school settings** version. `activeSessionId` in settings/metadata is a display default, not an override of dated placements or school authorization.
- `PATCH /subjects/{id}` takes `name`, `active`, reason and version. Subject codes/core identity remain stable; current/future assigned or curriculum subjects cannot be retired. New assignments/curricula/qualifications require active subjects.
- `GET /rooms` lists room IDs, stable uppercase codes, display names, capacity, active state and versions with active/cursor filters. `POST /rooms` and `PATCH /rooms/{id}` take code/name/capacity/active/reason. Codes are immutable. Current/future room references block deactivation; new saves reject inactive rooms. Legacy room text is indexed and new text entry creates a matching catalogue item; existing published labels are retained.

These controls are included in the verified real-PostgreSQL/container checkpoint recorded in IMPLEMENTATION_STATUS.md.

## Reviewed provisioning repair

`POST /provisioning/{type}/{subject}/replacement` accepts the failed `eventId`, `mode` (`CREATE_NEW` or `LINK_EXISTING`), exact `existingLoginId` for linking, `relationshipVerified`, `verificationReference`, `reason`, `If-Match` and `Idempotency-Key`. It repairs only initial STAFF/GUARDIAN account matching. Student, guardian and staff IDs remain unchanged.

Identity serializes cancellation against processing the original event. Already-applied outcomes or already-provisioned subjects cannot be rematched. A cancelled original can never apply later, even if its sender retries after a timeout. Academics preserves the original encrypted payload, records the replacement event and updates the pending tracking reference. A connection failure between cancellation and the local replacement commit is recoverable by retrying the same reviewed command. History labels the old event `SUPERSEDED`; normal retry cannot resurrect it. This is not a global password-reset operation.

## Delivery operations

- `GET /delivery/notifications` supports category, resourceId, childId, failedOnly and cursor filters. Principal sees authorized school notification metadata; School Office sees school/learning, permitted complaint categories and office threads; Accounts sees fees and fee cases.
- `GET /delivery/notifications/{id}` returns preparation, push and notification-email states, safe error codes, a separate delivery-command version and the last 20 retry records. It excludes bodies, addresses, tokens and authentication mail. In-app `readAt` is separate from provider confirmation.
- `POST .../{id}/retry` accepts channel `DISPATCH`, `PUSH` or `EMAIL`, a specific deliveryId for push/email, reason, `If-Match` and a stable command key. It rejects active leases, successful sends, outstanding provider receipts, expired windows and unregistered/removed devices. Case retries require the current owner or Principal. Current recipient access/preferences and the original device/email are checked again; workers repeat eligibility checks. Queued is not delivered. An unconfirmed transport outcome can still have reached an external provider; retries do not promise exactly-once external delivery.
- `GET /notices/{id}/delivery-summary` returns recipient/read counts and grouped delivery states for Principal/School Office, without exposing private recipient contacts.

## Report catalogues and bounded downloads

Read `GET /school-reports/catalogue`, `GET /reconciliation/reports/catalogue`, and `GET /complaints/reports/catalogue` in the permitted staff preset. Each service reads only its own database. All listed report endpoints take `format=JSON|CSV|PDF` (JSON by default), recheck current access on every request and return no-store responses. JSON generation is synchronous and returns `state: READY`; refreshing the same request regenerates the view. There is no arbitrary report-query builder or background export-job identity.

`GET /school-reports/{kind}` supports:

- ENROLMENTS: sessionId, termId, classId, studentId, department and overlap dates.
- ATTENDANCE: the same scope; a term or from/to dates is required. Rows represent dated enrolments on configured teaching days; only submitted rows supply attendance. NOT_RECORDED is never absence.
- TEACHING_ASSIGNMENTS: session, term, class, department and session-overlap dates.
- ASSESSMENT_COMPLETENESS: session, term, class, cycleId and term-overlap dates; workflow counts do not publish marks.
- PUBLISHED_BROADSHEET: cycleId plus optional pupil/department scope. It uses the latest **published snapshot** per pupil, including during a correction in progress. Teacher access requires the current class-lead duty. Totals, averages and grades come from the accepted snapshot; no rank is invented.
- SUBJECT_MARKS: cycleId and assignmentId only; Principal/Office, the assigned subject teacher or class lead can export their permitted working sheet. Draft/submitted working data is labelled and never becomes a family report.

`GET /reconciliation/reports/{kind}` supplies INVOICE_BALANCES (due dates), CONFIRMED_PAYMENTS (confirmation dates), REFUNDS (request dates) and SETTLEMENTS (import dates). Term/student filters apply to the first three; source applies to payments/settlements. Settlement batches may span pupils/terms and reject those filters. Amount columns ending Kobo are integer NGN kobo, calculated on Java/SQL. School Office/family roles cannot use these staff finance exports.

`GET /complaints/reports/{kind}` supplies BACKLOG (all cases not CLOSED, including resolutions awaiting acceptance) and CASE_REGISTER. Category/state/ownerId/from/to filters remain within preset limits: Accounts receives FEES; Office receives GENERAL/ATTENDANCE/ACADEMIC_RECORDS; Principal has the authorized school-wide view. No message body or internal note is exported. Response deadlines remain teaching-day evidence; elapsed hours are a separate description.

All new reports reject more than 1,000 rows instead of silently truncating; use narrower filters. Ordered paired dates span at most 367 days. Existing specific report-card and promotion exports retain their own routes. These are supported report types and formats, not a promise of every possible custom analysis.

## Adopted grading policy approval

`GET /grading-policy` returns the agreed 20/20/60, one-decimal, A-F, no-ranking policy. Weights/bands stay read-only because they are the adopted presentation-school decision. `POST /assessment-cycles/{id}/approve-policy` accepts policyVersion 1, reason, cycle version and a stable command key; Principal can explicitly approve a DRAFT cycle before mark entry. Opening mark entry also records Principal approval when no earlier approval exists, preserving existing clients. The policy is frozen on the cycle and old publications retain it; approval does not rewrite marks, reports or old approval history. Legacy opened cycles receive no invented historical approval actor.
