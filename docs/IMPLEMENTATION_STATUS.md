# Implementation status — 2 November 2026

The backend has progressed beyond the original foundation. The saved code on `backend/complete-workflows` implements the workflows below. **The complete agreed application is not yet finished.** Final scheduling/assessment edge cases, screen-by-screen contract review and release work remain.

## Implemented and exercised

| Area | Current implementation |
| --- | --- |
| Shared access | Activation, login, password setup/change, verified-email and saved-code recovery, rotating opaque sessions, allowed school/role/child selection, recent reauthentication, sessions/preferences |
| School isolation | Server-checked current context, school-scoped SQL and child/payer checks, separate databases/logins, distinct sender-to-receiver keys, no trusted client identity headers |
| Administration | Office/accounts onboarding, staff presets and lifecycle checks, teacher eligibility/availability and handover, school contacts/forms, student edits/transfers/withdrawals, guardian contacts and versioned permissions |
| Enrolment | Generated fresh admission numbers, explicit legacy migration, verified guardian linking, outbox account provisioning, private pending-account activation slips |
| Planning | Versioned curriculum choices; weekly timetable drafts, editing/removal/cloning, required-period review and publication; calendar draft/publication; dated examination schedules with intra-exam conflicts |
| Attendance | Assigned-teacher registers, drafts/submission, submitted-only office overview and corrections, reviewed absence alerts, own-child history, three-in-ten teaching-day follow-up query |
| Assessment/reporting | Subject sheets and submission/return; class-teacher remarks/five ratings; principal publication and correction revisions; child-scoped report PDFs and class CSV broadsheets |
| Promotion/graduation | Evidence-based preview, reasoned exceptions, stale-source checks, JSS3 pathways, principal confirmation, repeat-safe placement and SSS3 school completion; fees remain separate |
| Learning work/files | Assignment drafts/publication, private student drafts, accepted submission revisions, return/extension/grading, family visibility, private encrypted PDF/JPG/PNG uploads/downloads and attachment scope checks |
| Fees | Immutable invoice lines, partial payments, cash/verified-bank records, hosted test checkout, signed webhooks/authoritative verification, fee plans and repeat-safe bulk issue, independently approved adjustments, verified refund confirmation, PDF invoices/receipts |
| Reconciliation | Bank statements and provider settlement matching, gross/fee/net differences, permanent references, exception review and financial overview |
| Communication | Scoped notice drafts/publication, child-linked parent/class-teacher/office conversations, attachments, read/close actions, current-participant checks |
| Complaints | Parent/walk-in/phone intake, category/owner restrictions, private notes/files, teaching-day first-response target, resolution and parent acceptance/review, history |
| Delivery | Leased SMTP and Expo push preparation/delivery/receipt workers, current preferences, retries, expiry, invalid-device suppression and visible unconfigured states |
| Imports/exports/history | Reviewed CSV previews and mapping, row correction, asynchronous resume without repeating successes, verified sibling groups, teacher assignments, independently reviewed opening balances, scoped CSV/PDF exports and service-owned activity history |

## Verified evidence

[GitHub Actions run 41453457869](https://github.com/ayodamolami4-bot/educore-backend/actions/runs/41453457869), source commit `3f7bbd716932b3a550ab2877156943130a2b1148`, passed:

- **33 unit tests**, with none skipped.
- **42 integration scenario groups**, using real PostgreSQL and HTTP between all five Java programs.
- All five Docker image builds and both container startup checks: school bootstrap/activation/gateway sign-in, and truthful unconfigured-email behaviour.

Paystack, SMTP and Expo were local test doubles. No live money moved, no external mailbox/device delivery was demonstrated, and no cloud API or app-store build was deployed.

## Remaining work

1. Finish the dated interaction between normal timetables, examinations, departments and optional subject groups. Current weekly and examination schedules validate their own conflicts; do not claim complete cross-schedule replacement/conflict handling.
2. Finish assessment-roster handling for late admissions/transfers after a cycle snapshot, then test those changes and the school-settings/activity/follow-up edge cases. Source-result corrections already flag affected promotion outcomes for review.
3. Review every agreed role screen against the implemented contract, including remaining directory filters and consistent response adapters. The source-derived request catalogue is supplied; it is not proof that 94 frontend screens are connected.
4. Implement the assisted-recovery path when all private proofs are lost, and complete independent access/retry/security review before real school data.
5. Configure and validate actual payment/email/push providers, native devices, HTTPS hosting, backups/restore and operational monitoring.

The local coding environment became unavailable during the final scheduling work. Those uncommitted changes have **not** been presented as passing or merged into `main`. The branch contains the saved tested checkpoint; `main` still contains the earlier foundation until the remaining work is reviewed.

## Handoff

- [API workflow guide](API.md)
- [All implemented endpoints and request fields](FRONTEND_ENDPOINTS.md)
- [OpenAPI request catalogue](openapi.json)
- [Endpoint CSV](ENDPOINT_CATALOGUE.csv)
- [Local setup](SETUP.md)

Only endpoints backed by current controllers are included. Internal service routes are intentionally excluded from the frontend catalogue. Paystack live mode is rejected by configuration in this build.
