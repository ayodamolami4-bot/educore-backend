// Destructive-to-test-credentials CI check. Never run against an actual school.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {randomBytes,randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
const root=resolve(import.meta.dirname,'..');
assert.equal(process.env.EDUCORE_CONTAINER_TEST,'true','Only enable this check in the disposable container test environment.');
const state=JSON.parse(readFileSync(resolve(root,'.dev/bootstrap-state.json'),'utf8'));
assert.equal(state.body.code,'CONTAINER');
assert.equal(state.body.name,'EduCore Container Test School');
const slip=JSON.parse(readFileSync(resolve(root,'.dev/principal-access.json'),'utf8'));
const base='http://127.0.0.1:8080/api/v1';
async function request(path,body,token,expected=200){
 const response=await fetch(base+path,{method:body===undefined?'GET':path==='/me/context'?'PUT':'POST',headers:{'Content-Type':'application/json','Idempotency-Key':randomUUID(),...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 assert.equal(response.status,expected,'Unexpected HTTP status in container check: '+path);
 return response.json();
}
const password='Container test '+randomBytes(24).toString('base64url');
const flow=await request('/auth/activation/review',{loginId:slip.loginId,activationCode:slip.activationCode});
await request('/auth/password/setup',{flowToken:flow.flowToken,newPassword:password});
const tokens=await request('/auth/login',{identifier:slip.loginId,password,installationId:randomUUID(),deviceName:'Disposable container check'});
await request('/me/context',{schoolId:slip.schoolId,role:'PRINCIPAL'},tokens.accessToken);
const account=await request('/me',undefined,tokens.accessToken);
assert.ok(account.id);
console.log('PASS container bootstrap, activation and gateway sign-in');
const recent=await request('/me/reauthenticate',{currentPassword:password},tokens.accessToken);
await request('/me/email-change-requests',{email:'no-smtp@example.invalid',recentAuthToken:recent.recentAuthToken},tokens.accessToken,202);
function scalar(sql){return execFileSync('docker',['compose','exec','-T','postgres','psql','-U','postgres','-d','educore_communication','-Atqc',sql],{cwd:root,encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();}
let waiting=false;
for(let i=0;i<60;i++){
 if(Number(scalar("select count(*) from private_mail where state='WAITING_CONFIGURATION'"))===1){waiting=true;break;}
 await new Promise(r=>setTimeout(r,500));
}
assert.ok(waiting,'Disabled mail must remain visibly waiting for configuration.');
assert.equal(Number(scalar("select count(*) from private_mail where state='SENT'")),0);
const health=await fetch('http://127.0.0.1:8080/actuator/health',{signal:AbortSignal.timeout(5000)});
assert.equal(health.status,200);
console.log('PASS unconfigured email remains waiting while containers stay healthy');
