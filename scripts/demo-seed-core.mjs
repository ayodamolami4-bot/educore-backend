import {constants, mkdirSync, openSync, closeSync, fstatSync, readFileSync,
  writeFileSync, fsyncSync, renameSync, unlinkSync} from 'node:fs';
import {randomBytes, randomUUID} from 'node:crypto';
import {resolve} from 'node:path';
import {localAdminOrigin} from './bootstrap-files.mjs';

const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const privateNames = new Set(['bootstrap-state.json', 'principal-access.json',
  'demo-seed-state.json', 'demo-credentials.json']);

function pathIn(folder, name) {
  if (!privateNames.has(name)) throw new Error('Unknown private setup file.');
  return resolve(folder, name);
}

export function readPrivateJson(folder, name, optional = false) {
  let fd;
  try {
    fd = openSync(pathIn(folder, name), constants.O_RDONLY | (constants.O_NOFOLLOW || 0) |
      (constants.O_NONBLOCK || 0));
    const stat = fstatSync(fd);
    if (!stat.isFile() || stat.size > 2 * 1024 * 1024 ||
        (process.platform !== 'win32' && (stat.mode & 0o077) !== 0))
      throw new Error('Setup files must be small private regular files.');
    try { return JSON.parse(readFileSync(fd, 'utf8')); }
    catch { throw new Error('Private setup JSON is invalid; preserve the file for review.'); }
  } catch (error) {
    if (optional && error.code === 'ENOENT') return undefined;
    throw error;
  } finally { if (fd !== undefined) closeSync(fd); }
}

export function writePrivateJson(folder, name, value) {
  const temporary = resolve(folder, '.demo-seed-' + randomUUID() + '.tmp');
  const fd = openSync(temporary, constants.O_WRONLY | constants.O_CREAT |
    constants.O_EXCL | (constants.O_NOFOLLOW || 0), 0o600);
  try {
    writeFileSync(fd, JSON.stringify(value, null, 2) + '\n', 'utf8');
    fsyncSync(fd);
  } catch (error) {
    closeSync(fd); unlinkSync(temporary); throw error;
  }
  closeSync(fd);
  try { renameSync(temporary, pathIn(folder, name)); }
  catch (error) { unlinkSync(temporary); throw error; }
}

export function lockWorkspace(folder) {
  mkdirSync(folder, {recursive: true, mode: 0o700});
  const directory = openSync(folder, constants.O_RDONLY |
    (constants.O_DIRECTORY || 0) | (constants.O_NOFOLLOW || 0));
  try {
    const stat = fstatSync(directory);
    if (!stat.isDirectory() || (process.platform !== 'win32' && (stat.mode & 0o077) !== 0))
      throw new Error('Demo setup requires a private local directory.');
  } finally { closeSync(directory); }
  const path = resolve(folder, 'demo-seed.lock');
  let fd;
  try { fd = openSync(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL, 0o600); }
  catch (error) {
    if (error.code === 'EEXIST')
      throw new Error('A demo setup lock exists. See docs/DEMO_DATA.md before removing a stale lock.');
    throw error;
  }
  writeFileSync(fd, JSON.stringify({pid: process.pid}), 'utf8'); fsyncSync(fd);
  return () => { closeSync(fd); unlinkSync(path); };
}

export class DemoHttpError extends Error {
  constructor(status, path, code) {
    const safe = typeof code === 'string' && /^[A-Z][A-Z0-9_]{1,80}$/.test(code) ? code : 'REQUEST_FAILED';
    super('Demo setup received HTTP ' + status + ' (' + safe + ') at ' + path +
      '. Keep the private setup files and retry the same command after resolving the error.');
    this.status = status;
  }
}

export function demoPlan(now) {
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone: 'Africa/Lagos',
    year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(now);
  const part = name => parts.find(item => item.type === name).value;
  const today = part('year') + '-' + part('month') + '-' + part('day');
  const day = offset => {
    const date = new Date(today + 'T12:00:00Z');
    date.setUTCDate(date.getUTCDate() + offset); return date.toISOString().slice(0, 10);
  };
  let offset = -1;
  while ([0, 6].includes(new Date(day(offset) + 'T12:00:00Z').getUTCDay())) offset--;
  return {today, sessionStart: day(-45), sessionEnd: day(320),
    terms: [[1, day(-40), day(70)], [2, day(90), day(180)], [3, day(200), day(300)]],
    attendanceDate: day(offset), dueOn: day(14),
    opensAt: new Date(now.getTime() - 3600000).toISOString(),
    closesAt: new Date(now.getTime() + 2 * 86400000).toISOString(),
    assignmentDueAt: new Date(now.getTime() + 7 * 86400000).toISOString()};
}

