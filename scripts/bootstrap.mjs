import {existsSync,readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {resolve} from 'node:path';
const root=resolve(import.meta.dirname,'..');process.loadEnvFile(resolve(root,'.env'));
const [code,name,fullName,phone]=process.argv.slice(2);
if(!code||!name||!fullName||!phone){console.error('Usage: node scripts/bootstrap.mjs CODE "School name" "Principal name" PHONE');process.exit(1);}
const folder=resolve(root,'.dev');mkdirSync(folder,{recursive:true,mode:0o700});const statePath=resolve(folder,'bootstrap-state.json');
let state;
if(existsSync(statePath)){state=JSON.parse(readFileSync(statePath,'utf8'));if(state.body.code!==code){console.error('A different school bootstrap is already saved. Preserve its access instructions and use a separate development folder for another bootstrap.');process.exit(1);}}
else{state={body:{schoolId:randomUUID(),code,name,principal:{fullName,phone}},createKey:randomUUID(),slipKey:randomUUID()};writeFileSync(statePath,JSON.stringify(state),{mode:0o600,flag:'wx'});}
const base=process.env.ACADEMICS_ADMIN_URL||`http://127.0.0.1:${process.env.ACADEMICS_ADMIN_PORT||8092}`;
const headers={'Content-Type':'application/json','X-Service-Name':'operator','X-Service-Key':process.env.OPERATOR_TO_ACADEMICS_KEY};
async function post(path,body,extra={}){const response=await fetch(base+path,{method:'POST',headers:{...headers,...extra},body:JSON.stringify(body),signal:AbortSignal.timeout(20000)});let result={};try{result=await response.json();}catch{}return {status:response.status,result};}
const created=await post('/internal/schools',state.body,{'Idempotency-Key':state.createKey});
if(created.status!==200){console.error(`School setup was not accepted (${created.status}, ${created.result.code||'connection error'}).`);process.exit(1);}
for(let i=0;i<60;i++){
 const access=await post('/internal/principal-activation',{schoolId:created.result.schoolId,principalSubjectId:created.result.principalSubjectId,idempotencyKey:state.slipKey});
 if(access.status===200){const path=resolve(folder,'principal-access.json');writeFileSync(path,JSON.stringify({...access.result,schoolId:created.result.schoolId},null,2),{mode:0o600});console.log('School created. Private initial access instructions are in .dev/principal-access.json. Open that file locally; never commit it.');process.exit(0);}
 if(access.status===409){console.log('The principal account is already active. Sign in with its existing credentials. School setup does not reset that account.');process.exit(0);}
 if(![404,503].includes(access.status)){console.error(`Initial access is unavailable (${access.status}, ${access.result.code||'request error'}).`);process.exit(1);}
 await new Promise(r=>setTimeout(r,1000));
}
console.error('Identity provisioning is still pending. Retry this command with the same saved setup state.');process.exitCode=1;
