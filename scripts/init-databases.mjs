import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {spawn} from 'node:child_process';
const root=resolve(import.meta.dirname,'..');
const env=JSON.parse(readFileSync(resolve(root,'.dev/local-runtime.json'),'utf8'));
let sql='';
for(const name of ['IDENTITY','ACADEMICS','FINANCE','COMMUNICATION']){
 const user=env[name+'_DATABASE_USER'],password=env[name+'_DATABASE_PASSWORD'],database='educore_'+name.toLowerCase();
 if(!/^[a-z_]+$/.test(user)||!/^[A-Za-z0-9_-]+$/.test(password))throw new Error('Unexpected generated database configuration.');
 sql+=`CREATE ROLE ${user} LOGIN PASSWORD '${password}';\nCREATE DATABASE ${database} OWNER ${user};\nREVOKE CONNECT ON DATABASE ${database} FROM PUBLIC;\n`;
}
const child=spawn('psql',['-h','127.0.0.1','-p',env.POSTGRES_PORT,'-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1'],{env:{...process.env,PGPASSWORD:env.POSTGRES_ADMIN_PASSWORD},stdio:['pipe','inherit','inherit']});
child.on('error',()=>{console.error('Install the PostgreSQL client or initialise through Docker Compose.');process.exitCode=1;});
child.stdin.end(sql);child.on('exit',code=>{process.exitCode=code||0;});
