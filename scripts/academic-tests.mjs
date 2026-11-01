import assert from 'node:assert/strict';

export async function academicTests(u) {
 const {bootstrap,activate,login,choose,request,schoolPost,poll,passed,key,day}=u;
 const setup=await bootstrap('Academic workflows');await activate(setup.slip);const school=setup.schoolId,admin=await login(setup.slip.loginId);await choose(admin,school,'PRINCIPAL');
 const post=(path,body,extra={})=>schoolPost(admin,school,path,body,extra);
 const get=async(path,token=admin.accessToken)=>(await request(`/api/v1/schools/${school}${path}`,{token})).body;
 const call=async(method,path,body,token,version,expected=200,commandKey=key())=>(await request(`/api/v1/schools/${school}${path}`,{method,body,token,expected,headers:{'Idempotency-Key':commandKey,...(version===undefined?{}:{'If-Match':`"v${version}"`})}})).body;
 const slip=(type,id)=>poll(async()=>{const r=await request(`/api/v1/schools/${school}/onboarding/${type}/${id}/activation-slips`,{method:'POST',token:admin.accessToken,body:{},headers:{'Idempotency-Key':key()},expected:null});return r.status===200?r.body:false;},'academic account provisioning');
 const source=await post('/academic-sessions',{name:'Academic source year',startsOn:day(-360),endsOn:day(-20)});
 const target=await post('/academic-sessions',{name:'Academic target year',startsOn:day(10),endsOn:day(360)});
 const terms=[];for(const [number,start,end]of [[1,-350,-250],[2,-240,-140],[3,-130,-20]])terms.push({...await post('/terms',{sessionId:source.id,number,startsOn:day(start),endsOn:day(end)}),number});
 const classes=[];for(const level of ['JSS1','JSS3','SSS3'])classes.push(await post('/classes',{sessionId:source.id,level,arm:'A'}));
 const future=await post('/classes',{sessionId:target.id,level:'JSS2',arm:'A'}),repeat=await post('/classes',{sessionId:target.id,level:'JSS1',arm:'R'}),pathway=await post('/classes',{sessionId:target.id,level:'SSS1',arm:'A'});
 const english=await post('/subjects',{code:'ENGLISH',name:'English Language',core:true}),math=await post('/subjects',{code:'MATHEMATICS',name:'Mathematics',core:true});
 const levels=['JSS1','JSS2','JSS3','SSS1','SSS2','SSS3'];
 const englishTeacher=await post('/teachers',{fullName:'English Teacher',phone:'08082345678',eligibleLevels:levels,subjectIds:[english.id],assignments:[...classes.map(c=>({classId:c.id,subjectId:english.id,weeklyPeriods:4})),{classId:future.id,subjectId:english.id,weeklyPeriods:4}]});
 const mathTeacher=await post('/teachers',{fullName:'Math Teacher',phone:'08092345678',eligibleLevels:levels,subjectIds:[math.id,english.id],assignments:[...classes.map(c=>({classId:c.id,subjectId:math.id,weeklyPeriods:4})),{classId:future.id,subjectId:math.id,weeklyPeriods:4}]});
 for(const c of classes)await call('PUT',`/classes/${c.id}/attendance-teacher`,{teacherId:englishTeacher.id},admin.accessToken,1);
 const children=[];
 for(const [index,c]of classes.entries()){
   const child=await post('/students',{admissionNumber:'ACA-'+index,fullName:['Ayọ Demo','Pathway Demo','Graduate Demo'][index],dateOfBirth:'2010-04-12',sex:'FEMALE',classId:c.id,startsOn:day(-345),...(c.level.startsWith('SSS')?{department:'SCIENCE'}:{}),metadataVersion:1,fields:{},guardian:children.length?{existingGuardianId:children[0].guardianId,relationship:'MOTHER'}:{fullName:'Parent Academic',phone:'08102345678',relationship:'MOTHER',relationshipVerified:true,verificationReference:'Synthetic in-person school check'}},{expected:202});children.push(child);
 }
 const reviewChild=await post('/students',{admissionNumber:'ACA-REVIEW',fullName:'Review Demo',dateOfBirth:'2010-04-12',sex:'MALE',classId:classes[0].id,startsOn:day(-345),metadataVersion:1,fields:{},guardian:{existingGuardianId:children[0].guardianId,relationship:'MOTHER'}},{expected:202});
 const t1Slip=await slip('TEACHER',englishTeacher.id);await activate(t1Slip);const t1=await login(t1Slip.loginId);await choose(t1,school,'TEACHER');
 const t2Slip=await slip('TEACHER',mathTeacher.id);await activate(t2Slip);const t2=await login(t2Slip.loginId);await choose(t2,school,'TEACHER');
 const parentSlip=await slip('GUARDIAN',children[0].guardianId);await activate(parentSlip);const parent=await login(parentSlip.loginId);await choose(parent,school,'PARENT',children[0].id);
 const assignments=(await get('/teaching-assignments')).items;const cycles=[];let missingChild;
 for(const term of terms){
   if(term.number===3)missingChild=await post('/students',{admissionNumber:'ACA-LATE',fullName:'Late Arrival',dateOfBirth:'2011-04-12',sex:'MALE',classId:classes[0].id,startsOn:day(-120),metadataVersion:1,fields:{},guardian:{existingGuardianId:children[0].guardianId,relationship:'MOTHER'}},{expected:202});
   for(const c of classes){
     const cycle=await post('/assessment-cycles',{termId:term.id,classId:c.id,opensAt:new Date(Date.now()-3600000).toISOString(),closesAt:new Date(Date.now()+86400000).toISOString()});
     cycles.push({cycle,term,c});
     await post(`/assessment-cycles/${cycle.id}/open`,{opensAt:new Date(Date.now()-3600000).toISOString(),closesAt:new Date(Date.now()+86400000).toISOString(),reason:'Principal opens the reviewed assessment window'},{headers:{'If-Match':'"v1"'}});
     const first=term.number===1&&c.id===classes[0].id;
     if(first){assert.equal((await get(`/students/${children[0].id}/reports`,parent.accessToken)).items.length,0);await request(`/api/v1/schools/${school}/assessment-cycles/${cycle.id}`,{token:parent.accessToken,expected:403});}
     for(const subject of [english,math]){
       const assignment=assignments.find(a=>a.classId===c.id&&a.subjectId===subject.id);const teacher=subject.id===english.id?t1:t2;
       let sheet=await get(`/assessment-cycles/${cycle.id}/sheets/${assignment.id}`,teacher.accessToken);
       const rows=sheet.rows.map(row=>({studentId:row.studentId,ca1:row.studentId===reviewChild.id&&subject.id===english.id?10:20,ca2:row.studentId===reviewChild.id&&subject.id===english.id?10:20,exam:row.studentId===reviewChild.id&&subject.id===english.id?0:subject.id===english.id?40:35,examState:row.studentId===reviewChild.id&&subject.id===english.id?'ABSENT_ZERO':'COMPLETE',...(row.studentId===reviewChild.id&&subject.id===english.id?{note:'School approved an evidenced absent examination zero'}:{})}));
       if(first&&subject.id===english.id){
         await call('PUT',`/assessment-cycles/${cycle.id}/sheets/${assignment.id}/marks`,{rows:[{...rows[0],ca1:20.1}]},teacher.accessToken,sheet.version,422);
         await call('PUT',`/assessment-cycles/${cycle.id}/sheets/${assignment.id}/marks`,{rows},t2.accessToken,sheet.version,404);
         sheet=await call('PUT',`/assessment-cycles/${cycle.id}/sheets/${assignment.id}/marks`,{rows:rows.map(r=>r.studentId===reviewChild.id?{...r,exam:null,examState:'MAKEUP_PENDING'}:r)},teacher.accessToken,sheet.version);
         await call('POST',`/assessment-cycles/${cycle.id}/sheets/${assignment.id}/submit`,undefined,teacher.accessToken,sheet.version,422);
       }
       sheet=await call('PUT',`/assessment-cycles/${cycle.id}/sheets/${assignment.id}/marks`,{rows},teacher.accessToken,sheet.version);
       sheet=await call('POST',`/assessment-cycles/${cycle.id}/sheets/${assignment.id}/submit`,undefined,teacher.accessToken,sheet.version);
       if(first&&subject.id===english.id)await call('PUT',`/assessment-cycles/${cycle.id}/sheets/${assignment.id}/marks`,{rows},teacher.accessToken,sheet.version,409);
     }
     let review=await get(`/assessment-cycles/${cycle.id}/review`);
     if(first){assert.equal(review.readyToPublish,false);await post(`/assessment-cycles/${cycle.id}/publish`,{principalRemark:'Keep developing your knowledge and skills.',nextResumptionOn:day(10)},{headers:{'If-Match':`"v${review.version}"`},expected:422});}
     for(const student of review.students)await call('PUT',`/assessment-cycles/${cycle.id}/remarks/${student.studentId}`,{remark:'Consistent participation and progress during this term.',conduct:4,punctuality:4,cooperation:4,handwriting:4,practicalSkills:4},t1.accessToken,1);
     review=await get(`/assessment-cycles/${cycle.id}/review`);assert.equal(review.readyToPublish,true);
     if(first)await call('POST',`/assessment-cycles/${cycle.id}/publish`,{principalRemark:'Keep developing your knowledge and skills.',nextResumptionOn:day(10)},t1.accessToken,review.version,403);
     const publicationKey=key();const published=await post(`/assessment-cycles/${cycle.id}/publish`,{principalRemark:'Keep developing your knowledge and skills.',nextResumptionOn:day(10)},{headers:{'If-Match':`"v${review.version}"`,'Idempotency-Key':publicationKey}});
     const retry=await post(`/assessment-cycles/${cycle.id}/publish`,{principalRemark:'Keep developing your knowledge and skills.',nextResumptionOn:day(10)},{headers:{'If-Match':`"v${review.version}"`,'Idempotency-Key':publicationKey}});assert.deepEqual(retry.reports,published.reports);
     if(first){cycle.firstReport=published.reports.find(r=>r.studentId===children[0].id).id;const report=await get(`/reports/${cycle.firstReport}`,parent.accessToken);assert.equal(report.average,77.5);assert.equal(report.attendance.coverageComplete,false);assert.ok(!('presentPercent'in report.attendance));}
   }
 }
 passed('subject-teacher submission, explicit absent/make-up handling, class-teacher ratings and principal-only class report publication');
 const childReports=(await get(`/students/${children[0].id}/reports`,parent.accessToken)).items;assert.equal(childReports.length,3);
 await request(`/api/v1/schools/${school}/students/${children[1].id}/reports`,{token:parent.accessToken,expected:404});
 const reportResponse=await fetch(`http://127.0.0.1:8080/api/v1/schools/${school}/reports/${childReports[0].id}/pdf`,{headers:{Authorization:'Bearer '+parent.accessToken}});assert.equal(reportResponse.status,200);assert.equal(Buffer.from(await reportResponse.arrayBuffer()).subarray(0,5).toString(),'%PDF-');
 passed('families receive published child-scoped report cards and genuine Unicode-capable PDFs without inventing attendance percentages');

 async function decisions(preview){for(const row of preview.rows){const pass=row.recommendation.recommendation==='PROMOTE';await call('PUT',`/promotion-batches/${preview.id}/students/${row.studentId}`,{decision:pass?'PROMOTE':'REPEAT',targetClassId:pass?future.id:repeat.id,reason:pass?'Principal accepted the published annual recommendation.':'Principal reviewed this student for a repeat placement.'},admin.accessToken,row.version);}return get(`/promotion-batches/${preview.id}`);}
 const stale=await decisions(await post('/promotion-batches',{sourceSessionId:source.id,targetSessionId:target.id,sourceClassId:classes[0].id}));
 const firstCycle=cycles[0].cycle,firstEnglish=assignments.find(a=>a.classId===classes[0].id&&a.subjectId===english.id);
 let review=await get(`/assessment-cycles/${firstCycle.id}/review`);
 await post(`/assessment-cycles/${firstCycle.id}/reopen`,{assignmentId:firstEnglish.id,reason:'Original examination script was independently reviewed.',closesAt:new Date(Date.now()+86400000).toISOString()},{headers:{'If-Match':`"v${review.version}"`}});
 assert.equal((await get(`/reports/${firstCycle.firstReport}`,parent.accessToken)).average,77.5);
 let sheet=await get(`/assessment-cycles/${firstCycle.id}/sheets/${firstEnglish.id}`,t1.accessToken);
 sheet=await call('PUT',`/assessment-cycles/${firstCycle.id}/sheets/${firstEnglish.id}/marks`,{rows:sheet.rows.map(r=>({studentId:r.studentId,ca1:r.ca1,ca2:r.ca2,exam:r.studentId===children[0].id?55:r.exam,examState:r.examState,note:r.note}))},t1.accessToken,sheet.version);
 await call('POST',`/assessment-cycles/${firstCycle.id}/sheets/${firstEnglish.id}/submit`,undefined,t1.accessToken,sheet.version);
 review=await get(`/assessment-cycles/${firstCycle.id}/review`);const correction=await post(`/assessment-cycles/${firstCycle.id}/publish`,{principalRemark:'The reviewed correction is approved for publication.',nextResumptionOn:day(10)},{headers:{'If-Match':`"v${review.version}"`}});
 const correctedId=correction.reports.find(r=>r.studentId===children[0].id).id;assert.equal((await get(`/reports/${correctedId}`,parent.accessToken)).average,85);assert.equal((await get(`/reports/${firstCycle.firstReport}`,parent.accessToken)).average,77.5);
 await post(`/promotion-batches/${stale.id}/confirm`,undefined,{headers:{'If-Match':`"v${stale.version}"`},expected:412});
 assert.equal((await get(`/students/${children[0].id}`)).enrolments.length,1);
 passed('reasoned report correction preserves old publications and invalidates promotion previews based on superseded results');

 const debt=await post('/invoices',{studentId:children[0].id,termId:terms[2].id,description:'Unpaid source-year tuition',lines:[{description:'Tuition',amountMinor:500000,quantity:1}],dueOn:day(-30),partialAllowed:true});
 let fresh=await post('/promotion-batches',{sourceSessionId:source.id,targetSessionId:target.id,sourceClassId:classes[0].id});
 const missing=fresh.rows.find(r=>r.studentId===missingChild.id);assert.equal(missing.recommendation.recommendation,'REVIEW');
 await call('PUT',`/promotion-batches/${fresh.id}/students/${missing.studentId}`,{decision:'PROMOTE',targetClassId:future.id,reason:'Attempt to bypass missing term evidence.'},admin.accessToken,missing.version,422);
 fresh=await decisions(fresh);const confirmationKey=key();const outcome=await post(`/promotion-batches/${fresh.id}/confirm`,undefined,{headers:{'If-Match':`"v${fresh.version}"`,'Idempotency-Key':confirmationKey}});assert.equal(outcome.state,'COMPLETED');
 const retry=await post(`/promotion-batches/${fresh.id}/confirm`,undefined,{headers:{'If-Match':`"v${fresh.version}"`,'Idempotency-Key':confirmationKey}});assert.deepEqual(retry.results,outcome.results);
 assert.equal((await get(`/students/${children[0].id}`)).enrolments.length,2);assert.equal((await get(`/invoices/${debt.invoice.id}`)).invoice.paidMinor,0);
 passed('promotion/repeat confirmation is duplicate-safe, requires evidence for overrides and leaves unpaid invoices intact');
 let jss=await post('/promotion-batches',{sourceSessionId:source.id,targetSessionId:target.id,sourceClassId:classes[1].id});const stage=jss.rows[0];
 await call('PUT',`/promotion-batches/${jss.id}/students/${stage.studentId}`,{decision:'PROMOTE',targetClassId:pathway.id,department:'SCIENCE',reason:'Attempt an automatic pathway transition.'},admin.accessToken,stage.version,422);
 jss=await call('PUT',`/promotion-batches/${jss.id}/students/${stage.studentId}`,{decision:'JSS_COMPLETE',targetClassId:pathway.id,department:'SCIENCE',reason:'Principal reviewed the JSS completion and SSS1 science pathway.'},admin.accessToken,stage.version);
 await post(`/promotion-batches/${jss.id}/confirm`,undefined,{headers:{'If-Match':`"v${jss.version}"`}});
 let graduation=await post('/promotion-batches',{sourceSessionId:source.id,sourceClassId:classes[2].id});const grad=graduation.rows[0];
 graduation=await call('PUT',`/promotion-batches/${graduation.id}/students/${grad.studentId}`,{decision:'GRADUATE',reason:'Principal approves school completion without asserting any external examination grade.'},admin.accessToken,grad.version);
 await post(`/promotion-batches/${graduation.id}/confirm`,undefined,{headers:{'If-Match':`"v${graduation.version}"`}});assert.equal((await get(`/students/${children[2].id}`)).status,'GRADUATED');
 passed('JSS3 requires an explicit SSS1 pathway and SSS3 completion is a principal-approved school record');
}
