# Backend completion checklist

Each implemented item requires permissions, failure states, history and real-database evidence. Read [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for the verified checkpoint and [SCREEN_CONNECTIONS.md](SCREEN_CONNECTIONS.md) for each page.

- [x] Shared account flows, server context and service isolation.
- [x] Staff/student/guardian administration, directory filters, setting/form versions and activated-principal protection.
- [x] Curriculum choices, weekly editor/cloning and calendar/exam publication.
- [x] Dated normal/exam replacement, resource/activity conflicts, optional-subject snapshots and daily schedule.
- [x] Subject marks → class-lead package submission/return → principal publication, immutable report revisions and PDF/CSV.
- [x] Late admission/transfer/withdrawal roster synchronisation without deleting marks or inventing zeros.
- [x] Promotion/repeat, explicit JSS3 pathways, SSS3 completion and stale-source protection.
- [x] App assignments/submissions/feedback and protected attachments.
- [x] Fee plans/batches, independent approvals, confirmed refunds, settlement review and receipts.
- [x] Scoped communication/complaints and leased configured delivery.
- [x] Reviewed CSV imports/resume, opening balances, scoped exports and service-owned activity.
- [x] Review all 94 pages and validate the 224-operation request catalogue against current controllers.
- [ ] Implement the remaining controls in the 27 PARTIAL screen rows.
- [ ] Adopt and implement an assisted-recovery policy beyond the agreed SH09 help-only boundary.
- [ ] Complete actual provider/device validation, hosting, backup/restore and independent release/security review.
- [ ] Build/connect and test the React Native role screens with the frontend team.

A checked backend item is not a completed frontend page or evidence of a live provider/deployment.
