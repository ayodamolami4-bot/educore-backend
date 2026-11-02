import assert from 'node:assert/strict';
import { createServer as httpServer } from 'node:http';
import { createServer as tcpServer } from 'node:net';
import { randomBytes, randomUUID, createHmac } from 'node:crypto';
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, createWriteStream } from 'node:fs';
import { once } from 'node:events';
import { resolve } from 'node:path';
import { extendedTests } from './extended-tests.mjs';
import { academicTests } from './academic-tests.mjs';
import { learningTests } from './learning-tests.mjs';
import { planningTests } from './planning-tests.mjs';
import { financeTests } from './finance-tests.mjs';

// This test uses real PostgreSQL and real HTTP between the five programs.
// Only the external Paystack and SMTP providers are local test doubles.
const root = resolve(import.meta.dirname, '..');
const runtime = { ...process.env, ...(process.env.EDUCORE_TEST_CONFIG ? JSON.parse(readFileSync(process.env.EDUCORE_TEST_CONFIG, 'utf8')) : {}) };
const names = ['identity', 'academics', 'finance', 'communication'];
const pairs = ['ACADEMICS_TO_IDENTITY','FINANCE_TO_IDENTITY','COMMUNICATION_TO_IDENTITY','IDENTITY_TO_COMMUNICATION','ACADEMICS_TO_COMMUNICATION','FINANCE_TO_COMMUNICATION','FINANCE_TO_ACADEMICS','COMMUNICATION_TO_ACADEMICS','OPERATOR_TO_ACADEMICS'];
for (const pair of pairs) runtime[pair + '_KEY'] ||= randomBytes(36).toString('base64url');
for (const name of names) {
  runtime[name.toUpperCase() + '_RESULT_ENCRYPTION_KEY'] ||= randomBytes(32).toString('base64');
  for (const suffix of ['DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD']) assert.ok(runtime[name.toUpperCase() + '_' + suffix], `Configure ${name.toUpperCase()}_${suffix}`);
}
const temp = resolve(root, '.dev'); mkdirSync(temp, { recursive: true });
const checks = []; const services = []; const smtpMessages = []; const transactions = new Map(); const settlements = new Map(); const refunds=new Map();
const providerKey = 'sk_test_' + randomBytes(24).toString('hex');
const passed = name => { checks.push(name); console.log('PASS', name); };
const pause = ms => new Promise(r => setTimeout(r, ms));
const date = d => d.toISOString().slice(0, 10);
const day = n => { const d = new Date(); d.setUTCHours(12,0,0,0); d.setUTCDate(d.getUTCDate()+n); return date(d); };
const key = () => randomUUID();

const provider = httpServer(async (req,res) => {
  try {
    assert.equal(req.headers.authorization, 'Bearer ' + providerKey);
    let data;
    if (req.method === 'POST' && req.url === '/transaction/initialize') {
      let raw = ''; for await (const chunk of req) raw += chunk;
      const body = JSON.parse(raw); assert.equal(body.currency,'NGN'); assert.match(body.subaccount,/^ACCT_/); assert.ok(Number(body.amount)>0);
      transactions.set(body.reference,{ ...body, amount:Number(body.amount), status:'pending', domain:'test' });
      data = { reference:body.reference, authorization_url:'https://checkout.paystack.com/' + body.reference, access_code:'synthetic-test-code' };
    } else if (req.method === 'GET' && req.url.startsWith('/transaction/verify/')) {
      const reference = req.url.slice('/transaction/verify/'.length); data = transactions.get(reference);
      if (!data) {res.writeHead(404).end(); return;}
    } else if(req.method==='GET'&&req.url.startsWith('/settlement?')){
      const url=new URL(req.url,'http://localhost');data=(settlements.get(url.searchParams.get('subaccount'))||[]).map(({transactions,...x})=>x);
    } else if(req.method==='GET'&&/^\/settlement\/\d+\/transactions/.test(req.url)){
      const id=req.url.split('/')[2];data=[...settlements.values()].flat().find(x=>String(x.id)===id)?.transactions||[];
    } else if(req.method==='GET'&&req.url.startsWith('/refund/')){
      data=refunds.get(req.url.slice('/refund/'.length));if(!data){res.writeHead(404).end();return;}
    } else {res.writeHead(404).end(); return;}
    res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({status:true,data}));
  } catch {res.writeHead(500,{'Content-Type':'application/json'}).end(JSON.stringify({status:false}));}
});

