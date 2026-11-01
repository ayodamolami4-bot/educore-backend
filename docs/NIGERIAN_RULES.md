# Agreed secondary-school behaviour

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
| Attendance | Assigned teacher submits a complete register before office sees absences; missing register is not an absent child | Submitted overview, coverage, corrections and office-reviewed parent alerts |
| Assessment | CA1/20 + CA2/20 + exam/60 = /100 | Java rule function and unit tests; marks workflow remains to build |
| Bands | A ≥70; B ≥60; C ≥50; D ≥45; E ≥40; F <40 | Tested boundary calculations |
| Report approval | Subject teacher submits → class teacher reviews/remarks → principal publishes → family reads | Planned; a class teacher who teaches a subject follows both responsibilities for that subject and their class |
| Promotion | Mean ≥50, English/Mathematics ≥40 each, ≥60% of enrolled subjects passed at ≥40, evidence from all three published terms | Tested recommendation only; missing evidence requires review; approval/placement workflow remains to build |
| Fees and progress | Debt must not alter academic marks or silently block academic promotion | Separation retained; promotion execution is unfinished |
| Stage completion | JSS3 needs pathway review; SSS3 completion needs principal approval; external results are recorded only when issued | Planned |
| Money | NGN, amounts stored as integer kobo; partial payment when invoice allows it | Invoices, manual payments and Paystack test verification |
| Complaints | GENERAL, ACADEMIC_RECORDS, ATTENDANCE, FEES, STAFF_CONDUCT; private case with appropriate staff scope, first response in two future teaching days, reasoned closure | Implemented; staff-conduct cases are principal-only on the staff side |
| Delivery | In-app notifications; optional configured email; native push planned | In-app and SMTP queue implemented; SMS/WhatsApp/group chat/voice not included |
| Imports/exports | Staff CSV preview/validation/confirm; permitted CSV/PDF exports | Planned; no direct unreviewed bulk import |

The API must calculate official totals and recommendations. A phone may show a preview, but its calculation cannot become the school's official result. Later school-policy changes need an explicit version and review; do not change historical published results by editing a current setting.
