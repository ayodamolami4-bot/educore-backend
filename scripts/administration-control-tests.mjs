import assert from 'node:assert/strict';

export async function administrationControlTests(u) {
  const {bootstrap,activate,login,choose,request,key,day,poll,passed}=u;
  const setup=await bootstrap('Administration controls');await activate(setup.slip);
  const school=setup.schoolId,admin=await login(setup.slip.loginId);await choose(admin,school,'PRINCIPAL');
  const call=async(method,path,body,version,expected=200,token=admin.accessToken,commandKey=key())=>(await request(`/api/v1/schools/${school}${path}`,{method,body,token,expected,headers:{'Idempotency-Key':commandKey,...(version===undefined?{}:{'If-Match':`"v${version}"`})}})).body;
  const get=(path,token)=>call('GET',path,undefined,undefined,200,token);
  const session=await call('POST','/academic-sessions',{name:'Control session',startsOn:day(-60),endsOn:day(220)});
  let term=await call('POST','/terms',{sessionId:session.id,number:1,startsOn:day(-30),endsOn:day(70)});
  const edited=await call('PATCH',`/academic-sessions/${session.id}`,{name:'Reviewed control session',startsOn:day(-65),endsOn:day(225),reason:'Office checked the approved session calendar'},session.version);assert.equal(edited.version,2);
  await call('PATCH',`/academic-sessions/${session.id}`,{name:'Stale control session',startsOn:day(-65),endsOn:day(225),reason:'A stale editor must reload the session'},session.version,412);
  await call('PATCH',`/academic-sessions/${session.id}`,{name:edited.name,startsOn:day(-20),endsOn:day(225),reason:'Cannot shrink across an existing term'},edited.version,422);
  term=await call('PATCH',`/terms/${term.id}`,{startsOn:day(-29),endsOn:day(71),reason:'Correct unused term dates before school work begins'},term.version);assert.equal(term.version,2);
  const settings=await get('/settings');const preferred=await call('PUT','/active-academic-session',{sessionId:session.id,reason:'Select the reviewed session as the administrator default'},settings.version);assert.equal(preferred.displayPreferenceOnly,true);assert.equal((await get('/settings')).activeSessionId,session.id);
  await call('PUT','/active-academic-session',{sessionId:key(),reason:'A foreign session cannot be selected'},preferred.version,404);
  passed('session and unused-term edits reject stale or invalid ranges; active-session selection is a school-scoped display preference');

  const cls=await call('POST','/classes',{sessionId:session.id,level:'JSS1',arm:'A'});
  const english=await call('POST','/subjects',{code:'ENGLISH',name:'English Language',core:true});
  let spare=await call('POST','/subjects',{code:'SPARE',name:'Spare subject',core:false});
  spare=await call('PATCH',`/subjects/${spare.id}`,{name:'Archived spare subject',active:false,reason:'Retire a subject that has no current assignments'},spare.version);assert.equal(spare.active,false);
  await call('POST','/teachers',{fullName:'Inactive Subject Teacher',phone:'08020345678',eligibleLevels:['JSS1'],subjectIds:[spare.id],assignments:[{classId:cls.id,subjectId:spare.id,weeklyPeriods:1}]},undefined,422);
  const teacher=await call('POST','/teachers',{fullName:'Control English Teacher',phone:'08030345678',eligibleLevels:['JSS1'],subjectIds:[english.id],assignments:[{classId:cls.id,subjectId:english.id,weeklyPeriods:1}]});
  await call('PATCH',`/subjects/${english.id}`,{name:'English Language',active:false,reason:'An assigned current subject cannot be retired'},english.version,409);
  const room=await call('POST','/rooms',{code:'lab-a',name:'Science laboratory A',capacity:40,active:true,reason:'Register the school laboratory for scheduling'});assert.equal(room.code,'LAB-A');
  const retired=await call('PATCH',`/rooms/${room.id}`,{code:room.code,name:room.name,capacity:40,active:false,reason:'Close the unused room pending maintenance'},room.version);assert.equal(retired.active,false);
  const plan=await call('POST','/timetable-plans',{termId:term.id,effectiveFrom:day(-10)});
  const assignment=(await get('/teaching-assignments?classId='+cls.id)).items[0];
  await call('POST',`/timetable-plans/${plan.id}/lessons`,{assignmentId:assignment.id,weekday:1,start:'08:30',durationMinutes:30,room:'lab-a'},plan.version,422);
  await call('PATCH',`/terms/${term.id}`,{startsOn:day(-28),endsOn:day(72),reason:'A dated timetable must keep its accepted term boundary'},term.version,409);
  assert.ok((await get('/rooms?active=false')).items.some(x=>x.id===room.id));
  passed('subject and room lifecycle preserves existing work and blocks new use of inactive resources');

  const office=await call('POST','/staff',{fullName:'Control School Office',phone:'08040345678',role:'SCHOOL_OFFICE'});
  const officeSlip=await poll(async()=>{const r=await request(`/api/v1/schools/${school}/onboarding/STAFF/${office.id}/activation-slips`,{method:'POST',token:admin.accessToken,body:{},headers:{'Idempotency-Key':key()},expected:null});return r.status===200?r.body:false;},'control office account');
  await activate(officeSlip);const officeLogin=await login(officeSlip.loginId);await choose(officeLogin,school,'SCHOOL_OFFICE');
  await call('GET',`/onboarding/STAFF/${office.id}/invitations`,undefined,undefined,403,officeLogin.accessToken);
  await call('GET',`/provisioning/STAFF/${office.id}`,undefined,undefined,403,officeLogin.accessToken);
  await call('PUT','/active-academic-session',{sessionId:session.id,reason:'Only the principal chooses the school default'},preferred.version,403,officeLogin.accessToken);
  const child=await call('POST','/students',{fullName:'Control Pupil',dateOfBirth:'2013-02-12',sex:'FEMALE',classId:cls.id,startsOn:day(-20),metadataVersion:1,fields:{},guardian:{fullName:'Control Guardian',phone:'08050345678',relationship:'MOTHER',relationshipVerified:true,verificationReference:'School checked the synthetic guardian relationship'}},undefined,202);
  const slips=`/onboarding/GUARDIAN/${child.guardianId}/activation-slips`,invitations=`/onboarding/GUARDIAN/${child.guardianId}/invitations`;
  const first=await poll(async()=>{const r=await request(`/api/v1/schools/${school}${slips}`,{method:'POST',token:officeLogin.accessToken,body:{},headers:{'Idempotency-Key':key()},expected:null});return r.status===200?r.body:false;},'control guardian account');
  const firstFlow=(await request('/api/v1/auth/activation/review',{method:'POST',body:{loginId:first.loginId,activationCode:first.activationCode}})).body;
  const second=await call('POST',slips,{},undefined,200,officeLogin.accessToken);
  await request('/api/v1/auth/password/setup',{method:'POST',body:{flowToken:firstFlow.flowToken,newPassword:'Replaced invitation must never work 2026!'},expected:401});
  const secondFlow=(await request('/api/v1/auth/activation/review',{method:'POST',body:{loginId:second.loginId,activationCode:second.activationCode}})).body;
  const revokeKey=key(),revokeBody={reason:'Office revoked the unused invitation before password creation'};
  const revoked=await call('POST',`${invitations}/${second.invitationId}/revoke`,revokeBody,second.version,200,officeLogin.accessToken,revokeKey);assert.equal(revoked.state,'REVOKED');
  assert.deepEqual(await call('POST',`${invitations}/${second.invitationId}/revoke`,revokeBody,second.version,200,officeLogin.accessToken,revokeKey),revoked);
  await request('/api/v1/auth/password/setup',{method:'POST',body:{flowToken:secondFlow.flowToken,newPassword:'Revoked invitation must never work 2026!'},expected:401});
  const history=await get(invitations,officeLogin.accessToken);assert.equal(history.accountStatus,'PENDING');assert.ok(history.invitations.items.some(x=>x.state==='REPLACED'));assert.ok(history.invitations.items.some(x=>x.state==='REVOKED'));assert.ok(!JSON.stringify(history).includes(first.activationCode));
  passed('invitation replacement and revocation invalidate reviewed activation flows; private proofs never appear in invitation history');

  const final=await call('POST',slips,{},undefined,200,officeLogin.accessToken);await activate(final);const parent=await login(final.loginId);await choose(parent,school,'PARENT',child.id);
  assert.equal((await get(invitations)).accountStatus,'ACTIVE');
  await call('POST',slips,{},undefined,409);
  await call('POST',`${invitations}/${final.invitationId}/revoke`,{reason:'A school must not reset an activated global account'},final.version,409);
  await call('GET',invitations,undefined,undefined,403,parent.accessToken);
  await call('GET',`/provisioning/STUDENT/${child.id}`,undefined,undefined,403,parent.accessToken);
  const provisioned=await poll(async()=>{const x=await get(`/provisioning/STUDENT/${child.id}`);return x.items.some(e=>e.state==='PROVISIONED')?x:false;},'indexed provisioned student');assert.ok(provisioned.items.every(x=>!('payload_cipher' in x)));
  const other=await bootstrap('Control foreign school');await activate(other.slip);const otherAdmin=await login(other.slip.loginId);await choose(otherAdmin,other.schoolId,'PRINCIPAL');
  await call('GET',invitations,undefined,undefined,404,otherAdmin.accessToken);
  await call('GET',`/provisioning/STUDENT/${child.id}`,undefined,undefined,404,otherAdmin.accessToken);
  passed('active global accounts cannot be reset by invitation controls; provisioning and invitation data remain school and role scoped');

  const bad=await call('POST','/staff',{fullName:'Failed Provisioning Office',phone:'08060345678',role:'SCHOOL_OFFICE',existingLoginId:'EC-NOT-A-REAL-ACCOUNT',relationshipVerified:true});
  const failed=await poll(async()=>{const x=await get(`/provisioning/STAFF/${bad.id}`);return x.items.find(e=>e.errorCode&&e.state!=='PROCESSING')||false;},'truthful provisioning failure');
  const retryKey=key(),retryBody={eventId:failed.eventId,reason:'Request retry after the operator reviewed the provisioning failure'};
  const retried=await call('POST',`/provisioning/STAFF/${bad.id}/retry`,retryBody,failed.version,200,admin.accessToken,retryKey);assert.equal(retried.eventId,failed.eventId);assert.equal(retried.version,failed.version+1);
  assert.deepEqual(await call('POST',`/provisioning/STAFF/${bad.id}/retry`,retryBody,failed.version,200,admin.accessToken,retryKey),retried);
  await call('POST',`/provisioning/STAFF/${bad.id}/retry`,retryBody,failed.version,412);
  await call('POST',`/provisioning/STAFF/${bad.id}/retry`,{eventId:provisioned.items[0].eventId,reason:'A different subject event cannot be replayed'},failed.version,404);
  passed('failed provisioning retries retain the original event and are idempotent, versioned, and bound to the correct subject');
  let pendingRepair=await poll(async()=>{const x=await get(`/provisioning/STAFF/${bad.id}`);return x.items.find(e=>e.eventId===failed.eventId&&e.errorCode&&e.state!=='PROCESSING')||false;},'reviewable failed account match');
  const repairBody={eventId:failed.eventId,mode:'LINK_EXISTING',existingLoginId:final.loginId,relationshipVerified:true,verificationReference:'Principal checked the synthetic existing global identity',reason:'Correct the supplied existing account without recreating the staff record'};
  await call('POST',`/provisioning/STAFF/${bad.id}/replacement`,{...repairBody,relationshipVerified:false},pendingRepair.version,422);
  const repairKey=key(),repaired=await call('POST',`/provisioning/STAFF/${bad.id}/replacement`,repairBody,pendingRepair.version,200,admin.accessToken,repairKey);assert.notEqual(repaired.eventId,failed.eventId);
  assert.deepEqual(await call('POST',`/provisioning/STAFF/${bad.id}/replacement`,repairBody,pendingRepair.version,200,admin.accessToken,repairKey),repaired);
  const repairedStatus=await poll(async()=>{const x=await get(`/provisioning/STAFF/${bad.id}`);return x.items.some(e=>e.eventId===repaired.eventId&&e.state==='PROVISIONED')?x:false;},'reviewed replacement provisioning');
  assert.equal(repairedStatus.items.find(e=>e.eventId===failed.eventId).state,'SUPERSEDED');
  assert.equal((await get(`/onboarding/STAFF/${bad.id}/invitations`)).loginId,final.loginId);
  assert.equal((await login(final.loginId)).accountId,parent.accountId);
  await u.identityEvent(failed.eventId,'STAFF_ACCESS_CHANGED',{schoolId:school,schoolName:'Synthetic administration school',staff:{id:bad.id,fullName:'Failed Provisioning Office',phone:'08060345678',role:'SCHOOL_OFFICE',active:true,version:1}},409);
  await call('POST',`/provisioning/STAFF/${bad.id}/retry`,retryBody,repaired.originalEventVersion,409);
  passed('reviewed account-match repair cancels the unapplied original, blocks late replay and reuses the verified global login without creating another school person');

}
