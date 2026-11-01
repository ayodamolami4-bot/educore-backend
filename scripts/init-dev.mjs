import {randomBytes} from 'node:crypto';
import {existsSync,mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
const root=resolve(import.meta.dirname,'..');
if(existsSync(resolve(root,'.env'))){console.log('.env already exists. Existing credentials were preserved.');process.exit(0);}
const env={POSTGRES_ADMIN_PASSWORD:process.env.POSTGRES_ADMIN_PASSWORD||randomBytes(32).toString('base64url'),POSTGRES_PORT:process.env.POSTGRES_PORT||'5432'};
for(const name of ['IDENTITY','ACADEMICS','FINANCE','COMMUNICATION']){
 env[name+'_DATABASE_URL']=`jdbc:postgresql://127.0.0.1:${env.POSTGRES_PORT}/educore_${name.toLowerCase()}`;
 env[name+'_DATABASE_USER']='educore_'+name.toLowerCase();env[name+'_DATABASE_PASSWORD']=randomBytes(32).toString('base64url');
 env[name+'_RESULT_ENCRYPTION_KEY']=randomBytes(32).toString('base64');
}
for(const pair of ['ACADEMICS_TO_IDENTITY','FINANCE_TO_IDENTITY','COMMUNICATION_TO_IDENTITY','IDENTITY_TO_COMMUNICATION','ACADEMICS_TO_COMMUNICATION','FINANCE_TO_COMMUNICATION','FINANCE_TO_ACADEMICS','COMMUNICATION_TO_ACADEMICS','OPERATOR_TO_ACADEMICS'])env[pair+'_KEY']=randomBytes(36).toString('base64url');
env.EDUCORE_CORS_ORIGINS='http://localhost:8081,http://localhost:4173';env.PAYSTACK_SECRET_KEY='';env.EDUCORE_EMAIL_ENABLED='false';
writeFileSync(resolve(root,'.env'),Object.entries(env).map(([k,v])=>k+'='+v).join('\n')+'\n',{mode:0o600,flag:'wx'});
mkdirSync(resolve(root,'.dev'),{recursive:true,mode:0o700});
writeFileSync(resolve(root,'.dev/local-runtime.json'),JSON.stringify(env),{mode:0o600,flag:'wx'});
console.log('Created private local development configuration. No credentials were printed.');
