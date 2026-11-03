import assert from 'node:assert/strict';

export async function workspaceTests(u) {
 const {bootstrap,activate,login,choose,request,key,day,poll,passed}=u;
 const setup=await bootstrap('Workspace settings');await activate(setup.slip);const school=setup.schoolId,admin=await login(setup.slip.loginId);await choose(admin,school,'PRINCIPAL');
 const call=async(method,path,body,version,expected=200,token=admin.accessToken)=>(await request(`/api/v1/schools/${school}${path}`,{method,body,token,expected,headers:{'Idempotency-Key':key(),...(version===undefined?{}:{'If-Match':`"v${version}"`})}})).body;
 const get=(path,token)=>call('GET',path,undefined,undefined,200,token);
 const provision=async(type,id)=>{const slip=await poll(async()=>{const r=await request(`/api/v1/schools/${school}/onboarding/${type}/${id}/activation-slips`,{method:'POST',token:admin.accessToken,body:{},headers:{'Idempotency-Key':key()},expected:null});return r.status===200?r.body:false;},'workspace account provision');await activate(slip);return login(slip.loginId);};
 const principal=(await get('/staff?role=PRINCIPAL')).items[0];
 const pending=await call('POST','/staff',{fullName:'Pending Replacement Principal',phone:'08150345678',role:'PRINCIPAL',staffNumber:'PEND-01'});
 await call('POST',`/staff/${principal.id}/access`,{role:'SCHOOL_OFFICE',active:true,reason:'A pending invitation must not lock out the active principal'},principal.version,409);
 assert.ok((await get('/staff?search=Replacement&role=PRINCIPAL&active=true')).items.some(x=>x.id===pending.id));
 const office=await call('POST','/staff',{fullName:'Settings School Office',phone:'08160345678',role:'SCHOOL_OFFICE',staffNumber:'OFF-02'});const officeLogin=await provision('STAFF',office.id);await choose(officeLogin,school,'SCHOOL_OFFICE');
 let settings=await get('/settings');const update={phone:'08170345678',email:'office@example.invalid',address:'12 Synthetic School Road',wednesdayPtEnabled:false,reason:'Principal confirmed the school contact information'};
 await call('PUT','/settings',update,settings.version,403,officeLogin.accessToken);
 const saved=await call('PUT','/settings',update,settings.version);assert.equal(saved.timetableReviewRequired,settings.wednesdayPtEnabled!==update.wednesdayPtEnabled);assert.equal(saved.settings.phone,'+2348170345678');await call('PUT','/settings',update,settings.version,412);
 const metadata=await get('/metadata');const revised=await call('POST','/metadata-versions',{studentFields:[{key:'medicalNote',label:'School medical note',maxLength:80,required:false}],reason:'Principal reviewed this additional optional enrolment field'},metadata.version);assert.equal(revised.version,metadata.version+1);
 await call('POST','/metadata-versions',{studentFields:[{key:'classId',label:'Reserved official field',maxLength:80,required:false}],reason:'Cannot redefine official school identity or placement fields'},revised.version,422);
 const session=await call('POST','/academic-sessions',{name:'Workspace session',startsOn:day(-40),endsOn:day(180)});const term=await call('POST','/terms',{sessionId:session.id,number:1,startsOn:day(-30),endsOn:day(60)});const cls=await call('POST','/classes',{sessionId:session.id,level:'JSS1',arm:'A'});const english=await call('POST','/subjects',{code:'ENGLISH',name:'English Language',core:true});
 const teacher=await call('POST','/teachers',{fullName:'Workspace English Teacher',phone:'08180345678',eligibleLevels:['JSS1'],subjectIds:[english.id],assignments:[{classId:cls.id,subjectId:english.id,weeklyPeriods:4}]});const teacherLogin=await provision('TEACHER',teacher.id);await choose(teacherLogin,school,'TEACHER');await call('PUT',`/classes/${cls.id}/attendance-teacher`,{teacherId:teacher.id},cls.version);
 const enrol={fullName:'Workspace Pupil',dateOfBirth:'2013-02-12',sex:'MALE',classId:cls.id,startsOn:day(-20),metadataVersion:revised.version,fields:{medicalNote:'Synthetic optional school note'},guardian:{fullName:'Workspace Guardian',phone:'08190345678',relationship:'FATHER',relationshipVerified:true,verificationReference:'School checked the fictional in-person admission documents'}};
 await call('POST','/students',{...enrol,metadataVersion:metadata.version},undefined,412);
 const child=await call('POST','/students',enrol,undefined,202);const parent=await provision('GUARDIAN',child.guardianId);await choose(parent,school,'PARENT',child.id);
 const allTeachers=(await get('/teachers')).items;assert.deepEqual(allTeachers.map(x=>x.id),[teacher.id]);
 assert.equal((await get(`/teachers?search=English&level=JSS1&subjectId=${english.id}&classId=${cls.id}&active=true`)).items[0].id,teacher.id);assert.equal((await get('/teachers?level=SSS3')).items.length,0);
 assert.equal((await get('/guardians?search=Workspace%20Pupil&activeLink=true')).items[0].id,child.guardianId);assert.equal((await get('/guardians?childId='+child.id)).items.length,1);await call('GET','/staff',undefined,undefined,403,parent.accessToken);
 const oldPhone=(await request('/api/v1/me',{token:parent.accessToken})).body.phone;
 const guardian=await get('/guardians/'+child.guardianId);const edited=await call('PATCH','/guardians/'+child.guardianId,{fullName:guardian.fullName,phone:'08010345678',email:'contact@example.invalid',reason:'School updated its contact record after verifying the parent'},guardian.version);assert.equal(edited.phone,'+2348010345678');assert.equal((await request('/api/v1/me',{token:parent.accessToken})).body.phone,oldPhone);
 passed('directory filters stay within school and staff preset; settings/forms reject stale versions; school contacts do not rewrite a global login; pending principals cannot replace activated access');

 const plan=await call('POST','/timetable-plans',{termId:term.id,effectiveFrom:day(-10)});let pv=plan.version;
 for(const weekday of [1,3])pv=(await call('POST',`/timetable-plans/${plan.id}/lessons`,{assignmentId:(await get('/teaching-assignments?classId='+cls.id)).items[0].id,weekday,start:'08:00',durationMinutes:60,room:'WORK-A'},pv)).planVersion;
 await call('POST',`/timetable-plans/${plan.id}/publish`,undefined,pv);
 settings=await get('/settings');const enabled=await call('PUT','/settings',{...update,wednesdayPtEnabled:true,reason:'Principal enables PT for the next published timetable revision'},settings.version);assert.equal(enabled.timetableReviewRequired,true);
 let wed=new Date(day(1)+'T12:00:00Z');while(wed.getUTCDay()!==3)wed.setUTCDate(wed.getUTCDate()+1);const wednesday=wed.toISOString().slice(0,10);
 assert.ok(!(await get('/schedule/day?on='+wednesday,parent.accessToken)).items.some(x=>x.type==='WEDNESDAY_PT'));
 const clone=await call('POST','/timetable-plans',{termId:term.id,effectiveFrom:day(-10),sourcePlanId:plan.id});let draft=await get(`/timetable-plans/${clone.id}/review`);assert.ok(draft.conflicts.some(x=>x.issue==='FIXED_ACTIVITY_CONFLICT'));await call('POST',`/timetable-plans/${clone.id}/publish`,undefined,draft.version,422);
 const lesson=draft.lessons.find(x=>x.weekday===3),assignment=(await get('/teaching-assignments?classId='+cls.id)).items[0];const moved=await call('PUT',`/timetable-plans/${clone.id}/lessons/${lesson.id}`,{assignmentId:assignment.id,weekday:3,start:'09:00',durationMinutes:60,room:'WORK-A'},draft.version);await call('POST',`/timetable-plans/${clone.id}/publish`,undefined,moved.planVersion);
 assert.ok((await get('/schedule/day?on='+wednesday,parent.accessToken)).items.some(x=>x.type==='WEDNESDAY_PT'&&x.startMinute===480&&x.endMinute===510));
 let friday=new Date(wed);while(friday.getUTCDay()!==5)friday.setUTCDate(friday.getUTCDate()+1);const onDate=friday.toISOString().slice(0,10);
 const duty=await call('PUT',`/terms/${term.id}/activities`,{activity:'FRIDAY_SPORTS',teacherIds:[teacher.id],reason:'Principal assigns the Friday sports supervisor'},term.version);
 const exams=await call('POST','/exam-schedules',{termId:term.id});const event={classId:cls.id,subjectId:english.id,invigilatorId:teacher.id,onDate,start:'12:00',durationMinutes:60,room:'EXAM-WORK',exceptionReason:'Principal authorises this exceptional dated examination during sports'};
 await call('POST',`/exam-schedules/${exams.id}/events`,event,exams.version,409);
 const otherClass=await call('POST','/classes',{sessionId:session.id,level:'JSS1',arm:'B'});const other=await call('POST','/teachers',{fullName:'Available Exam Invigilator',phone:'08020345678',eligibleLevels:['JSS1'],subjectIds:[english.id],assignments:[{classId:otherClass.id,subjectId:english.id,weeklyPeriods:2}]});
 const e=await call('POST',`/exam-schedules/${exams.id}/events`,{...event,invigilatorId:other.id},exams.version);await call('POST',`/exam-schedules/${exams.id}/publish`,undefined,e.scheduleVersion);
 await call('PUT',`/terms/${term.id}/activities`,{activity:'FRIDAY_SPORTS',teacherIds:[other.id],reason:'An invigilator cannot simultaneously supervise school sports'},duty.version,409);
 passed('PT settings take effect through reviewed timetable revisions; fixed activities and exceptional examinations enforce supervisor conflicts in both directions');

 const teachingDates=[];for(let n=0;teachingDates.length<10;n--){const d=new Date(day(n)+'T12:00:00Z');if(d.getUTCDay()>0&&d.getUTCDay()<6)teachingDates.push(day(n));}
 for(const onDate of teachingDates.slice(0,3)){
  let register=await call('POST','/attendance/registers',{classId:cls.id,onDate},undefined,200,teacherLogin.accessToken);
  register=await call('PUT',`/attendance/registers/${register.id}/marks`,{rows:[{studentId:child.id,status:'ABSENT'}]},register.version,200,teacherLogin.accessToken);
  await call('POST',`/attendance/registers/${register.id}/submit`,undefined,register.version,200,teacherLogin.accessToken);
 }
 const followup=(await get('/attendance/follow-ups?on='+day(0))).items.find(x=>x.studentId===child.id);assert.equal(followup.absences,3);assert.equal(followup.expectedRegisters,10);assert.equal(followup.submittedRegisters,3);assert.equal(followup.coverageComplete,false);await call('GET','/attendance/follow-ups?on='+day(1),undefined,undefined,422);await call('GET','/attendance/follow-ups',undefined,undefined,403,parent.accessToken);
 passed('three confirmed absences trigger the ten-teaching-day follow-up while missing registers remain incomplete coverage rather than absences');
}
