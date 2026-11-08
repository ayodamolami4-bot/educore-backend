// Disposable CI environment only. This creates a separate fictional DEMO school.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {resolve} from 'node:path';
import {loadOrCreateState, savePrincipalAccess} from './bootstrap-files.mjs';
import {DemoWorkspace, DemoHttpError, readPrivateJson} from './demo-seed-core.mjs';
import {seedDemoSchool} from './demo-school.mjs';

assert.equal(process.env.EDUCORE_DEMO_CHECK, 'true', 'This check requires disposable CI containers.');
const root = resolve(import.meta.dirname, '..');
process.loadEnvFile(resolve(root, '.env'));
const folder = resolve(root, '.dev/demo-seed-check');
const bootstrap = loadOrCreateState(folder, {code: 'DEMO', name: 'EduCore Presentation School',
  principal: {fullName: 'Demo Principal', phone: '08012345678'}});
const origin = 'http://127.0.0.1:8080';
const operatorOrigin = 'http://127.0.0.1:8092';
const operatorKey = process.env.OPERATOR_TO_ACADEMICS_KEY;
async function operator(path, body, key) {
  const response = await fetch(operatorOrigin + path, {method: 'POST', redirect: 'error',
    headers: {'Content-Type': 'application/json', 'X-Service-Name': 'operator',
      'X-Service-Key': operatorKey, ...(key ? {'Idempotency-Key': key} : {})},
    body: JSON.stringify(body), signal: AbortSignal.timeout(20000)});
  return {status: response.status, body: await response.json()};
}
const created = await operator('/internal/schools', bootstrap.body, bootstrap.createKey);
assert.equal(created.status, 200);
let slip;
for (let i = 0; i < 90; i++) {
  const result = await operator('/internal/principal-activation',
    {schoolId: bootstrap.body.schoolId, principalSubjectId: created.body.principalSubjectId,
      idempotencyKey: bootstrap.slipKey});
  if (result.status === 200) {slip = result.body; break;}
  assert.ok([404, 503].includes(result.status));
  await new Promise(resolve => setTimeout(resolve, 500));
}
assert.ok(slip, 'New-school provisioning must finish');
savePrincipalAccess(folder, bootstrap.body.schoolId, slip);
const options = {folder, origin, operatorOrigin, operatorKey, log: () => {}};
const first = await seedDemoSchool(options);
assert.equal(first.completed, true);
let recordWrites = 0;
const second = await seedDemoSchool({...options, fetchImpl: async (url, init) => {
  if (init.method !== 'GET' && !new URL(url).pathname.startsWith('/api/v1/auth/') &&
      new URL(url).pathname !== '/api/v1/me/context') recordWrites++;
  return fetch(url, init);
}});
assert.deepEqual(second, first); assert.equal(recordWrites, 0);
console.log('PASS demo seed creates real PostgreSQL records and a rerun writes no duplicate school records');
const d = new DemoWorkspace(options), credentials = readPrivateJson(folder, 'demo-credentials.json');
assert.equal(Object.keys(credentials.accounts).length, 6);
const actors = {};
for (const [label, account] of Object.entries(d.state.accounts)) {
  const tokens = await d.login(account); assert.ok(tokens, 'Every exported demo login must work');
  await d.request('PUT', '/api/v1/me/context', {schoolId: d.schoolId, role: account.role,
    ...(account.childId ? {childId: account.childId} : {})}, {token: tokens.accessToken});
  actors[label] = {...tokens, loginId: account.loginId};
}
assert.equal((await d.get('/students', actors.admin)).items.length, 4);
assert.equal((await d.get('/classes', actors.teacher)).items.length, 3);
const [child, sibling, foreign] = first.records.studentIds;
assert.equal((await d.get('/students/' + child, actors.student)).id, child);
assert.equal((await d.get('/students/' + child + '/reports', actors.parent)).items.length, 1);
assert.ok((await d.get('/notices', actors.parent)).items.some(n => n.id === first.records.noticeId));
assert.equal((await d.get('/invoices/' + first.records.invoiceIds[0], actors.parent)).invoice.balanceMinor, 2500000);
await assert.rejects(d.get('/students/' + foreign, actors.parent),
  error => error instanceof DemoHttpError && error.status === 404);
await assert.rejects(d.get('/teachers', actors.student),
  error => error instanceof DemoHttpError && error.status === 403);
await assert.rejects(d.request('GET', '/api/v1/schools/' + randomUUID() + '/students'),
  /unexpected request path/);
await d.request('PUT', '/api/v1/me/context', {schoolId: d.schoolId, role: 'PARENT', childId: sibling},
  {token: actors.parent.accessToken});
assert.equal((await d.get('/students/' + sibling, actors.parent)).id, sibling);
console.log('PASS six private demo logins, parent child switching, published reports, fees and role denials');