const smtp = tcpServer(socket => {
  socket.setEncoding('utf8'); socket.write('220 localhost EduCore test SMTP\r\n');
  let buffer='',dataMode=false,body=[],recipient='';
  socket.on('data', chunk => {
    buffer += chunk; let pos;
    while((pos=buffer.indexOf('\r\n'))>=0) {
      const line=buffer.slice(0,pos);buffer=buffer.slice(pos+2);
      if(dataMode) {if(line==='.') {smtpMessages.push({recipient,body:body.join('\n').replace(/=\n/g,'')});dataMode=false;body=[];socket.write('250 Accepted\r\n');}else body.push(line);continue;}
      const command=line.toUpperCase();
      if(command.startsWith('EHLO')||command.startsWith('HELO'))socket.write('250-localhost\r\n250 PIPELINING\r\n');
      else if(command.startsWith('MAIL FROM'))socket.write('250 OK\r\n');
      else if(command.startsWith('RCPT TO')) {recipient=line.slice(line.indexOf(':')+1).replace(/[<>]/g,'').trim();socket.write('250 OK\r\n');}
      else if(command==='DATA') {dataMode=true;socket.write('354 Send message\r\n');}
      else if(command==='QUIT')socket.end('221 Bye\r\n');
      else if(command==='RSET'||command==='NOOP')socket.write('250 OK\r\n');
      else socket.write('502 Unsupported test command\r\n');
    }
  });
  socket.on('error',()=>{});
});