export class DemoWorkspace {
  constructor({folder, origin, operatorOrigin, operatorKey, fetchImpl = fetch, now = new Date()}) {
    this.folder = folder;
    this.origin = localAdminOrigin(origin);
    this.operatorOrigin = localAdminOrigin(operatorOrigin);
    if (typeof operatorKey !== 'string' || operatorKey.length < 24)
      throw new Error('Use the existing backend .env containing the local operator key.');
    this.operatorKey = operatorKey; this.fetchImpl = fetchImpl;
    this.bootstrap = readPrivateJson(folder, 'bootstrap-state.json');
    this.access = readPrivateJson(folder, 'principal-access.json');
    const b = this.bootstrap?.body;
    if (b?.code !== 'DEMO' || b?.name !== 'EduCore Presentation School' ||
        !uuid(b.schoolId) || !uuid(this.bootstrap.createKey) ||
        b.schoolId !== this.access?.schoolId ||
        typeof this.access.loginId !== 'string' || this.access.loginId.length > 64)
      throw new Error('Demo setup only accepts the saved DEMO / EduCore Presentation School bootstrap.');
    this.schoolId = b.schoolId;
    this.state = readPrivateJson(folder, 'demo-seed-state.json', true);
    if (this.state && (this.state.schema !== 1 || this.state.schoolId !== this.schoolId ||
        this.state.origin !== this.origin || typeof this.state.commands !== 'object' ||
        !this.state.commands || typeof this.state.accounts !== 'object' || !this.state.accounts))
      throw new Error('Saved demo setup belongs to another school, origin or version. Preserve it for review.');
    this.state ||= {schema: 1, schoolId: this.schoolId, origin: this.origin,
      plan: demoPlan(now), commands: {}, accounts: {}, completed: false};
    this.save();
  }
  save() { writePrivateJson(this.folder, 'demo-seed-state.json', this.state); }
  school(path) { return '/api/v1/schools/' + this.schoolId + path; }
  async request(method, path, body, {token, version, key, operator = false} = {}) {
    const allowed = operator ? ['/internal/schools', '/internal/principal-activation'].includes(path) :
      (['/api/v1/auth/login', '/api/v1/auth/activation/review', '/api/v1/auth/password/setup',
        '/api/v1/me/context', '/api/v1/me/contexts', '/api/v1/me'].includes(path) ||
        path.startsWith(this.school('/')));
    if (!allowed || path.includes('..') || path.includes('#'))
      throw new Error('Demo setup refused an unexpected request path.');
    let response;
    try {
      response = await this.fetchImpl((operator ? this.operatorOrigin : this.origin) + path, {
        method, redirect: 'error', headers: {
          ...(body === undefined ? {} : {'Content-Type': 'application/json'}),
          ...(operator ? {'X-Service-Name': 'operator', 'X-Service-Key': this.operatorKey} : {}),
          ...(token ? {Authorization: 'Bearer ' + token} : {}),
          ...(version === undefined ? {} : {'If-Match': '"v' + version + '"'}),
          ...(key ? {'Idempotency-Key': key} : {})},
        body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000)});
    } catch {
      throw new Error('The local demo request did not finish at ' + path +
        '. Keep the private setup files and rerun the same command.');
    }
    let value;
    try { value = await response.json(); }
    catch { throw new Error('The local API returned an unreadable response at ' + path + '.'); }
    if (!response.ok) throw new DemoHttpError(response.status, path, value?.code);
    return value;
  }
  async command(label, actor, method, path, body, version, operator = false) {
    const owner = operator ? 'operator' : actor?.loginId;
    let op = this.state.commands[label];
    if (!op) {
      op = {key: randomUUID(), owner, method, path, body, version, operator, accepted: false};
      this.state.commands[label] = op; this.save();
    }
    if (op.owner !== owner || op.method !== method || op.path !== path || op.operator !== operator)
      throw new Error('A saved demo command changed scope. Preserve the setup state for review.');
    if (op.accepted) return op.result;
    // Replay the original body, version and key after an unknown result.
    const result = await this.request(op.method, op.path, op.body, {
      token: actor?.accessToken, version: op.version, key: op.key, operator});
    op.result = result; op.accepted = true; this.save(); return result;
  }
  async get(path, actor) { return this.request('GET', this.school(path), undefined, {token: actor.accessToken}); }
  async mutate(label, actor, method, path, body, version) {
    return this.command(label, actor, method, this.school(path), body, version);
  }
  async login(account) {
    if (!account.loginId) return undefined;
    try {
      return await this.request('POST', '/api/v1/auth/login', {
        identifier: account.loginId, password: account.password,
        installationId: account.installationId, deviceName: 'EduCore fictional demo setup'});
    } catch (error) { if (error instanceof DemoHttpError && error.status === 401) return undefined; throw error; }
  }
  async waitFor(action) {
    for (let i = 0; i < 90; i++) {
      try { return await action(); }
      catch (error) {
        if (!(error instanceof DemoHttpError) || ![404, 503].includes(error.status)) throw error;
        if (i === 89) throw error;
        await new Promise(resolve => setTimeout(resolve, 500));
      }
    }
  }
  async principalSlip(attempt) {
    const receipt = await this.request('POST', '/internal/schools', this.bootstrap.body,
      {operator: true, key: this.bootstrap.createKey});
    if (receipt.schoolId !== this.schoolId || !uuid(receipt.principalSubjectId))
      throw new Error('The local operator returned a different school receipt.');
    const label = 'principal-slip-' + attempt;
    if (!this.state.commands[label]) {
      this.state.commands[label] = {key: randomUUID(), bodyKey: randomUUID(), owner: 'operator',
        method: 'POST', path: '/internal/principal-activation', operator: true, accepted: false};
      this.state.commands[label].body = {schoolId: this.schoolId,
        principalSubjectId: receipt.principalSubjectId, idempotencyKey: this.state.commands[label].bodyKey};
      this.save();
    }
    return this.waitFor(() => this.command(label, undefined, 'POST',
      '/internal/principal-activation', undefined, undefined, true));
  }
  async account(label, {name, role, type, subjectId, childId}, admin) {
    let a = this.state.accounts[label];
    if (!a) {
      a = {name, role, type, subjectId, childId, installationId: randomUUID(),
        password: 'EduCore test ' + randomBytes(24).toString('base64url') + '!', attempt: 0,
        ...(label === 'lead' ? {loginId: this.access.loginId} : {})};
      this.state.accounts[label] = a; this.save();
    }
    if (a.role !== role || a.subjectId !== subjectId || a.childId !== childId)
      throw new Error('A saved test account changed scope. Preserve the setup state for review.');
    let tokens = await this.login(a);
    if (!tokens) {
      if (!a.flow) {
        a.attempt++; this.save();
        const slip = label === 'lead' ? await this.principalSlip(a.attempt) :
          await this.waitFor(() => this.mutate('slip-' + label + '-' + a.attempt, admin, 'POST',
            '/onboarding/' + type + '/' + subjectId + '/activation-slips', {}));
        if (typeof slip.loginId !== 'string' || !/^[A-Za-z0-9_-]{8,128}$/.test(slip.activationCode || ''))
          throw new Error('The local API returned an invalid activation slip.');
        a.loginId = slip.loginId; this.save();
        const flow = await this.request('POST', '/api/v1/auth/activation/review',
          {loginId: slip.loginId, activationCode: slip.activationCode});
        a.flow = flow.flowToken; a.flowExpiresAt = flow.expiresAt; this.save();
      }
      if (!a.flowExpiresAt || Date.parse(a.flowExpiresAt) <= Date.now()) {
        delete a.flow; delete a.flowExpiresAt; this.save();
        throw new Error('A demo activation flow expired. Rerun the same command to issue a fresh pending-account proof.');
      }
      let saved;
      try { saved = await this.request('POST', '/api/v1/auth/password/setup',
        {flowToken: a.flow, newPassword: a.password}); }
      catch (error) {
        if (error instanceof DemoHttpError && error.status === 401) {
          delete a.flow; delete a.flowExpiresAt; this.save();
        }
        throw error;
      }
      a.recoveryCode = saved.newRecoveryCode;
      delete a.flow; delete a.flowExpiresAt; this.save();
      tokens = await this.login(a);
      if (!tokens) throw new Error('A prepared demo account could not sign in. Preserve the private credentials.');
    }
    await this.request('PUT', '/api/v1/me/context',
      {schoolId: this.schoolId, role, ...(childId ? {childId} : {})}, {token: tokens.accessToken});
    a.active = true; delete a.flow; delete a.flowExpiresAt; this.save();
    const actor = {label, loginId: a.loginId, ...tokens};
    this.credentials(); return actor;
  }
  credentials() {
    const accounts = Object.fromEntries(Object.entries(this.state.accounts).filter(([, a]) => a.loginId && a.active)
      .map(([label, a]) => [label, {name: a.name, role: a.role, loginId: a.loginId,
        password: a.password, ...(a.childId ? {childId: a.childId} : {}),
        ...(a.recoveryCode ? {recoveryCode: a.recoveryCode} : {})}]));
    writePrivateJson(this.folder, 'demo-credentials.json', {schoolId: this.schoolId,
      schoolName: 'EduCore Presentation School', apiBaseUrl: this.origin + '/api/v1',
      fictionalOnly: true, accounts});
  }
}
