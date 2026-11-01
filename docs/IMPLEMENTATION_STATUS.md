# Implementation status — 1 November 2026

This is a working first backend build. The agreed final school application is larger than this implementation. An endpoint below is ready only within the limits stated here and in [API.md](API.md).

| Area | Implemented | Still to build or validate |
| --- | --- | --- |
| Shared access | Activation, login, password setup/change, verified email change, saved-code/email recovery, opaque rotating sessions, allowed school/role/child selection, recent reauthentication, private recovery-code rotation, sessions and preferences | Assisted recovery when all private proofs are lost; external abuse/rate-limit controls; comprehensive breached-password screening; end-to-end native device testing |
| School isolation | Current server-checked permissions, school-scoped SQL, separate service databases/users, explicit child/payer checks, distinct sender-to-receiver service keys, no trusted client identity headers | Independent security review, deployment network policies, production monitoring and backup/restore exercise |
| School setup | Operator creates school and first principal; sessions, three terms, class arms, subject catalogue, versioned metadata | Office/accounts staff account onboarding, full permissions administration and school lifecycle |
| Enrolment and parents | Atomic child/placement/verified guardian link, optional explicit link to an existing login, account provisioning through outbox, pending-account slips, child link revocation, staff/family-safe reads | Additional-guardian management UI/API, changes/transfers/withdrawals, duplicate review workflow and bulk enrolment |
| Teachers | Registration with eligible subjects/levels and actual class assignments; account provisioning and attendance-teacher assignment | Complete edit/deactivation/reassignment workflow, staff invitations for every preset, substitute teachers |
| Timetable | Weekly draft, teacher/class/room conflicts, fixed break/sports/PT reservations, required periods review, principal publication, class/department/teacher/family reads and publication notifications | Draft listing, lesson edits/removal and an easier revision editor; automatic generation; live conflict tests across multiple app instances |
| Attendance | Assigned teacher's dated roster, drafts, complete submit, submitted-only office overview with missing-register coverage, reasoned office corrections, reviewed absence alerts, own-child history | Repeated-absence flag for 3 absences in 10 teaching days, richer reports/exports, substitute duty workflow |
| Examination rules | Tested 20/20/60 calculation, A–F grade bands and annual promotion recommendation function | Marks entry/submission/review, class-teacher remarks, principal report publication, report-card PDFs, assessment calendars, corrections to published results |
| Promotion/graduation | Tested recommendation arithmetic and incomplete-evidence review | Execution/approval records, next-session placement, JSS3 pathway decisions, SSS3 completion and graduation workflows |
| Fees | Immutable issued invoice lines, NGN integer amounts, partial payments, reasoned cash/verified-bank records, permanent reference uniqueness and receipts in invoice detail | Fee plans, bulk issue, credits/discount approvals, refunds, full bank/provider settlement reconciliation and accountant onboarding |
| Paystack | Test hosted checkout, signed raw webhook intake/deduplication, authoritative verify, retryable unknown outcomes, exact-once local allocation and exception listing | Live mode is deliberately rejected; actual test-account/subaccount validation, external webhooks and settlement work remain |
| Communication | In-app private notifications/read status, school-wide notices, parent complaint cases/replies/internal notes/reasoned closure, teaching-day first-response target | Class-targeted notices, parent/teacher message threads, attachments, case reassignment/escalation and school-office walk-in complaints |
| Email and push | Encrypted private mail queue, opt-in configured SMTP delivery, expiry/retry states; encrypted push-device registration | Real email-provider validation; mail-worker leasing before running multiple Communication instances; native push-provider delivery and delivery receipts |
| Calendar/history | Session/term dates, teaching-day exceptions, academic audit endpoint and histories for implemented commands | Calendar list/editor, whole-school history screen, immutable storage/retention policy and unified cross-service audit queries |
| Imports/exports and assignments | No application endpoints yet | CSV preview/validation/confirm imports, scoped CSV/PDF exports, assignment creation, student submissions and teacher feedback |

The normal first-school account is **PRINCIPAL**. Teacher, student and parent provisioning is implemented. Having role checks for `SCHOOL_OFFICE` or `ACCOUNTS` does not mean their account-management screens or onboarding endpoints are finished. Current Finance read endpoints do not include the proposed office read-only preset.

## Verified evidence

The application build passed **30 unit tests** and **19 integration scenario groups** with all five Java programs and real PostgreSQL. The tests cover multi-school and selected-child isolation, enrolment races/retries, account linking/revocation, timetable conflicts/publication, submitted attendance/corrections, payment verification/deduplication, complaint privacy, recovery, refresh replay and service key separation.

Before transfer into the backend repository, [GitHub Actions run 41325001298](https://github.com/ayodamolami4-bot/educore-frontend/actions/runs/41325001298) passed the application tests, built all five Docker images, started Compose, bootstrapped a school and verified gateway sign-in and unconfigured-email behaviour. The backend repository's [Backend checks workflow](https://github.com/ayodamolami4-bot/educore-backend/actions/workflows/backend.yml) repeats those checks for subsequent changes. Paystack HTTP and SMTP were local test doubles. No live money moved, no actual external email was delivered, and no cloud API or app-store build was deployed.

The database development project prepared separately is not proof that the applications are connected to a hosted database. The tested runtime uses four fresh databases in CI; local Compose provides the same four-database layout.

## Next delivery order

1. Add office/accounts onboarding, teacher changes, timetable editing and parent-link management so the current workflows have complete administration screens.
2. Build the examination chain: subject teacher submits, class teacher reviews/remarks, principal publishes, families read the published report.
3. Add promotion/graduation execution using published results, then assignments and submissions.
4. Finish fee plans/adjustments/settlement reconciliation, targeted messaging, import/export and deployment hardening.

Build each workflow with its API, permitted roles, history, failure states and real-database tests before marking the corresponding frontend screen connected.
