import {resolve} from 'node:path';
import {loadOrCreateState, localAdminOrigin, schoolReceipt, savePrincipalAccess} from './bootstrap-files.mjs';
const root=resolve(import.meta.dirname,'..');process.loadEnvFile(resolve(root,'.env'));
const [code,name,fullName,phone]=process.argv.slice(2);
if(!code||!name||!fullName||!phone){console.error('Usage: node scripts/bootstrap.mjs CODE "School name" "Principal name" PHONE');process.exit(1);}
const folder=resolve(root,'.dev');
const state=loadOrCreateState(folder,{code,name,principal:{fullName,phone}});
const base=localAdminOrigin(process.env.ACADEMICS_ADMIN_URL||`http://127.0.0.1:${process.env.ACADEMICS_ADMIN_PORT||8092}`);
const headers={'Content-Type':'application/json','X-Service-Name':'operator','X-Service-Key':process.env.OPERATOR_TO_ACADEMICS_KEY};
async function post(path,body,extra={}) {
 if(!['/internal/schools','/internal/principal-activation'].includes(path)) throw new Error('Unavailable setup operation.');
 const response=await fetch(base+path,{method:'POST',redirect:'error',headers:{...headers,...extra},body:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
 let result={};try{result=await response.json();}catch{}
 return {status:response.status,result};
}
const created=await post('/internal/schools',state.body,{'Idempotency-Key':state.createKey});
if(created.status!==200){console.error(`School setup was not accepted (HTTP ${created.status}).`);process.exit(1);}
const receipt=schoolReceipt(created.result);
if(receipt.schoolId!==state.body.schoolId) throw new Error('School setup returned a different school.');
for(let i=0;i<60;i++){
 const access=await post('/internal/principal-activation',{...receipt,idempotencyKey:state.slipKey});
 if(access.status===200){savePrincipalAccess(folder,receipt.schoolId,access.result);console.log('School created. Private initial access instructions are in .dev/principal-access.json. Open that file locally; never commit it.');process.exit(0);}
 if(access.status===409){console.log('The principal account is already active. Sign in with its existing credentials. School setup does not reset that account.');process.exit(0);}
 if(![404,503].includes(access.status)){console.error(`Initial access is unavailable (HTTP ${access.status}).`);process.exit(1);}
 await new Promise(r=>setTimeout(r,1000));
}
console.error('Identity provisioning is still pending. Retry this command with the same saved setup state.');process.exitCode=1;
