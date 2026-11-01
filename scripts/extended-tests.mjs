import assert from 'node:assert/strict';

export async function extendedTests(u) {
 const {bootstrap,activate,login,choose,request,schoolPost,poll,passed,key,day}=u;
 const setup=await bootstrap('Administration');await activate(setup.slip);
 const school=setup.schoolId,admin=await login(setup.slip.loginId);await choose(admin,school,'PRINCIPAL');
 const post=(path,body,extra={})=>schoolPost(admin,school,path,body,extra);
 const get=async(path,token=admin.accessToken)=>(await request(`/api/v1/schools/${school}${path}`,{token})).body;
 const slip=async(type,id)=>poll(async()=>{const r=await request(`/api/v1/schools/${school}/onboarding/${type}/${id}/activation-slips`,{method:'POST',token:admin.accessToken,body:{},headers:{'Idempotency-Key':key()},expected:null});return r.status===200?r.body:false;},'extended account provisioning');
 const office=await post('/staff',{fullName:'Office Demo',phone:'08052345678',role:'SCHOOL_OFFICE',staffNumber:'OFF-001'});
 const officeSlip=await slip('STAFF',office.id);await activate(officeSlip);const officeLogin=await login(officeSlip.loginId);await choose(officeLogin,school,'SCHOOL_OFFICE');
 await schoolPost(officeLogin,school,'/staff',{fullName:'Forbidden Demo',phone:'08062345678',role:'ACCOUNTS'},{expected:403});
 const staffList=await get('/staff');const principal=staffList.items.find(p=>p.role==='PRINCIPAL');assert.ok(principal);
 await post(`/staff/${principal.id}/access`,{role:'SCHOOL_OFFICE',active:true,reason:'Attempt to remove the only principal'},{headers:{'If-Match':`"v${principal.version}"`},expected:409});
 const accounts=await post('/staff',{fullName:'Accounts Demo',phone:'08062345678',role:'ACCOUNTS',staffNumber:'ACC-001'});
 await schoolPost(officeLogin,school,`/onboarding/STAFF/${accounts.id}/activation-slips`,{},{expected:403});
 const accountsSlip=await slip('STAFF',accounts.id);await activate(accountsSlip);const accountsLogin=await login(accountsSlip.loginId);await choose(accountsLogin,school,'ACCOUNTS');
 await request(`/api/v1/schools/${school}/invoices`,{token:accountsLogin.accessToken});
 await post(`/staff/${accounts.id}/access`,{role:'ACCOUNTS',active:false,reason:'School access removed after authorised review'},{headers:{'If-Match':`"v${accounts.version}"`}});
 await poll(async()=>{const contexts=(await request('/api/v1/me/contexts',{token:accountsLogin.accessToken})).body;return !contexts.items.some(c=>c.schoolId===school&&c.role==='ACCOUNTS');},'staff access revocation');
 await request('/api/v1/me',{token:accountsLogin.accessToken});
 passed('office/accounts onboarding, administrator invitation limits, last-principal protection and school-only access revocation');

 const session=await post('/academic-sessions',{name:'Administration test session',startsOn:day(-200),endsOn:day(150)});
 const classA=await post('/classes',{sessionId:session.id,level:'JSS1',arm:'A'}),classB=await post('/classes',{sessionId:session.id,level:'JSS1',arm:'B'});
 const student=await post('/students',{admissionNumber:'ADM-001',fullName:'Student Demo',dateOfBirth:'2012-02-12',sex:'MALE',classId:classA.id,startsOn:day(-30),metadataVersion:1,fields:{},guardian:{fullName:'Guardian Demo',phone:'08072345678',relationship:'FATHER',relationshipVerified:true,verificationReference:'Synthetic guardian check'}},{expected:202});
 const patched=(await request(`/api/v1/schools/${school}/students/${student.id}`,{method:'PATCH',token:admin.accessToken,headers:{'Idempotency-Key':key(),'If-Match':'"v1"'},body:{fullName:'Corrected Demo',dateOfBirth:'2012-02-12',sex:'MALE',fields:{},metadataVersion:1,reason:'School checked the birth record'}})).body;
 assert.equal(patched.fullName,'Corrected Demo');
 await post(`/students/${student.id}/transfers`,{classId:classB.id,effectiveOn:day(-10),reason:'Class arm move approved by the office'},{headers:{'If-Match':`"v${patched.version}"`}});
 const profile=await get(`/students/${student.id}`);assert.equal(profile.enrolments.length,2);assert.ok(profile.enrolments.some(e=>e.classId===classA.id&&e.endsOn));
 await post(`/students/${student.id}/withdrawal`,{effectiveOn:day(-1),reason:'Family confirmed withdrawal from the school'},{headers:{'If-Match':`"v${profile.version}"`}});
 assert.equal((await get(`/students/${student.id}`)).status,'WITHDRAWN');
 passed('reasoned student corrections, dated class-arm transfer and withdrawal preserve earlier placements');
}