async function request(path,{method='GET',body,token,headers={},expected=200,base='http://127.0.0.1:8080'}={}) {
  const response=await fetch(base+path,{method,headers:{...(body===undefined?{}:{'Content-Type':'application/json'}),...(token?{Authorization:'Bearer '+token}:{}),...headers},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
  const raw=await response.text();let value;try{value=raw?JSON.parse(raw):null;}catch{value=null;}
  if(expected!==null)assert.equal(response.status,expected,`${method} ${path}: expected ${expected}, got ${response.status}; ${value?.code || 'no problem code'}`);
  return {status:response.status,body:value};
}
async function poll(fn,label,attempts=60){let last;for(let i=0;i<attempts;i++){last=await fn();if(last)return last;await pause(500);}throw new Error('Timed out: '+label);}
async function start() {
  provider.listen(0,'127.0.0.1');smtp.listen(0,'127.0.0.1');await Promise.all([once(provider,'listening'),once(smtp,'listening')]);
  runtime.PAYSTACK_SECRET_KEY=providerKey;runtime.PAYSTACK_BASE_URL=`http://127.0.0.1:${provider.address().port}`;
  runtime.EDUCORE_EMAIL_ENABLED='true';runtime.SMTP_HOST='127.0.0.1';runtime.SMTP_PORT=String(smtp.address().port);runtime.SMTP_AUTH='false';runtime.SMTP_TLS='false';runtime.SMTP_FROM='no-reply@example.invalid';
  for(const module of [...names.map(n=>'educore-'+n),'api-gateway']) {
    const config=readFileSync(resolve(root,module,'src/main/resources/application.properties'),'utf8');
    const required=[...config.matchAll(/\$\{([A-Z_]+)/g)].map(m=>m[1]);const env={...process.env};
    for(const name of Object.keys(env))if(/_(?:DATABASE_(?:URL|USER|PASSWORD)|RESULT_ENCRYPTION_KEY|TO_[A-Z_]+_KEY)$/.test(name)||pairs.some(p=>name===p+'_KEY'))delete env[name];
    for(const variable of required)if(runtime[variable]!==undefined)env[variable]=runtime[variable];
    env.EDUCORE_OUTBOX_DELAY_MS='500';env.EDUCORE_MAIL_DELAY_MS='500';env.EDUCORE_PAYMENT_VERIFICATION_DELAY_MS='1000';
    for(const variable of Object.keys(env))if(variable.endsWith('_DATABASE_URL')) {
      env[variable]=env[variable].replace('-pooler.','.');
    }
    const log=createWriteStream(resolve(temp,module+'-integration.log'));
    const processHandle=spawn('java',['-Duser.timezone=Africa/Lagos','-Xms32m','-Xmx192m','-jar',resolve(root,module,'target',module+'-0.1.0-SNAPSHOT.jar')],{env,cwd:root,stdio:['ignore','pipe','pipe']});
    processHandle.stdout.pipe(log);processHandle.stderr.pipe(log);services.push({module,processHandle,log});
  }
  await poll(async()=>{for(const port of [8080,8091,8092,8093,8094]){try{const r=await fetch(`http://127.0.0.1:${port}/actuator/health`,{signal:AbortSignal.timeout(1500)});if(r.status!==200)return false;}catch{return false;}}return true;},'five services and PostgreSQL migrations',100);
  passed('all five programs start with real PostgreSQL migrations');
}

const operatorHeaders=()=>({'X-Service-Name':'operator','X-Service-Key':runtime.OPERATOR_TO_ACADEMICS_KEY});
async function bootstrap(label){const schoolId=randomUUID();const created=(await request('/internal/schools',{base:'http://127.0.0.1:8092',method:'POST',headers:{...operatorHeaders(),'Idempotency-Key':key()},body:{schoolId,code:'SMK-'+randomBytes(5).toString('hex'),name:'EduCore '+label+' Test School',principal:{fullName:'Ayo Demo',phone:'08012345678'}}})).body;const idempotencyKey=key();const slip=await poll(async()=>{const r=await request('/internal/principal-activation',{base:'http://127.0.0.1:8092',method:'POST',headers:operatorHeaders(),body:{schoolId,principalSubjectId:created.principalSubjectId,idempotencyKey},expected:null});return r.status===200?r.body:false;},'first principal activation');return {schoolId,slip};}
const password='Learning EduCore safely 2026!';
async function activate(slip){const flow=(await request('/api/v1/auth/activation/review',{method:'POST',body:{loginId:slip.loginId,activationCode:slip.activationCode}})).body;const saved=(await request('/api/v1/auth/password/setup',{method:'POST',body:{flowToken:flow.flowToken,newPassword:password}})).body;assert.ok(saved.newRecoveryCode);return saved.newRecoveryCode;}
async function login(loginId,pass=password){return (await request('/api/v1/auth/login',{method:'POST',body:{identifier:loginId,password:pass,installationId:randomUUID(),deviceName:'Synthetic integration device'}})).body;}
async function choose(tokens,schoolId,role,childId){return request('/api/v1/me/context',{method:'PUT',token:tokens.accessToken,body:{schoolId,role,...(childId?{childId}:{})}});}
async function schoolPost(admin,school,path,body,extra={}){return (await request(`/api/v1/schools/${school}${path}`,{method:'POST',token:admin.accessToken,body,...extra,headers:{'Idempotency-Key':key(),...(extra.headers||{})}})).body;}
async function schoolSlip(admin,school,type,subject){return schoolPost(admin,school,`/onboarding/${type}/${subject}/activation-slips`,{});}

async function test(){
 await request('/api/v1/me',{expected:401});await request('/api/v1/internal/introspect',{method:'POST',body:{},expected:404});passed('gateway rejects anonymous protected requests and internal route access');
 const first=await bootstrap('First');await activate(first.slip);const admin=await login(first.slip.loginId);const school=first.schoolId;await choose(admin,school,'PRINCIPAL');
 await request('/api/v1/auth/activation/review',{method:'POST',body:{loginId:first.slip.loginId,activationCode:first.slip.activationCode},expected:401});passed('school bootstrap, activation, sign-in and one-time proof consumption');
 const me=(await request('/api/v1/me',{token:admin.accessToken})).body;
 await request('/api/v1/me',{method:'PATCH',token:admin.accessToken,headers:{'If-Match':'"v1"'},body:{phone:'08012345678'},expected:412});
 await request('/api/v1/me',{method:'PATCH',token:admin.accessToken,headers:{'If-Match':`"v${me.version}"`},body:{phone:'08012345678'}});passed('stale account versions cannot overwrite a later update');
 const other=await bootstrap('Second');await request(`/api/v1/schools/${other.schoolId}/metadata`,{token:admin.accessToken,expected:404});passed('administrator cannot cross a school boundary');
 const session=await schoolPost(admin,school,'/academic-sessions',{name:'Smoke session',startsOn:day(-120),endsOn:day(240)});
 const term=await schoolPost(admin,school,'/terms',{sessionId:session.id,number:1,startsOn:day(-120),endsOn:day(90)});
 await schoolPost(admin,school,'/terms',{sessionId:session.id,number:2,startsOn:day(91),endsOn:day(160)});await schoolPost(admin,school,'/terms',{sessionId:session.id,number:3,startsOn:day(161),endsOn:day(240)});
 const armA=await schoolPost(admin,school,'/classes',{sessionId:session.id,level:'JSS1',arm:'A'});const armB=await schoolPost(admin,school,'/classes',{sessionId:session.id,level:'JSS1',arm:'B'});
 const english=await schoolPost(admin,school,'/subjects',{code:'ENGLISH',name:'English Language',core:true});const math=await schoolPost(admin,school,'/subjects',{code:'MATHEMATICS',name:'Mathematics',core:true});
 const teacher=await schoolPost(admin,school,'/teachers',{fullName:'Ibrahim Demo',phone:'08022345678',eligibleLevels:['JSS1'],subjectIds:[english.id,math.id],assignments:[{classId:armA.id,subjectId:english.id,weeklyPeriods:2},{classId:armB.id,subjectId:math.id,weeklyPeriods:1}]});
 const teacherTwo=await schoolPost(admin,school,'/teachers',{fullName:'Bisi Demo',phone:'08032345678',eligibleLevels:['JSS1'],subjectIds:[math.id],assignments:[{classId:armA.id,subjectId:math.id,weeklyPeriods:1}]});
 const enrolBody={admissionType:'LEGACY_MIGRATION',legacyReference:'Synthetic existing pupil migration',admissionNumber:'SMK-001',fullName:'Zainab Demo',dateOfBirth:'2010-06-12',sex:'FEMALE',classId:armA.id,startsOn:day(-20),metadataVersion:1,fields:{homeAddress:'Fictional test address'},guardian:{fullName:'Ada Demo',phone:'08042345678',relationshipVerified:true,verificationReference:'Synthetic school verification',relationship:'Mother'}};
 const enrolKey=key();const students=await Promise.all([1,2].map(()=>schoolPost(admin,school,'/students',enrolBody,{headers:{'Idempotency-Key':enrolKey},expected:202})));assert.equal(students[0].id,students[1].id);const student=students[0];
 await schoolPost(admin,school,'/students',{...enrolBody,fullName:'Different Demo'},{headers:{'Idempotency-Key':enrolKey},expected:409});passed('concurrent enrolment retries create one student and retain the original result');
 await poll(async()=>{const p=(await request(`/api/v1/schools/${school}/students/${student.id}`,{token:admin.accessToken})).body;return p.onboardingStatus==='ACCOUNTS_PROVISIONED';},'student and guardian account provisioning');
 const parentSlip=await schoolSlip(admin,school,'GUARDIAN',student.guardianId);let parentRecovery=await activate(parentSlip);let parent=await login(parentSlip.loginId);await choose(parent,school,'PARENT',student.id);
 const parentView=(await request(`/api/v1/schools/${school}/students/${student.id}`,{token:parent.accessToken})).body;assert.equal(parentView.id,student.id);assert.ok(!('guardians' in parentView));
 await choose(parent,school,'PRINCIPAL',student.id).then(()=>{throw new Error('Parent granted principal role');},()=>{});
 await request('/api/v1/me/context',{method:'PUT',token:parent.accessToken,body:{schoolId:school,role:'PRINCIPAL'},expected:403});passed('automatic guardian access does not expose staff fields or grant administrator privileges');
 const secondChild=await schoolPost(admin,school,'/students',{...enrolBody,admissionType:'LEGACY_MIGRATION',legacyReference:'Synthetic existing pupil migration',admissionNumber:'SMK-002',fullName:'Mariam Demo',guardian:{existingGuardianId:student.guardianId,relationship:'Mother'}},{expected:202});
 await poll(async()=>{const c=(await request('/api/v1/me/contexts',{token:parent.accessToken})).body;return c.items.filter(i=>i.role==='PARENT').length===2;},'existing guardian linked to second child');
 await choose(parent,school,'PARENT',secondChild.id);await request(`/api/v1/schools/${school}/students/${student.id}`,{token:parent.accessToken,expected:404});await choose(parent,school,'PARENT',student.id);passed('a second child reuses the parent account and child selection is enforced');
 const otherRecovery=await activate(other.slip);const otherAdmin=await login(other.slip.loginId);await choose(otherAdmin,other.schoolId,'PRINCIPAL');
 const otherSession=await schoolPost(otherAdmin,other.schoolId,'/academic-sessions',{name:'Other school session',startsOn:day(-120),endsOn:day(240)});
 const otherTerm=await schoolPost(otherAdmin,other.schoolId,'/terms',{sessionId:otherSession.id,number:1,startsOn:day(-120),endsOn:day(90)});
 const otherClass=await schoolPost(otherAdmin,other.schoolId,'/classes',{sessionId:otherSession.id,level:'SSS1',arm:'A'});
 const crossSchoolChild=await schoolPost(otherAdmin,other.schoolId,'/students',{...enrolBody,admissionType:'LEGACY_MIGRATION',legacyReference:'Synthetic existing pupil migration',admissionNumber:'SMK-001',fullName:'Tunde Demo',classId:otherClass.id,department:'SCIENCE',guardian:{fullName:'Ada Demo',phone:'08052345678',existingLoginId:parentSlip.loginId,relationshipVerified:true,verificationReference:'Synthetic verified existing parent',relationship:'Mother'}},{expected:202});
 await poll(async()=>{const contexts=(await request('/api/v1/me/contexts',{token:parent.accessToken})).body.items;return contexts.some(c=>c.schoolId===other.schoolId&&c.childId===crossSchoolChild.id);},'global parent linked to another school');
 const stillSame=await login(parentSlip.loginId);assert.equal(stillSame.accountId,parent.accountId);await choose(parent,other.schoolId,'PARENT',crossSchoolChild.id);await request(`/api/v1/schools/${other.schoolId}/students/${crossSchoolChild.id}`,{token:parent.accessToken});
 await schoolPost(otherAdmin,other.schoolId,`/students/${crossSchoolChild.id}/guardians/${crossSchoolChild.guardianId}/revoke`,{reason:'Synthetic school relationship revocation'});
 await request(`/api/v1/schools/${other.schoolId}/students/${crossSchoolChild.id}`,{token:parent.accessToken,expected:404});await choose(parent,school,'PARENT',student.id);
 assert.equal((await request('/api/v1/me',{token:parent.accessToken})).body.phone,'+2348042345678');passed('one global parent spans schools; school revocation does not reset or disable that identity');

 const teacherSlip=await schoolSlip(admin,school,'STAFF',teacher.id);await activate(teacherSlip);const teacherLogin=await login(teacherSlip.loginId);await choose(teacherLogin,school,'TEACHER');
 await request(`/api/v1/schools/${school}/classes/${armA.id}/attendance-teacher`,{method:'PUT',token:admin.accessToken,headers:{'If-Match':'"v1"','Idempotency-Key':key()},body:{teacherId:teacher.id}});
 let attendanceDate=new Date();attendanceDate.setUTCDate(attendanceDate.getUTCDate()-1);while([0,6].includes(attendanceDate.getUTCDay()))attendanceDate.setUTCDate(attendanceDate.getUTCDate()-1);
 const register=await schoolPost(teacherLogin,school,'/attendance/registers',{classId:armA.id,onDate:date(attendanceDate)});
 const marked=(await request(`/api/v1/schools/${school}/attendance/registers/${register.id}/marks`,{method:'PUT',token:teacherLogin.accessToken,headers:{'If-Match':`"v${register.version}"`,'Idempotency-Key':key()},body:{rows:[{studentId:student.id,status:'ABSENT'},{studentId:secondChild.id,status:'PRESENT'}]}})).body;
 const before=(await request(`/api/v1/schools/${school}/attendance/overview?on=${date(attendanceDate)}`,{token:admin.accessToken})).body;assert.equal(before.absences.length,0);assert.equal(before.submittedRegisters,0);
 const submitted=await schoolPost(teacherLogin,school,`/attendance/registers/${register.id}/submit`,undefined,{headers:{'If-Match':`"v${marked.version}"`}});
 const after=(await request(`/api/v1/schools/${school}/attendance/overview?on=${date(attendanceDate)}`,{token:admin.accessToken})).body;assert.equal(after.absences.length,1);assert.equal(after.coverageComplete,false);assert.ok(after.missingRegisters>0);passed('only submitted attendance feeds absences; missing registers remain incomplete coverage');
 await schoolPost(admin,school,`/attendance/registers/${register.id}/absence-alerts`,{studentId:student.id,reviewReason:'Office reviewed this synthetic absence'});
 await poll(async()=>{const n=(await request(`/api/v1/me/notifications?schoolId=${school}&category=LEARNING`,{token:parent.accessToken})).body;return n.items.length>0;},'reviewed absence notification');
 const corrected=await schoolPost(admin,school,`/attendance/registers/${register.id}/corrections`,{studentId:student.id,status:'LATE',reason:'Office checked the signed late arrival record'},{headers:{'If-Match':`"v${submitted.version}"`}});assert.equal(corrected.rows.find(r=>r.studentId===student.id).status,'LATE');passed('office review, private absence notification and reasoned attendance correction');
 const assignments=(await request(`/api/v1/schools/${school}/teaching-assignments`,{token:admin.accessToken})).body.items;
 const engAssignment=assignments.find(a=>a.teacherId===teacher.id&&a.classId===armA.id);const mathB=assignments.find(a=>a.teacherId===teacher.id&&a.classId===armB.id);const mathA=assignments.find(a=>a.teacherId===teacherTwo.id);
 const plan=await schoolPost(admin,school,'/timetable-plans',{termId:term.id,effectiveFrom:day(-15)});let planVersion=plan.version;
 const add=async(body,expected=200)=>{const r=await schoolPost(admin,school,`/timetable-plans/${plan.id}/lessons`,body,{headers:{'If-Match':`"v${planVersion}"`},expected});if(expected===200)planVersion=r.planVersion;return r;};
 await add({assignmentId:engAssignment.id,weekday:1,start:'08:30',durationMinutes:60,room:'R1'});
 await add({assignmentId:mathB.id,weekday:1,start:'09:00',durationMinutes:30,room:'R2'},409);
 await add({assignmentId:mathA.id,weekday:1,start:'08:30',durationMinutes:30,room:'R2'},409);
 await add({assignmentId:mathB.id,weekday:1,start:'09:30',durationMinutes:30,room:'R1'});
 await add({assignmentId:mathA.id,weekday:1,start:'09:30',durationMinutes:30,room:'R1'},409);
 await add({assignmentId:mathA.id,weekday:1,start:'10:00',durationMinutes:30,room:'R1'});
 await add({assignmentId:mathA.id,weekday:1,start:'11:00',durationMinutes:60},422);await add({assignmentId:mathA.id,weekday:3,start:'08:00',durationMinutes:30},422);await add({assignmentId:mathA.id,weekday:5,start:'12:00',durationMinutes:30},422);
 await schoolPost(admin,school,`/timetable-plans/${plan.id}/publish`,undefined,{headers:{'If-Match':`"v${planVersion}"`}});
 const familyTimetable=(await request(`/api/v1/schools/${school}/timetable`,{token:parent.accessToken})).body;assert.equal(familyTimetable.published,true);assert.equal(familyTimetable.items.length,2);assert.ok(familyTimetable.items.every(l=>l.classId===armA.id));passed('teacher/class/room conflicts and Nigerian activity blocks prevent invalid publication');
 const invoice=(await schoolPost(admin,school,'/invoices',{studentId:student.id,termId:term.id,description:'Synthetic term fees',lines:[{description:'Tuition',amountMinor:500000,quantity:1}],dueOn:day(30),partialAllowed:true})).invoice;
 const paymentBody={amountMinor:200000,method:'CASH',reference:'CASH-'+key(),verificationReference:'Synthetic cashier receipt',reason:'Cash confirmed at the school office'};const paymentKey=key();
 const firstPayment=await schoolPost(admin,school,`/invoices/${invoice.id}/manual-payments`,paymentBody,{headers:{'If-Match':'"v1"','Idempotency-Key':paymentKey}});
 const retryPayment=await schoolPost(admin,school,`/invoices/${invoice.id}/manual-payments`,paymentBody,{headers:{'If-Match':'"v1"','Idempotency-Key':paymentKey}});assert.equal(firstPayment.paymentId,retryPayment.paymentId);assert.equal(firstPayment.invoice.balanceMinor,300000);
 await schoolPost(admin,school,`/invoices/${invoice.id}/manual-payments`,paymentBody,{headers:{'If-Match':'"v2"'},expected:409});
 await schoolPost(admin,school,`/invoices/${invoice.id}/manual-payments`,{...paymentBody,amountMinor:300000,method:'BANK',reference:'BANK-'+key()},{headers:{'If-Match':'"v2"'}});
 const paid=(await request(`/api/v1/schools/${school}/invoices/${invoice.id}`,{token:parent.accessToken})).body;assert.equal(paid.invoice.status,'PAID');assert.equal(paid.confirmedPayments.length,2);passed('cash/bank part payments reconcile once and duplicate references cannot allocate twice');
 await schoolPost(admin,school,'/invoices/payment-settings',{subaccount:'ACCT_SYNTHETICTEST'});
 const online=(await schoolPost(admin,school,'/invoices',{studentId:student.id,termId:term.id,description:'Synthetic second invoice',lines:[{description:'School charge',amountMinor:500000,quantity:1}],dueOn:day(30),partialAllowed:true})).invoice;
 const intent=await schoolPost(parent,school,`/invoices/${online.id}/checkout`,{amountMinor:350000,email:'synthetic-payer@example.invalid'},{expected:202});assert.equal(intent.state,'PENDING');
 await request('/api/v1/webhooks/paystack',{method:'POST',body:{event:'charge.success',data:{reference:intent.reference}},headers:{'x-paystack-signature':'0'.repeat(128)},expected:401});
 transactions.get(intent.reference).status='success';const event={event:'charge.success',data:{reference:intent.reference}};const raw=JSON.stringify(event);const signature=createHmac('sha512',providerKey).update(raw).digest('hex');
 for(let i=0;i<2;i++)await request('/api/v1/webhooks/paystack',{method:'POST',body:event,headers:{'x-paystack-signature':signature}});
 await poll(async()=>{const i=(await request(`/api/v1/schools/${school}/payment-intents/${intent.id}`,{token:parent.accessToken})).body;return i.state==='CONFIRMED';},'server-side Paystack verification');
 const onlineResult=(await request(`/api/v1/schools/${school}/invoices/${online.id}`,{token:parent.accessToken})).body;assert.equal(onlineResult.invoice.paidMinor,350000);assert.equal(onlineResult.confirmedPayments.length,1);passed('signed duplicate webhooks and provider verification allocate one payment only');
 const caseCreated=await schoolPost(parent,school,'/complaints',{studentId:student.id,category:'ATTENDANCE',subject:'Synthetic attendance query',body:'Please review this fictional attendance record.'});assert.ok(caseCreated.firstResponseDueAt);
 const internal=await schoolPost(admin,school,`/complaints/${caseCreated.id}/replies`,{body:'STAFF ONLY SYNTHETIC NOTE',internal:true},{headers:{'If-Match':`"v${caseCreated.version}"`}});
 const parentCase=(await request(`/api/v1/schools/${school}/complaints/${caseCreated.id}`,{token:parent.accessToken})).body;assert.ok(parentCase.entries.every(e=>e.visibility==='PUBLIC'));assert.ok(!JSON.stringify(parentCase).includes('STAFF ONLY'));
 await schoolPost(parent,school,`/complaints/${caseCreated.id}/replies`,{body:'Attempt staff note',internal:true},{headers:{'If-Match':`"v${parentCase.version}"`},expected:403});
 const replied=await schoolPost(admin,school,`/complaints/${caseCreated.id}/replies`,{body:'The school has reviewed the fictional record.',internal:false},{headers:{'If-Match':`"v${internal.version}"`}});assert.ok(replied.firstResponseAt);
 const closed=await schoolPost(admin,school,`/complaints/${caseCreated.id}/close`,{reason:'The fictional attendance query has been resolved.'},{headers:{'If-Match':`"v${replied.version}"`}});assert.equal(closed.state,'CLOSED');passed('complaint deadlines use teaching days and staff-only notes stay private');
 const recent=(await request('/api/v1/me/reauthenticate',{method:'POST',token:parent.accessToken,body:{currentPassword:password}})).body;
 const emailAddress='synthetic-'+key()+'@example.invalid';await request('/api/v1/me/email-change-requests',{method:'POST',token:parent.accessToken,headers:{'Idempotency-Key':key()},body:{email:emailAddress,recentAuthToken:recent.recentAuthToken},expected:202});
 assert.equal((await request('/api/v1/me',{token:parent.accessToken})).body.verifiedEmail,undefined);
 const mail=await poll(async()=>smtpMessages.find(m=>m.recipient===emailAddress&&m.body.includes('educore://verify-email')),'test SMTP email verification delivery');const verificationToken=mail.body.match(/educore:\/\/verify-email\?token=([A-Za-z0-9_-]+)/)?.[1];assert.ok(verificationToken);
 await request('/api/v1/auth/email-verifications/confirm',{method:'POST',body:{token:verificationToken}});assert.equal((await request('/api/v1/me',{token:parent.accessToken})).body.verifiedEmail,emailAddress);await request('/api/v1/auth/email-verifications/confirm',{method:'POST',body:{token:verificationToken},expected:401});passed('email stays unverified until the single-use link is confirmed; SMTP is tested locally');
 const flow=(await request('/api/v1/auth/recovery/code/review',{method:'POST',body:{loginId:parentSlip.loginId,recoveryCode:parentRecovery}})).body;
 await request('/api/v1/auth/recovery/code/review',{method:'POST',body:{loginId:parentSlip.loginId,recoveryCode:parentRecovery},expected:401});
 const newPassword='  A different EduCore phrase 2026!  ';await request('/api/v1/auth/password/setup',{method:'POST',body:{flowToken:flow.flowToken,newPassword:newPassword}});await request('/api/v1/me',{token:parent.accessToken,expected:401});
 await request('/api/v1/auth/login',{method:'POST',body:{identifier:parentSlip.loginId,password:newPassword.trim(),installationId:key(),deviceName:'Synthetic device'},expected:401});parent=await login(parentSlip.loginId,newPassword);passed('saved recovery proof is single-use, revokes old sessions and preserves password spaces');
 const renewed=(await request('/api/v1/auth/refresh',{method:'POST',body:{refreshToken:parent.refreshToken}})).body;await request('/api/v1/me',{token:renewed.accessToken});await request('/api/v1/auth/refresh',{method:'POST',body:{refreshToken:parent.refreshToken},expected:401});await request('/api/v1/me',{token:renewed.accessToken,expected:401});passed('refresh-token replay revokes the committed session family');
 await request('/internal/events',{base:'http://127.0.0.1:8091',method:'POST',headers:{'X-Service-Name':'academics','X-Service-Key':runtime.ACADEMICS_TO_COMMUNICATION_KEY},body:{},expected:401});passed('a communication receiver credential cannot impersonate academics to identity');
}

try {await start();await test();const helpers={bootstrap,activate,login,choose,request,schoolPost,schoolSlip,poll,passed,key,day,transactions,settlements,refunds};await extendedTests(helpers);await academicTests(helpers);const learning=await learningTests(helpers);await planningTests(helpers,learning);await financeTests(helpers,learning);writeFileSync(resolve(temp,'integration-results.json'),JSON.stringify({passed:true,checks,total:checks.length},null,2));console.log(`Integration checks passed: ${checks.length}`);}
catch(error){console.error(error.message);writeFileSync(resolve(temp,'integration-results.json'),JSON.stringify({passed:false,checks,error:error.message},null,2));for(const {module} of services){let log='';try{log=readFileSync(resolve(temp,module+'-integration.log'),'utf8').split('\n').filter(l=>/Caused by:|ERROR|APPLICATION FAILED/.test(l)).slice(-8).join('\n');}catch{}if(log)console.error(module+' diagnostics:\n'+log);}process.exitCode=1;}
finally {for(const {processHandle} of services)processHandle.kill('SIGTERM');await pause(1000);for(const {processHandle,log} of services){if(processHandle.exitCode===null)processHandle.kill('SIGKILL');log.end();}provider.closeAllConnections();provider.close();smtp.close();}
