import assert from 'node:assert/strict';

export async function reportControlTests(u,academic,finance) {
  const {request,key,day,passed,activate,login,choose,poll}=u;
  const {school,admin,source,target,classes,future,children,cycles,assignments,t1,t2,englishTeacher,math,parent}=academic;
  const get=async(path,token=admin.accessToken,expected=200)=>(await request(`/api/v1/schools/${school}${path}`,{token,expected})).body;
  const post=async(path,body,version,token=admin.accessToken,expected=200)=>(await request(`/api/v1/schools/${school}${path}`,{method:'POST',token,body,expected,headers:{'Idempotency-Key':key(),...(version===undefined?{}:{'If-Match':`"v${version}"`})}})).body;
  const catalogue=await get('/school-reports/catalogue');assert.ok(catalogue.items.some(x=>x.kind==='ATTENDANCE'));assert.equal(catalogue.maximumRows,1000);
  const enrolled=await get('/school-reports/ENROLMENTS?sessionId='+source.id);assert.equal(enrolled.complete,true);assert.ok(enrolled.rows.some(x=>x.studentName==='Ayọ Demo'));
  const attendance=await get(`/school-reports/ATTENDANCE?studentId=${children[0].id}&from=${day(-340)}&to=${day(-333)}`);assert.ok(attendance.rows.length>0);assert.ok(attendance.rows.every(x=>x.status==='NOT_RECORDED'&&x.registerSubmitted===false));
  await get(`/school-reports/ATTENDANCE?from=${day(1)}&to=${day(-1)}`,admin.accessToken,422);
  await get('/school-reports/ENROLMENTS',parent.accessToken,403);
  await get('/school-reports/ENROLMENTS',t2.accessToken,403);
  const teacherCatalogue=await get('/school-reports/catalogue',t2.accessToken);assert.ok(!teacherCatalogue.items.some(x=>x.kind==='ENROLMENTS'));
  const staff=await get('/school-reports/TEACHING_ASSIGNMENTS?sessionId='+source.id);assert.ok(staff.rows.length>0);
  const completeness=await get('/school-reports/ASSESSMENT_COMPLETENESS?sessionId='+source.id);assert.ok(completeness.rows.some(x=>x.state==='PUBLISHED'));
  passed('report catalogues enforce staff scope; attendance exports label missing information without turning it into absence');

  const cycle=cycles.find(x=>x.c.id===classes[0].id).cycle;
  const broadsheet=await get('/school-reports/PUBLISHED_BROADSHEET?cycleId='+cycle.id,t1.accessToken);assert.ok(broadsheet.rows.some(x=>x.studentName==='Ayọ Demo'&&x.subject==='ENGLISH'&&x.total===95&&x.grade==='A'&&x.average===85&&x.reportRevision===2));assert.ok(broadsheet.columns.every(x=>!x.toLowerCase().includes('rank')));
  await get('/school-reports/PUBLISHED_BROADSHEET?cycleId='+cycle.id,t2.accessToken,404);
  const own=assignments.find(x=>x.classId===classes[0].id&&x.subjectId===math.id),other=assignments.find(x=>x.classId===classes[0].id&&x.subjectId!==math.id);
  const marks=await get(`/school-reports/SUBJECT_MARKS?cycleId=${cycle.id}&assignmentId=${own.id}`,t2.accessToken);assert.ok(marks.rows.length>0&&marks.rows.every(x=>x.grade));
  await get(`/school-reports/SUBJECT_MARKS?cycleId=${cycle.id}&assignmentId=${other.id}`,t2.accessToken,404);
  for(const format of ['CSV','PDF']){
    const response=await fetch(`http://127.0.0.1:8080/api/v1/schools/${school}/school-reports/PUBLISHED_BROADSHEET?cycleId=${cycle.id}&format=${format}`,{headers:{Authorization:'Bearer '+t1.accessToken}});assert.equal(response.status,200);const bytes=new Uint8Array(await response.arrayBuffer());assert.ok(bytes.length>100);assert.equal(response.headers.get('cache-control'),'no-store');
    if(format==='PDF')assert.equal(new TextDecoder().decode(bytes.slice(0,5)),'%PDF-');else assert.ok(new TextDecoder().decode(bytes).includes('Ayọ Demo'));
  }
  await get('/school-reports/PUBLISHED_BROADSHEET?cycleId='+cycle.id+'&format=XML',t1.accessToken,422);
  passed('published broadsheets preserve accepted totals and Unicode in PDF/CSV; subject teachers can export only their permitted sheets');

  const adopted=await get('/grading-policy');assert.equal(adopted.policy.ca1Maximum,20);assert.equal(adopted.policy.ca2Maximum,20);assert.equal(adopted.policy.examMaximum,60);assert.equal(adopted.policy.rankingEnabled,false);assert.equal(adopted.editableWeightsOrBands,false);
  const term=await post('/terms',{sessionId:target.id,number:1,startsOn:day(10),endsOn:day(90)});
  const futureVersion=(await get('/classes?sessionId='+target.id)).items.find(x=>x.id===future.id).version;
  await request(`/api/v1/schools/${school}/classes/${future.id}/attendance-teacher`,{method:'PUT',token:admin.accessToken,body:{teacherId:englishTeacher.id},headers:{'Idempotency-Key':key(),'If-Match':`"v${futureVersion}"`}});
  const window={opensAt:new Date(Date.now()-3600000).toISOString(),closesAt:new Date(Date.now()+86400000).toISOString()};let draft=await post('/assessment-cycles',{termId:term.id,classId:future.id,...window});
  const policy={policyVersion:1,reason:'Principal reviewed and approved the adopted twenty twenty sixty school policy'};
  await post(`/assessment-cycles/${draft.id}/approve-policy`,policy,draft.version,t1.accessToken,403);
  await post(`/assessment-cycles/${draft.id}/approve-policy`,{...policy,policyVersion:2},draft.version,admin.accessToken,422);
  draft=await post(`/assessment-cycles/${draft.id}/approve-policy`,policy,draft.version);assert.equal(draft.policyApproved,true);assert.equal(draft.version,2);
  await post(`/assessment-cycles/${draft.id}/approve-policy`,policy,1,admin.accessToken,412);
  const before=await get(`/assessment-cycles/${draft.id}/review`);assert.ok(before.policyApprovedBy&&before.policyApprovedAt);
  const opened=await post(`/assessment-cycles/${draft.id}/open`,{...window,reason:'Open mark entry using the already approved policy'},draft.version);assert.equal(opened.version,3);
  const after=await get(`/assessment-cycles/${draft.id}/review`);assert.equal(after.policyApprovedAt,before.policyApprovedAt);assert.deepEqual(after.policy,before.policy);
  passed('the agreed grading policy stays fixed and is versioned, Principal approved, and frozen before mark entry');

  const base=`/api/v1/schools/${finance.school}`,fget=async(path,token=finance.admin.accessToken,expected=200)=>(await request(base+path,{token,expected})).body;
  const financeCatalogue=await fget('/reconciliation/reports/catalogue',finance.accounts.accessToken);assert.equal(financeCatalogue.amountUnit,'KOBO');
  const balances=await fget('/reconciliation/reports/INVOICE_BALANCES?termId='+finance.term.id,finance.accounts.accessToken);assert.ok(balances.rows.length>0);const invoice=await fget('/invoices/'+finance.invoice.id);const row=balances.rows.find(x=>x.invoiceId===finance.invoice.id);assert.equal(row.balanceKobo,invoice.invoice.balanceMinor);
  const cash=await fget('/reconciliation/reports/CONFIRMED_PAYMENTS?source=CASH',finance.accounts.accessToken);assert.ok(cash.rows.length>0&&cash.rows.every(x=>x.source==='CASH'));
  assert.ok((await fget('/reconciliation/reports/REFUNDS',finance.accounts.accessToken)).rows.length>0);
  assert.ok((await fget('/reconciliation/reports/SETTLEMENTS',finance.accounts.accessToken)).rows.length>0);
  await fget('/reconciliation/reports/SETTLEMENTS?termId='+finance.term.id,finance.accounts.accessToken,422);
  await fget('/reconciliation/reports/CONFIRMED_PAYMENTS',finance.parent.accessToken,403);
  await get('/school-reports/catalogue',finance.admin.accessToken,404);
  const complaints=await fget('/complaints/reports/CASE_REGISTER',finance.accounts.accessToken);assert.ok(complaints.rows.length>0&&complaints.rows.every(x=>x.category==='FEES'));assert.ok(complaints.columns.every(x=>!['body','entries','notes','resolution'].includes(x)));
  await fget('/complaints/reports/BACKLOG',finance.parent.accessToken,403);
  const office=(await request(base+'/staff',{method:'POST',token:finance.admin.accessToken,body:{fullName:'Report School Office',phone:'08130345678',role:'SCHOOL_OFFICE'},headers:{'Idempotency-Key':key()}})).body;
  const slip=await poll(async()=>{const r=await request(base+`/onboarding/STAFF/${office.id}/activation-slips`,{method:'POST',token:finance.admin.accessToken,body:{},headers:{'Idempotency-Key':key()},expected:null});return r.status===200?r.body:false;},'report office account');await activate(slip);const officeLogin=await login(slip.loginId);await choose(officeLogin,finance.school,'SCHOOL_OFFICE');
  await fget('/reconciliation/reports/catalogue',officeLogin.accessToken,403);const officeCases=await fget('/complaints/reports/CASE_REGISTER',officeLogin.accessToken);assert.ok(officeCases.rows.every(x=>['GENERAL','ATTENDANCE','ACADEMIC_RECORDS'].includes(x.category)));
  passed('finance and complaint exports enforce preset/category limits and distinguish balances, confirmed payments, refunds and settlements');
}
