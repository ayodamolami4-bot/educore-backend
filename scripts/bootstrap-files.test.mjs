import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, writeFileSync, rmSync, chmodSync, statSync, symlinkSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {spawn} from 'node:child_process';
import {loadOrCreateState, localAdminOrigin, savePrincipalAccess, schoolReceipt} from './bootstrap-files.mjs';

const input={code:'TEST',name:'Test School',principal:{fullName:'Test Principal',phone:'08012345678'}};
function folder(t){const path=mkdtempSync(resolve(tmpdir(),'educore-bootstrap-'));t.after(()=>rmSync(path,{recursive:true,force:true}));return path;}

test('concurrent setup processes publish complete state once and preserve retry keys',async t=>{
 const path=folder(t);
 const program=`import {loadOrCreateState} from ${JSON.stringify(new URL('./bootstrap-files.mjs',import.meta.url).href)};process.stdout.write(JSON.stringify(loadOrCreateState(process.argv[1],${JSON.stringify(input)})));`;
 const outcomes=await Promise.all(Array.from({length:8},()=>new Promise((resolve,reject)=>{
  const child=spawn(process.execPath,['--input-type=module','-e',program,path],{stdio:['ignore','pipe','pipe']});
  let output='',error='';child.stdout.on('data',v=>output+=v);child.stderr.on('data',v=>error+=v);
  child.on('error',reject);child.on('exit',code=>code===0?resolve(JSON.parse(output)):reject(new Error(error)));
 })));
 for(const value of outcomes) assert.deepEqual(value,outcomes[0]);
 assert.deepEqual(loadOrCreateState(path,{...input,name:'Retry name'}),outcomes[0]);
 assert.throws(()=>loadOrCreateState(path,{...input,code:'OTHER'}),/different school/);
 assert.deepEqual(JSON.parse(readFileSync(resolve(path,'bootstrap-state.json'))),outcomes[0]);
 if(process.platform!=='win32') assert.equal(statSync(resolve(path,'bootstrap-state.json')).mode&0o777,0o600);
});

test('invalid, oversized, public or linked saved state fails closed',t=>{
 const path=folder(t),statePath=resolve(path,'bootstrap-state.json');
 for(const content of ['{}','{', ' '.repeat(32769)]){
  writeFileSync(statePath,content,{mode:0o600});
  assert.throws(()=>loadOrCreateState(path,input));
 }
 if(process.platform!=='win32'){
  chmodSync(statePath,0o644);assert.throws(()=>loadOrCreateState(path,input),/private regular/);
  rmSync(statePath);
  const target=resolve(path,'untouched.json');writeFileSync(target,'{}',{mode:0o600});
  symlinkSync(target,statePath);assert.throws(()=>loadOrCreateState(path,input));
  assert.equal(readFileSync(target,'utf8'),'{}');
 }
});

test('operator credentials are restricted to loopback origins',()=>{
 for(const value of ['http://127.0.0.1:8092','https://localhost:8092/','http://[::1]:8092'])
  assert.equal(new URL(localAdminOrigin(value)).pathname,'/');
 for(const value of ['https://example.invalid','http://localhost.evil.invalid','http://user@localhost','http://localhost/internal','http://localhost/?next=elsewhere','file:///tmp/config'])
  assert.throws(()=>localAdminOrigin(value));
});

test('only validated private access fields are atomically saved at the fixed destination',t=>{
 const path=folder(t),state=loadOrCreateState(path,input);
 const receipt={schoolId:state.body.schoolId,principalSubjectId:state.createKey};
 assert.deepEqual(schoolReceipt({...receipt,ignored:'extra'}),receipt);
 assert.throws(()=>schoolReceipt({schoolId:'bad',principalSubjectId:state.createKey}));
 const access={loginId:'EC-TEST',activationCode:'test-code-123456789',expiresAt:'2026-11-07T12:00:00Z',delivery:'PRIVATE_SCHOOL_HANDOVER',untrustedField:'ignored'};
 savePrincipalAccess(path,receipt.schoolId,access);
 const saved=JSON.parse(readFileSync(resolve(path,'principal-access.json')));
 assert.equal(saved.schoolId,receipt.schoolId);assert.equal(saved.untrustedField,undefined);
 assert.throws(()=>savePrincipalAccess(path,receipt.schoolId,{...access,activationCode:'!'}));
 assert.deepEqual(JSON.parse(readFileSync(resolve(path,'principal-access.json'))),saved);
 if(process.platform!=='win32') assert.equal(statSync(resolve(path,'principal-access.json')).mode&0o777,0o600);
});
