# Implementation status — 4 November 2026

The expanded backend is on `backend/complete-workflows`. **The full agreed application is still unfinished.** The core workflows below are implemented and exercised. The completed screen review records exact remaining contracts in [SCREEN_CONNECTIONS.md](SCREEN_CONNECTIONS.md).

## Implemented and exercised

| Area | Current implementation |
| --- | --- |
| Shared access | Activation, login, password setup/change, verified-email and saved-code recovery, rotating opaque sessions, selected school/role/child, recent authentication, sessions/preferences |
| Isolation | Current server-checked permissions, school-scoped SQL and child/payer checks, four separately owned databases/logins, separate sender-to-receiver keys |
| Administration | Staff presets/onboarding and activated-principal protection, teacher eligibility/availability and handover, versioned settings/forms, student corrections/transfers/withdrawal, guardian contacts and permissions, scoped directory filters |
| Enrolment | Generated fresh admission numbers, explicit legacy migration, verified guardian links, repeat-safe provisioning and private pending-account activation slips |
| Planning | Curriculum alternatives and weekly timetable drafts/editing/cloning; dated publication, calendar revisions, exam schedules, normal/exam resource conflicts, optional-subject snapshots, activity-supervisor conflicts and a scoped daily schedule projection |
| Attendance | Assigned-teacher registers, complete submit, submitted-only overview, private correction requests/decisions, stale-source checks, office-reviewed alerts, official own-child history and saved absence follow-ups with private office notes |
| Assessment | Frozen subject scopes, subject-sheet entry/return/submission, class-lead remarks/five ratings, explicit class-package submit/return, principal publication, correction revisions, PDF reports and CSV broadsheets |
| Roster changes | Principal review/synchronisation after late admission, transfer or withdrawal; blank new marks, archived read-only rows, teacher confirmation, preserved issued snapshots and class-scoped report history |
| Promotion/graduation | Published-evidence preview, documented exceptions, stale-source checks, JSS3 pathways, ended-year principal confirmation, repeat-safe placements and SSS3 school completion |
| Learning/files | Class/subject materials and accepted revisions; app/physical assignments, teacher-recorded receipts, private feedback drafts/release, accepted learner history and protected encrypted PDF/JPG/PNG attachments |
| School documents | Verified admission copies, historical paper reports and staff credentials; principal-only historical publication, private revision drafts, per-revision visibility, archive and school-logo selection; scans never manufacture official marks |
| Fees | Immutable invoice lines, partial payments, cash/verified-bank records, test hosted checkout, signed webhook intake/verification, fee plans/batches, independent adjustment approval, confirmed refunds and invoice/receipt PDFs |
| Reconciliation | Statement/provider settlement review, gross/fee/net differences, permanent references, reviewed exceptions and finance overview |
| Communication | School/class/department notices, child-linked parent/class-lead/office threads, current-participant checks and private attachments |
| Complaints | Parent/walk-in/phone intake, category/owner limits, private notes, teaching-day response target, resolution/parent acceptance or review, visible history |
| Delivery | Leased SMTP/Expo workers, preference checks, retries/expiry, provider receipt handling, invalid-device suppression and truthful unconfigured states |
| Imports/history | Reviewed CSV previews/mapping, row correction, asynchronous resume without repeating successes, verified sibling groups, teacher assignments, independent opening-balance approval, scoped exports and service-owned activity history |

## Verified evidence

[GitHub Actions run 41828518009](https://github.com/ayodamolami4-bot/educore-backend/actions/runs/41828518009), code commit `5266c16c63caccd76cb57283ece2b7479c974f8b`, passed:

- **33 unit tests**, none skipped.
- **62 integration scenario groups**, using actual PostgreSQL and HTTP between all five Java programs.
- Controller/catalogue/page connection checks, all five Docker image builds and both container startup checks.

The latest scenarios exercise material publication/revision and optional-subject audiences, private files and sibling/school isolation, physical-work receipt and duplicate-safe revisions, private feedback release/stale-source checks, verified historical/admission/staff documents, school logos, teacher correction requests/declines, stale attendance approvals, official family history, reviewed correction alerts and private follow-up evidence/history. Existing enrolment, schedule, assessment, finance, communication and recovery checks also pass.

Paystack, SMTP and Expo are local test doubles. Actual provider accounts, external mailboxes/native devices and a hosted API have not been demonstrated. Local Docker startup is verified.

## Screen review and remaining work

The review covers **94 agreed pages**: **76 MAPPED**, **17 PARTIAL**, and **1 POLICY_DEFERRED**. MAPPED describes core API coverage; the frontend role screens still require implementation and connection by the frontend team. Every PARTIAL row states its missing planned controls.

Remaining contracts include:

1. Provisioning diagnostics/retry and pending-invitation status/revoke controls; active global accounts still use their private recovery proofs.
2. Existing session/term date editing and an active-session preference; subject lifecycle and a room catalogue.
3. Broader report/export controls, a school-wide checkout-attempt list, payment-settings reads/editor and the School Office fee-only read preset.
4. Staff delivery troubleshooting/retry controls. Background delivery workers already retry under leases and respect access/preferences.
5. Scope decisions called out in the screen map: the presentation school's grading policy is currently fixed; approved refunds are executed externally and confirmed with evidence, not sent automatically by this API.
6. An adopted policy for recovery when all private proofs are lost. SH09 is deliberately contact/help only; staff cannot reset an active global identity. Existing verified-email/saved-code recovery and failure boundaries are tested.
7. Actual provider/device validation, HTTPS hosting, backup/restore, operational monitoring and independent access/load/security review.

Tested code and the endpoint handoff are saved remotely. `main` still contains the earlier foundation; use the expanded branch for review and local development. Do not describe the whole backend or all 94 screens as complete.

## Handoff

- [Page-to-endpoint connections and precise gaps](SCREEN_CONNECTIONS.md)
- [API workflow guide](API.md)
- [All 256 implemented endpoints and 116 request record types](FRONTEND_ENDPOINTS.md)
- [OpenAPI request catalogue](openapi.json)
- [Endpoint CSV](ENDPOINT_CATALOGUE.csv)
- [Local setup](SETUP.md)

Internal service routes are excluded from the frontend catalogue. Official grades/money/promotion decisions stay on Java; frontend Models adapt response names, Presenters coordinate screen actions and Views render them.
