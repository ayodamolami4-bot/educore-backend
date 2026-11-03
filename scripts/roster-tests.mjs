import assert from 'node:assert/strict';

export async function rosterTests(u, f) {
 const {request,key,day,passed,poll,activate,login,choose}=u;
 const {school,admin,teacher,otherTeacher,parent,children,classA,classB,english,term,session}=f;
 const call=async(method,path,body,version,expected=200,token=admin.accessToken,commandKey=key())=>(await request(`/api/v1/schools/${school}${path}`,{method,body,token,expected,headers:{'Idempotency-Key':commandKey,...(version===undefined?{}:{'If-Match':`"v${version}"`})}})).body;
 const get=(path,token=admin.accessToken)=>call('GET',path,undefined,undefined,200,token);
 const assignments=(await get('/teaching-assignments')).items;
 const englishAssignment=assignments.find(x=>x.classId===classA.id&&x.subjectId===english.id);
 for(const c of [classA,classB]){const workspace=await get(`/classes/${c.id}/workspace`);await call('PUT',`/classes/${c.id}/attendance-teacher`,{teacherId:englishAssignment.teacherId},workspace.class.version);}
 const window={opensAt:new Date(Date.now()-3600000).toISOString(),closesAt:new Date(Date.now()+86400000).toISOString()};
 const cycle=await call('POST','/assessment-cycles',{termId:term.id,classId:classA.id,...window});
 const target=await call('POST','/assessment-cycles',{termId:term.id,classId:classB.id,...window});
 await call('POST',`/assessment-cycles/${cycle.id}/open`,{...window,reason:'Principal opens this term for subject mark entry'},cycle.version);
 let sheet=await get(`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}`,teacher.accessToken);
 const original=sheet.rows.find(x=>x.studentId===children[0].id);
 assert.ok(original.active);
 sheet=await call('PUT',`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}/marks`,{rows:[{studentId:original.studentId,ca1:18,ca2:19,exam:50,examState:'COMPLETE'}]},sheet.version,200,teacher.accessToken);
 sheet=await call('POST',`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}/submit`,undefined,sheet.version,200,teacher.accessToken);
 sheet=await call('POST',`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}/return`,{reason:'Class teacher reviewed and returned this subject sheet for confirmation'},sheet.version,200,teacher.accessToken);
 sheet=await get(`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}`,teacher.accessToken);
 sheet=await call('POST',`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}/submit`,undefined,sheet.version,200,teacher.accessToken);
 const choice=(await get(`/students/${children[0].id}/subject-choices?sessionId=${session.id}`)).subjectIds[0].id;
 const late=await call('POST','/students',{fullName:'Late Snapshot Admission',dateOfBirth:'2013-03-04',sex:'FEMALE',classId:classA.id,startsOn:day(0),metadataVersion:1,fields:{},subjectIds:[choice],guardian:{existingGuardianId:children[0].guardianId,relationship:'MOTHER'}},undefined,202);
 let review=await get(`/assessment-cycles/${cycle.id}/review`);assert.equal(review.rosterSyncRequired,true);assert.ok(review.rosterIssues.some(x=>x.studentId===late.id));assert.equal(review.readyToPublish,false);
 const why={reason:'Principal reviewed the new admission and retained earlier recorded marks'};
 await call('POST',`/assessment-cycles/${cycle.id}/roster/sync`,why,review.version,403,parent.accessToken);
 await call('POST',`/assessment-cycles/${cycle.id}/roster/sync`,why,review.version-1,412);
 const syncKey=key();const synced=await call('POST',`/assessment-cycles/${cycle.id}/roster/sync`,why,review.version,200,admin.accessToken,syncKey);
 assert.deepEqual(await call('POST',`/assessment-cycles/${cycle.id}/roster/sync`,why,review.version,200,admin.accessToken,syncKey),synced);
 sheet=await get(`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}`,teacher.accessToken);assert.equal(sheet.state,'RETURNED');assert.equal(sheet.rows.find(x=>x.studentId===original.studentId).exam,50);assert.equal(sheet.rows.find(x=>x.studentId===late.id).exam,null);
 await call('POST',`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}/submit`,undefined,sheet.version,422,teacher.accessToken);
 const teachers=new Map([[englishAssignment.teacherId,teacher],[assignments.find(x=>x.classId===classA.id&&x.subjectId===f.math.id).teacherId,otherTeacher]]);
 for(const sh of (await get(`/assessment-cycles/${cycle.id}/review`)).sheets){
  if(!sh.active)continue;
  if(!teachers.has(sh.teacherId)){
   const slip=await poll(async()=>{const r=await request(`/api/v1/schools/${school}/onboarding/TEACHER/${sh.teacherId}/activation-slips`,{method:'POST',body:{},token:admin.accessToken,headers:{'Idempotency-Key':key()},expected:null});return r.status===200?r.body:false;},'roster subject teacher activation');
   await activate(slip);const token=await login(slip.loginId);await choose(token,school,'TEACHER');teachers.set(sh.teacherId,token);
  }
  const token=teachers.get(sh.teacherId).accessToken;
  let current=await get(`/assessment-cycles/${cycle.id}/sheets/${sh.assignmentId}`,token);
  current=await call('PUT',`/assessment-cycles/${cycle.id}/sheets/${sh.assignmentId}/marks`,{rows:current.rows.filter(x=>x.active).map(x=>({studentId:x.studentId,ca1:x.ca1??18,ca2:x.ca2??19,exam:x.exam??50,examState:'COMPLETE'}))},current.version,200,token);
  await call('POST',`/assessment-cycles/${cycle.id}/sheets/${sh.assignmentId}/submit`,undefined,current.version,200,token);
 }
 review=await get(`/assessment-cycles/${cycle.id}/review`);
 assert.ok(review.sheets.some(x=>!x.active));
 for(const row of review.students)await call('PUT',`/assessment-cycles/${cycle.id}/remarks/${row.studentId}`,{remark:'The class teacher reviewed this pupil and all submitted subjects.',conduct:4,punctuality:4,cooperation:4,handwriting:4,practicalSkills:4},row.remarkVersion??1,200,teacher.accessToken);
 review=await get(`/assessment-cycles/${cycle.id}/review`);assert.equal(review.rosterSyncRequired,false);assert.equal(review.readyToPublish,true);
 await call('POST',`/assessment-cycles/${cycle.id}/publish`,{principalRemark:'Principal cannot skip the class teacher review submission.',nextResumptionOn:day(70)},review.version,409);
 let submitted=await call('POST',`/assessment-cycles/${cycle.id}/submit-review`,undefined,review.version,200,teacher.accessToken);assert.equal(submitted.state,'REVIEW');
 await call('PUT',`/assessment-cycles/${cycle.id}/remarks/${children[0].id}`,{remark:'Attempt to change a submitted package without returning it.',conduct:4,punctuality:4,cooperation:4,handwriting:4,practicalSkills:4},2,409,teacher.accessToken);
 const returnedPackage=await call('POST',`/assessment-cycles/${cycle.id}/return-review`,{reason:'Principal requested the class teacher to recheck the complete package'},submitted.version);assert.equal(returnedPackage.state,'OPEN');
 review=await get(`/assessment-cycles/${cycle.id}/review`);await call('POST',`/assessment-cycles/${cycle.id}/submit-review`,undefined,review.version,200,teacher.accessToken);review=await get(`/assessment-cycles/${cycle.id}/review`);
 const publication=await call('POST',`/assessment-cycles/${cycle.id}/publish`,{principalRemark:'Principal approved the complete reviewed class reports.',nextResumptionOn:day(70)},review.version);
 assert.equal(publication.reports.length,2);
 const historic=publication.reports.find(x=>x.studentId===children[0].id).id;
 passed('late admission requires principal roster review; retries retain marks, new scores remain blank, empty optional sheets do not block reviewed publication');

 const student=await get('/students/'+children[0].id);
 await call('POST',`/students/${children[0].id}/transfers`,{classId:classB.id,effectiveOn:day(0),reason:'Office confirmed a dated move to the parallel class arm'},student.version);
 review=await get(`/assessment-cycles/${cycle.id}/review`);assert.equal(review.rosterSyncRequired,true);
 await call('POST',`/assessment-cycles/${cycle.id}/roster/sync`,why,review.version,409);
 const reopened=await call('POST',`/assessment-cycles/${cycle.id}/reopen`,{reason:'Principal reviewed the class transfer after report publication',closesAt:window.closesAt},review.version);
 await call('POST',`/assessment-cycles/${cycle.id}/roster/sync`,{reason:'Principal archived the former class scope without deleting recorded marks'},reopened.version);
 review=await get(`/assessment-cycles/${cycle.id}/review`);assert.equal(review.students.length,1);assert.ok(review.archivedStudents.some(x=>x.studentId===children[0].id));
 sheet=await get(`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}`,teacher.accessToken);const archived=sheet.rows.find(x=>x.studentId===children[0].id);assert.equal(archived.active,false);assert.equal(archived.exam,50);
 await call('PUT',`/assessment-cycles/${cycle.id}/sheets/${englishAssignment.id}/marks`,{rows:[{studentId:children[0].id,ca1:0,ca2:0,exam:0,examState:'COMPLETE'}]},sheet.version,404,teacher.accessToken);
 let targetReview=await get(`/assessment-cycles/${target.id}/review`);assert.equal(targetReview.rosterSyncRequired,true);
 await call('POST',`/assessment-cycles/${target.id}/roster/sync`,why,targetReview.version);
 const targetEnglish=assignments.find(x=>x.classId===classB.id&&x.subjectId===english.id);
 const newSheet=await get(`/assessment-cycles/${target.id}/sheets/${targetEnglish.id}`,teacher.accessToken);assert.equal(newSheet.rows.find(x=>x.studentId===children[0].id).exam,null);
 const oldReport=await get('/reports/'+historic,parent.accessToken);assert.ok(oldReport.subjects.some(x=>x.subjectId===english.id&&x.exam===50));
 const bWorkspace=await get(`/classes/${classB.id}/workspace`);const mathTeacher=assignments.find(x=>x.classId===classA.id&&x.subjectId===f.math.id).teacherId;
 await call('PUT',`/classes/${classB.id}/attendance-teacher`,{teacherId:mathTeacher},bWorkspace.class.version);
 targetReview=await get(`/assessment-cycles/${target.id}/review`);await call('POST',`/assessment-cycles/${target.id}/open`,{...window,reason:'Principal opens the reviewed target-class assessment window'},targetReview.version);
 for(const sh of targetReview.sheets.filter(x=>x.active)){
  const token=teachers.get(sh.teacherId).accessToken;let current=await get(`/assessment-cycles/${target.id}/sheets/${sh.assignmentId}`,token);
  current=await call('PUT',`/assessment-cycles/${target.id}/sheets/${sh.assignmentId}/marks`,{rows:current.rows.filter(x=>x.active).map(x=>({studentId:x.studentId,ca1:18,ca2:19,exam:40,examState:'COMPLETE'}))},current.version,200,token);
  await call('POST',`/assessment-cycles/${target.id}/sheets/${sh.assignmentId}/submit`,undefined,current.version,200,token);
 }
 targetReview=await get(`/assessment-cycles/${target.id}/review`);
 for(const row of targetReview.students)await call('PUT',`/assessment-cycles/${target.id}/remarks/${row.studentId}`,{remark:'New class teacher reviewed this independently entered report.',conduct:4,punctuality:4,cooperation:4,handwriting:4,practicalSkills:4},row.remarkVersion??1,200,otherTeacher.accessToken);
 targetReview=await get(`/assessment-cycles/${target.id}/review`);await call('POST',`/assessment-cycles/${target.id}/submit-review`,undefined,targetReview.version,200,otherTeacher.accessToken);targetReview=await get(`/assessment-cycles/${target.id}/review`);const newReports=await call('POST',`/assessment-cycles/${target.id}/publish`,{principalRemark:'Principal approved the new class reports after separate teacher review.',nextResumptionOn:day(70)},targetReview.version);
 const newReport=newReports.reports.find(x=>x.studentId===children[0].id).id;
 assert.ok(!(await get(`/students/${children[0].id}/reports`,teacher.accessToken)).items.some(x=>x.id===newReport));
 await call('GET','/reports/'+newReport,undefined,undefined,404,teacher.accessToken);assert.ok((await get('/reports/'+newReport,otherTeacher.accessToken)).subjects.some(x=>x.exam===40));
 assert.ok((await get('/reports/'+historic,parent.accessToken)).subjects.some(x=>x.exam===50));
 passed('a former class lead can read their own class publication but cannot use that history to read a transferred pupil’s new class report');
 const lateProfile=await get('/students/'+late.id);await call('POST',`/students/${late.id}/withdrawal`,{effectiveOn:day(0),reason:'Family completed the verified school withdrawal procedure'},lateProfile.version);
 review=await get(`/assessment-cycles/${cycle.id}/review`);await call('POST',`/assessment-cycles/${cycle.id}/roster/sync`,{reason:'Principal archived the withdrawn pupil after reviewing the school record'},review.version);
 review=await get(`/assessment-cycles/${cycle.id}/review`);assert.equal(review.students.length,0);assert.equal(review.readyToPublish,false);assert.ok(review.sheets.every(x=>!x.active));
 passed('class transfer and withdrawal archive old assessment rows, require reopening published scopes and never copy marks or alter an issued report silently');
}
