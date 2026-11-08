import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, rmSync, chmodSync, statSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {DemoWorkspace, DemoHttpError, demoPlan, lockWorkspace,
  readPrivateJson, writePrivateJson} from './demo-seed-core.mjs';

function fixture(t) {
  const folder = mkdtempSync(join(tmpdir(), 'educore-demo-seed-'));
  t.after(() => rmSync(folder, {recursive: true, force: true}));
  const schoolId = randomUUID();
  const bootstrap = {body: {schoolId, code: 'DEMO', name: 'EduCore Presentation School',
    principal: {fullName: 'Demo Principal', phone: '08012345678'}},
    createKey: randomUUID(), slipKey: randomUUID()};
  writePrivateJson(folder, 'bootstrap-state.json', bootstrap);
  writePrivateJson(folder, 'principal-access.json', {schoolId, loginId: 'EC-FICTIONAL-TEST'});
  return {folder, schoolId, origin: 'http://127.0.0.1:8080',
    operatorOrigin: 'http://127.0.0.1:8092', operatorKey: 'local-fictional-operator-test-key'};
}
async function server(t, handle) {
  const s = createServer(handle);
  s.listen(0, '127.0.0.1'); await once(s, 'listening');
  t.after(() => new Promise(resolve => {s.closeAllConnections(); s.close(resolve);}));
  return 'http://127.0.0.1:' + s.address().port;
}
const actor = {loginId: 'EC-FICTIONAL-TEST', accessToken: 'fictional-test-access-token'};

test('an accepted request with a lost response reuses its exact key, body and version after restart', async t => {
  const options = fixture(t), received = [], committed = new Map();
  const origin = await server(t, async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    received.push({key: req.headers['idempotency-key'], version: req.headers['if-match'], raw});
    const key = req.headers['idempotency-key'];
    if (!committed.has(key)) {
      committed.set(key, {id: randomUUID(), version: 2});
      req.socket.destroy(); return;
    }
    res.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(committed.get(key)));
  });
  const d = new DemoWorkspace({...options, origin});
  const path = d.school('/students');
  await assert.rejects(d.command('child', actor, 'POST', path, {fullName: 'Original Demo'}, 1),
    /did not finish/);
  const retry = new DemoWorkspace({...options, origin});
  const result = await retry.command('child', actor, 'POST', path, {fullName: 'New edit'}, 99);
  assert.equal(committed.size, 1); assert.deepEqual(received[0], received[1]);
  assert.deepEqual(result, [...committed.values()][0]);
  await retry.command('child', actor, 'POST', path, {fullName: 'Another edit'}, 100);
  assert.equal(received.length, 2, 'Completed commands must not overwrite later developer edits');
  await assert.rejects(retry.command('child', {loginId: 'OTHER'}, 'POST', path, {}, 1), /changed scope/);
});

test('operator and API credentials are confined to loopback origins and redirects are rejected', async t => {
  const options = fixture(t);
  assert.throws(() => new DemoWorkspace({...options, origin: 'https://example.com'}), /loopback/);
  assert.throws(() => new DemoWorkspace({...options, operatorOrigin: 'http://127.0.0.1:8092/internal'}), /loopback/);
  let forwarded = 0;
  const target = await server(t, (req, res) => {forwarded++; res.end('{}');});
  const origin = await server(t, (req, res) => res.writeHead(302, {Location: target}).end());
  const d = new DemoWorkspace({...options, origin});
  await assert.rejects(d.request('POST', '/api/v1/auth/login', {password: 'fictional-test-password'}),
    /did not finish/);
  assert.equal(forwarded, 0);
  await assert.rejects(d.request('GET', '/api/v1/schools/' + randomUUID() + '/students'), /unexpected/);
});

test('only the saved fictional school can be seeded and another origin cannot reuse its state', t => {
  const options = fixture(t);
  const d = new DemoWorkspace(options);
  assert.throws(() => new DemoWorkspace({...options, origin: 'http://127.0.0.1:8085'}), /another school, origin/);
  const bootstrap = readPrivateJson(options.folder, 'bootstrap-state.json');
  bootstrap.body.code = 'ACTUAL-SCHOOL'; writePrivateJson(options.folder, 'bootstrap-state.json', bootstrap);
  assert.throws(() => new DemoWorkspace(options), /only accepts the saved DEMO/);
  assert.equal(d.state.schoolId, options.schoolId);
});

test('credentials contain separate account handovers and exclude live tokens and activation flows', t => {
  const options = fixture(t), d = new DemoWorkspace(options);
  d.state.accounts.student = {name: 'Demo Student', role: 'STUDENT', loginId: 'EC-DEMO-STUDENT', active: true,
    password: 'fictional-random-password', recoveryCode: 'fictional-recovery',
    accessToken: 'DO-NOT-EXPORT-ACCESS', refreshToken: 'DO-NOT-EXPORT-REFRESH', flow: 'DO-NOT-EXPORT-FLOW'};
  d.credentials();
  const value = readPrivateJson(options.folder, 'demo-credentials.json');
  assert.equal(value.fictionalOnly, true); assert.equal(value.accounts.student.role, 'STUDENT');
  assert.doesNotMatch(JSON.stringify(value), /DO-NOT-EXPORT/);
  if (process.platform !== 'win32') {
    assert.equal(statSync(join(options.folder, 'demo-credentials.json')).mode & 0o777, 0o600);
    chmodSync(join(options.folder, 'principal-access.json'), 0o644);
    assert.throws(() => new DemoWorkspace(options), /private regular files/);
  }
});

test('concurrent seeds are refused and invalid private JSON is not echoed in errors', t => {
  const options = fixture(t), release = lockWorkspace(options.folder);
  try { assert.throws(() => lockWorkspace(options.folder), /setup lock exists/); }
  finally { release(); }
  const again = lockWorkspace(options.folder); again();
  writeFileSync(join(options.folder, 'demo-seed-state.json'), '{"PRIVATE_SECRET_SENTINEL":', {mode: 0o600});
  assert.throws(() => new DemoWorkspace(options), error =>
    /Private setup JSON is invalid/.test(error.message) && !error.message.includes('PRIVATE_SECRET_SENTINEL'));
});

test('sample dates follow the Lagos school date and sample attendance uses a previous weekday', () => {
  const plan = demoPlan(new Date('2026-11-08T23:30:00Z'));
  assert.equal(plan.today, '2026-11-09'); assert.equal(plan.attendanceDate, '2026-11-06');
  assert.equal(plan.terms.length, 3); assert.ok(plan.terms[0][1] < plan.attendanceDate);
  const error = new DemoHttpError(422, '/api/v1/auth/login', 'private response text');
  assert.doesNotMatch(error.message, /private response text/);
});
