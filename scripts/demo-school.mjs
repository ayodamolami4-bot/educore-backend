import {DemoWorkspace, lockWorkspace} from './demo-seed-core.mjs';

// This is an API client for one fictional school, not a migration or a test-suite runner.
export async function seedDemoSchool(options) {
  const release = lockWorkspace(options.folder);
  try {
    const d = new DemoWorkspace(options), p = d.state.plan;
    const log = options.log || console.log;
    const admin = await d.account('lead', {name: 'Demo Principal', role: 'PRINCIPAL'});
    const sessions = await d.get('/academic-sessions', admin);
    if (!d.state.commands.session && sessions.items.some(x => x.name === 'Demo integration session'))
      throw new Error('Demo records already exist without their retry state. Restore the private demo setup files.');
    const post = (label, path, body, actor = admin, version) =>
      d.mutate(label, actor, 'POST', path, body, version);
    const session = await post('session', '/academic-sessions',
      {name: 'Demo integration session', startsOn: p.sessionStart, endsOn: p.sessionEnd});
    const terms = [];
    for (const [number, startsOn, endsOn] of p.terms)
      terms.push(await post('term-' + number, '/terms', {sessionId: session.id, number, startsOn, endsOn}));
    await d.mutate('active-session', admin, 'PUT', '/active-academic-session',
      {sessionId: session.id, reason: 'Select the fictional frontend integration session.'}, 1);
    const classes = [];
    for (const [level, arm] of [['JSS1', 'A'], ['JSS1', 'B'], ['SSS1', 'A']])
      classes.push(await post('class-' + level + arm, '/classes', {sessionId: session.id, level, arm}));
    const subjects = [];
    for (const [code, name, core] of [['ENGLISH', 'English Language', true],
      ['MATHEMATICS', 'Mathematics', true], ['BIOLOGY', 'Biology', false],
      ['CIVIC', 'Civic Education', false]])
      subjects.push(await post('subject-' + code, '/subjects', {code, name, core}));
    const staff = await post('frontend-admin', '/staff', {fullName: 'Frontend Administrator Demo',
      phone: '08070000001', role: 'PRINCIPAL', staffNumber: 'DEMO-ADMIN'});
    await d.account('admin', {name: 'Frontend Administrator Demo', role: 'PRINCIPAL',
      type: 'STAFF', subjectId: staff.id}, admin);
    const teachers = [];
    const teacherActors = [];
    for (const [i, fullName] of ['Ibrahim Demo', 'Bisi Demo'].entries()) {
      const teacher = await post('teacher-' + i, '/teachers', {fullName,
        phone: i ? '08070000003' : '08070000002', eligibleLevels: ['JSS1', 'SSS1'],
        subjectIds: [subjects[i].id],
        assignments: classes.map(c => ({classId: c.id, subjectId: subjects[i].id, weeklyPeriods: 1}))});
      teachers.push(teacher);
      teacherActors.push(await d.account(i ? 'mathTeacher' : 'teacher',
        {name: fullName, role: 'TEACHER', type: 'TEACHER', subjectId: teacher.id}, admin));
    }
    for (const [i, c] of classes.entries()) {
      const workspace = await d.get('/classes/' + c.id + '/workspace', admin);
      await d.mutate('class-teacher-' + i, admin, 'PUT', '/classes/' + c.id + '/attendance-teacher',
        {teacherId: teachers[i === 1 ? 1 : 0].id}, workspace.class.version);
    }
    const metadata = await d.get('/metadata', admin);
    const students = [];
    const studentInputs = [
      {fullName: 'Zainab Demo', sex: 'FEMALE', classIndex: 0, parent: 'Ada Demo', phone: '08070000004'},
      {fullName: 'Mariam Demo', sex: 'FEMALE', classIndex: 1, reuseFirstParent: true},
      {fullName: 'Tunde Demo', sex: 'MALE', classIndex: 0, parent: 'Bola Demo', phone: '08070000005'},
      {fullName: 'Chidi Demo', sex: 'MALE', classIndex: 2, parent: 'Ngozi Demo', phone: '08070000006'}
    ];
    for (const [i, input] of studentInputs.entries()) {
      const guardian = input.reuseFirstParent ?
        {existingGuardianId: students[0].guardianId, relationship: 'MOTHER'} :
        {fullName: input.parent, phone: input.phone, relationship: 'MOTHER',
          relationshipVerified: true, verificationReference: 'Fictional presentation-school relationship only'};
      students.push(await post('student-' + i, '/students', {
        fullName: input.fullName, sex: input.sex, dateOfBirth: i === 3 ? '2011-03-12' : '2013-06-12',
        classId: classes[input.classIndex].id, startsOn: p.terms[0][1],
        ...(i === 3 ? {department: 'SCIENCE'} : {}),
        metadataVersion: metadata.version, fields: {}, guardian}));
    }
    const studentActor = await d.account('student', {name: 'Zainab Demo', role: 'STUDENT',
      type: 'STUDENT', subjectId: students[0].id}, admin);
    const parent = await d.account('parent', {name: 'Ada Demo', role: 'PARENT', type: 'GUARDIAN',
      subjectId: students[0].guardianId, childId: students[0].id}, admin);
    log('Demo role accounts and linked school records are ready.');

    const assignments = (await d.get('/teaching-assignments', admin)).items;
    const plan = await post('timetable', '/timetable-plans',
      {termId: terms[0].id, effectiveFrom: p.terms[0][1]});
    let planVersion = plan.version;
    for (const [i, c] of classes.entries()) {
      for (const [j, subject] of subjects.slice(0, 2).entries()) {
        const assignment = assignments.find(a => a.classId === c.id && a.subjectId === subject.id);
        if (!assignment) throw new Error('A demo teaching assignment is missing.');
        const minute = 510 + (i + j) * 30;
        const start = String(Math.floor(minute / 60)).padStart(2, '0') + ':' +
          String(minute % 60).padStart(2, '0');
        const lesson = await post('lesson-' + i + '-' + j, '/timetable-plans/' + plan.id + '/lessons',
          {assignmentId: assignment.id, weekday: 1, start, durationMinutes: 30,
            room: c.level + c.arm}, admin, planVersion);
        planVersion = lesson.planVersion;
      }
    }
    await post('publish-timetable', '/timetable-plans/' + plan.id + '/publish', undefined, admin, planVersion);
    const register = await post('attendance', '/attendance/registers',
      {classId: classes[0].id, onDate: p.attendanceDate}, teacherActors[0]);
    const marked = await d.mutate('attendance-marks', teacherActors[0], 'PUT',
      '/attendance/registers/' + register.id + '/marks',
      {rows: [{studentId: students[0].id, status: 'ABSENT'}, {studentId: students[2].id, status: 'PRESENT'}]},
      register.version);
    await post('submit-attendance', '/attendance/registers/' + register.id + '/submit',
      undefined, teacherActors[0], marked.version);
    await post('reviewed-absence', '/attendance/registers/' + register.id + '/absence-alerts',
      {studentId: students[0].id, reviewReason: 'Fictional absence reviewed for frontend integration.'});

    const english = assignments.find(a => a.classId === classes[0].id && a.subjectId === subjects[0].id);
    const work = await post('assignment', '/assignments', {teachingAssignmentId: english.id,
      termId: terms[0].id, title: 'Demo reading and written response',
      instructions: 'Write five sentences about how your school keeps its environment clean.',
      dueAt: p.assignmentDueAt, maximumScore: 20, fileIds: [], submissionMode: 'APP'}, teacherActors[0]);
    await post('publish-assignment', '/assignments/' + work.id + '/publish',
      undefined, teacherActors[0], work.version);
    const invoices = [];
    for (const [i, student] of students.entries()) {
      const issued = await post('invoice-' + i, '/invoices', {studentId: student.id, termId: terms[0].id,
        description: 'Fictional first-term fees', dueOn: p.dueOn, partialAllowed: true,
        lines: [{description: 'Demo tuition', amountMinor: 3000000, quantity: 1},
          {description: 'Demo development levy', amountMinor: 500000, quantity: 1}]});
      invoices.push(issued.invoice);
      if ([0, 2].includes(i)) await post('payment-' + i,
        '/invoices/' + issued.invoice.id + '/manual-payments',
        {amountMinor: i === 0 ? 1000000 : 3500000, method: 'CASH',
          reference: 'DEMO-' + d.schoolId + '-' + i,
          verificationReference: 'SIMULATED receipt for fictional presentation data; no money received.',
          reason: 'Create a fictional fee status for frontend integration.'}, admin, issued.invoice.version);
    }
    const cycle = await post('assessment', '/assessment-cycles', {termId: terms[0].id,
      classId: classes[0].id, opensAt: p.opensAt, closesAt: p.closesAt});
    const policy = (await d.get('/grading-policy', admin)).policy;
    const approved = await post('approve-grading', '/assessment-cycles/' + cycle.id + '/approve-policy',
      {policyVersion: policy.version, reason: 'Approve the adopted 20/20/60 policy for fictional results.'},
      admin, cycle.version);
    await post('open-assessment', '/assessment-cycles/' + cycle.id + '/open',
      {opensAt: p.opensAt, closesAt: p.closesAt, reason: 'Open the fictional integration mark-entry window.'},
      admin, approved.version);
    for (const [i, subject] of subjects.slice(0, 2).entries()) {
      const assignment = assignments.find(a => a.classId === classes[0].id && a.subjectId === subject.id);
      const path = '/assessment-cycles/' + cycle.id + '/sheets/' + assignment.id;
      const sheet = await d.get(path, teacherActors[i]);
      const saved = await d.mutate('marks-' + i, teacherActors[i], 'PUT', path + '/marks',
        {rows: sheet.rows.map((row, n) => ({studentId: row.studentId, ca1: 18 - n,
          ca2: 17 - n, exam: (i ? 42 : 45) - n, examState: 'COMPLETE'}))}, sheet.version);
      await post('submit-marks-' + i, path + '/submit', undefined, teacherActors[i], saved.version);
    }
    let review = await d.get('/assessment-cycles/' + cycle.id + '/review', admin);
    for (const row of review.students) await d.mutate('remark-' + row.studentId,
      teacherActors[0], 'PUT', '/assessment-cycles/' + cycle.id + '/remarks/' + row.studentId,
      {remark: 'A fictional report demonstrating steady effort and participation.',
        conduct: 4, punctuality: 4, cooperation: 4, handwriting: 4, practicalSkills: 4},
      row.remarkVersion || 1);
    review = await d.get('/assessment-cycles/' + cycle.id + '/review', admin);
    await post('class-review', '/assessment-cycles/' + cycle.id + '/submit-review',
      undefined, teacherActors[0], review.version);
    review = await d.get('/assessment-cycles/' + cycle.id + '/review', admin);
    const published = await post('publish-reports', '/assessment-cycles/' + cycle.id + '/publish',
      {principalRemark: 'Fictional presentation report; continue practising your skills.',
        nextResumptionOn: p.terms[1][1]}, admin, review.version);
    const notice = await post('notice', '/notices', {title: 'Welcome to the EduCore presentation school',
      body: 'All pupils, names, attendance and payment records here are fictional integration examples.',
      scope: 'SCHOOL', fileIds: []});
    await post('publish-notice', '/notices/' + notice.id + '/publish', undefined, admin, notice.version);
    const complaint = await post('complaint', '/complaints', {studentId: students[0].id,
      category: 'FEES', subject: 'Demo question about the development levy',
      body: 'Please explain what the fictional development levy covers in this test school.',
      fileIds: []}, parent);
    d.state.records = {sessionId: session.id, termIds: terms.map(t => t.id),
      classIds: classes.map(c => c.id), subjectIds: subjects.map(s => s.id),
      teacherIds: teachers.map(t => t.id), studentIds: students.map(s => s.id),
      assignmentId: work.id, timetableId: plan.id, registerId: register.id,
      invoiceIds: invoices.map(i => i.id), reportIds: published.reports.map(r => r.id),
      noticeId: notice.id, complaintId: complaint.id};
    // Exercise the primary family reads before declaring the setup complete.
    await d.get('/students/' + students[0].id, studentActor);
    await d.get('/students/' + students[0].id + '/reports', parent);
    d.state.completed = true; d.save(); d.credentials();
    log('Demo timetable, submitted attendance, assignment, invoices, reports and complaint are ready.');
    return {schoolId: d.schoolId, records: d.state.records,
      accounts: Object.keys(d.state.accounts), completed: true};
  } finally { release(); }
}
