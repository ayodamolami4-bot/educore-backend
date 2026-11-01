# Backend completion work

The final administrator specification (34 templates), shared contracts and teacher/student/parent workflows define the scope. A completed item needs real endpoints, current school/role checks, validated inputs, failure states, history and meaningful PostgreSQL tests. Provider credentials and deployment are separate from implementation.

- [ ] Staff presets, access lifecycle, teacher handover, student edits/transfers/withdrawal and guardian permissions.
- [ ] Curriculum choices, class workspaces, timetable editing and calendar publication.
- [ ] Assessment cycles, exam schedule, subject marks submission/return, class-teacher comments/ratings, principal publication, historical report revisions and PDF/CSV reports.
- [ ] Promotion preview, reasoned exceptions, explicit JSS3 pathways, SSS3 completion and repeat-safe target enrolment.
- [ ] Assignments, student submissions, teacher feedback and family visibility.
- [ ] Fee plans/batch issue, approved credits/discounts/refunds, payment directories, settlement reconciliation and receipts.
- [ ] Scoped announcements, child-linked messaging/attachments, walk-in complaints, owners/status changes and private histories.
- [ ] Delivery workers for configured push/email with retries, disabled-provider states and safe previews.
- [ ] CSV preview/confirm imports, authorised exports, activity history and role dashboards.
- [ ] Complete endpoint handoff, real-database regression tests and runnable Docker checks.

The foundation tests are documented in IMPLEMENTATION_STATUS.md. Do not describe the unfinished workflows above as complete because their rule functions or database tables exist.
