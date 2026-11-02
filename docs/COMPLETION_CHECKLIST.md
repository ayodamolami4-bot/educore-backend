# Backend completion checklist

Completion requires an implemented workflow, current school/role checks, failure states, history and meaningful PostgreSQL tests. The latest verified evidence is in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

- [x] Shared account flows, selected school/role/child and service isolation.
- [x] Office/accounts onboarding, core staff/student/guardian administration and permission versions.
- [x] Curriculum choices, timetable draft editor/cloning and calendar/exam publication.
- [ ] Final cross-schedule conflicts, date-specific exam overrides and optional-subject timetable snapshots.
- [x] Marks chain, class-teacher review, principal report publication/revisions and report PDF/CSV.
- [ ] Late admission/transfer synchronisation after assessment-roster snapshots.
- [x] Promotion/repeat execution, explicit JSS3 pathways, SSS3 school completion and stale-source checks.
- [x] Assignments, private drafts, accepted response/review history, family reads and protected files.
- [x] Fee plans/batches, independent approvals, confirmed refunds, settlement matching and receipts.
- [x] Scoped notices, current parent/class-teacher/office participation, attachments and owned complaints.
- [x] Configured delivery workers with leases/retries/receipts and truthful disabled states.
- [x] Reviewed academic CSV imports/resume, opening balances, authorised exports and service-owned activity endpoints.
- [x] Source-derived endpoint/request catalogue and local Docker startup instructions.
- [ ] Full role-screen contract review, remaining directory filters and final response adapters.
- [ ] Lost-all-proofs assisted recovery and independent release/security review.
- [ ] Actual provider/native-device validation, hosting and operational setup.

A checked implementation item does not imply every associated frontend screen, external provider or production operation has been validated. Read the specific limits in the implementation status.
