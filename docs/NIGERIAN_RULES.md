# Agreed secondary-school behaviour

Reviewed 4 November 2026. See the preserved [final administrator specification](product/FINAL_ADMIN_SPEC_v1.md) for the full target and [SCREEN_CONNECTIONS.md](SCREEN_CONNECTIONS.md) for remaining controls.

These are EduCore's agreed presentation-school defaults. They are not a claim that every Nigerian school, state or examination body uses identical dates or grading policies. Primary school is outside the agreed current interface scope.

| Rule | Agreed behaviour | Current implementation |
| --- | --- | --- |
| Levels | JSS1–JSS3, SSS1–SSS3; separate class arms such as JSS1A/JSS1B | Class records and enrolment |
| Departments | JSS has no department; SSS uses SCIENCE, ARTS or COMMERCIAL | Validated on enrolment/teaching assignments; department/common timetable visibility |
| Academic year | Three terms with actual school/state-approved dates and holiday exceptions | Session/term creation and teaching-day exceptions; no guessed nationwide calendar |
| Timezone | Africa/Lagos (WAT) | School calculations and startup timezone |
| School day | Monday–Friday, 08:00–13:00 | Timetable validation |
| Periods | 30-minute periods; a double period is 60 minutes | Whole half-hour boundaries; weekly targets count 30-minute periods |
| Break | 11:30–12:00 every day | Cannot schedule lessons over it |
| Wednesday PT | Optional 08:00–08:30 | Metadata setting is frozen on each new timetable plan |
| Friday sports | 12:00–13:00 | Reserved; academic lessons cannot occupy it |
| Teacher selection | Teacher already has allowed levels/subjects and actual class assignments | Lessons use the teaching-assignment ID, which supplies teacher/class/subject |
| Publication | Draft → conflict/period-target review → principal publishes | Teachers/families receive only the effective published timetable |
| Enrolment | Office/principal records child, class, SSS department and verified guardian relationship; parent account is created or explicitly linked | Student and placement commit atomically; account provisioning can briefly remain pending |
| Attendance | Assigned teacher submits a complete register before office sees absences; missing register is not an absent child | Submitted overview and coverage, teacher correction requests, Office approve/decline with stale-source checks, reviewed family alerts and private saved follow-ups |
| Assessment | CA1/20 + CA2/20 + exam/60 = /100 | Frozen assessment scope, one-decimal validation, subject entry/submission and tested official Java totals |
| Bands | A ≥70; B ≥60; C ≥50; D ≥45; E ≥40; F <40 | Tested boundary calculations |
| Report approval | Subject teacher submits → class teacher reviews/remarks → principal publishes → family reads | Implemented subject submission, class-lead remarks/five ratings and explicit class-package submission, Principal publication and immutable report/PDF revisions; a class lead teaching a subject performs both responsibilities |
| Promotion | Mean ≥50, English/Mathematics ≥40 each, ≥60% of enrolled subjects passed at ≥40, evidence from all three published terms | Published-evidence preview, Principal review/approval, reasoned exceptions, stale-source checks and repeat-safe target placements |
| Fees and progress | Debt must not alter academic marks or silently block academic promotion | Separate academic and finance decisions; unpaid invoices survive promotion without silently changing academic outcomes |
| Stage completion | JSS3 needs pathway review; SSS3 completion needs principal approval; external results are recorded only when issued | Explicit JSS3/SSS1 pathways, Principal-approved SSS3 school completion and original external-exam references/grades; no board certificate is manufactured |
| Money | NGN, amounts stored as integer kobo; partial payment when invoice allows it | Invoices, manual payments and Paystack test verification |
| Complaints | GENERAL, ACADEMIC_RECORDS, ATTENDANCE, FEES, STAFF_CONDUCT; private case with appropriate staff scope, first response in two future teaching days, reasoned closure | Implemented; staff-conduct cases are principal-only on the staff side |
| Delivery | In-app notifications, configured optional verified email and Expo push; no SMS/WhatsApp | In-app plus leased SMTP/Expo workers, retry/expiry/access/preference checks and provider doubles; actual mailbox/device validation remains |
| Imports/exports | Staff CSV preview/validation/confirm; permitted CSV/PDF exports | Reviewed previews, row correction, asynchronous jobs/resume without repeating successes, independent opening-balance approval and scoped CSV/PDF downloads |

The API must calculate official totals and recommendations. A phone may show a preview, but its calculation cannot become the school's official result. Later school-policy changes need an explicit version and review; do not change historical published results by editing a current setting.

Additional agreed boundaries:

- Administration has Principal, School Office and Accounts presets. Only Principal manages administrator privileges and final academic/promotion publication. School Office reads the selected pupil fee-status summary; full invoice/payment detail and money-changing actions remain with Principal/Accounts.
- Teacher eligibility is different from actual teaching assignments. A new pupil joins the dated class-arm timetable and applicable SSS/optional-subject group; the school does not build a separate timetable per pupil.
- A phone number or matching name alone does not merge global parent identities. School edits to guardian contact data never reset the parent's global password/recovery identity. Existing accounts require explicit verified linking.
- For sessions from 2026/27, the presentation project's admission policy restricts fresh/transfer SSS3 entry. Principal's documented legacy-data migration is a separate reviewed path. This describes the adopted application boundary, not a new claim about national law.
- Zero marks, unresolved absence/make-up and unrecorded marks remain distinct. Missing required evidence blocks publication; incomplete attendance never produces a misleading complete percentage. There is no overall class rank.
- The adopted grading policy is fixed for the presentation school and returned by `/grading-policy`. Principal approves the frozen cycle policy explicitly or when opening marks. A configurable policy builder is outside the adopted decision; settings cannot rewrite accepted reports.
- APP and PHYSICAL assignments have different receipt flows. Teachers record physical receipt; private review drafts are visible to the teacher until explicitly released. Materials retain the prior published version while a new draft is edited.
- Historical paper reports are verified document copies. They never create official marks or automatically satisfy promotion evidence. Admission copies and staff credentials have explicit publication/visibility checks.
- Complaints follow NEW, IN_REVIEW, WAITING_FOR_PARENT, RESOLVED and CLOSED. Private office notes stay private; parent acceptance or reasoned review preserves history. Missing activity never silently closes a case.
- Account activation uses a private one-time proof; verified-email/saved-code recovery is supported. SH09 remains help/contact only until an assisted global-recovery policy is adopted. A school cannot reset an active global identity.
- Scope excludes payroll, library, hostel, transport, biometrics, recruitment, public group chat, voice/video and invented external-exam integrations.
