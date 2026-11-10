import {constants, mkdirSync, openSync, closeSync, fstatSync, readFileSync, writeFileSync, fsyncSync, linkSync, unlinkSync, renameSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {resolve} from 'node:path';

const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const text = (value, maximum) => typeof value === 'string' && value.length > 0 && value.length <= maximum && !/[\u0000-\u001f]/.test(value);

function privateFolder(folder) {
  mkdirSync(folder, {recursive: true, mode: 0o700});
  const fd = openSync(folder, constants.O_RDONLY | (constants.O_DIRECTORY || 0) | (constants.O_NOFOLLOW || 0));
  try {
    const stat = fstatSync(fd);
    if (!stat.isDirectory() || (process.platform !== 'win32' && (stat.mode & 0o077) !== 0))
      throw new Error('Bootstrap requires a private local directory.');
  } finally { closeSync(fd); }
}

function temporaryJson(folder, value) {
  const path = resolve(folder, '.bootstrap-' + randomUUID() + '.tmp');
  const fd = openSync(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | (constants.O_NOFOLLOW || 0), 0o600);
  try {
    writeFileSync(fd, JSON.stringify(value, null, 2), 'utf8');
    fsyncSync(fd);
  } finally { closeSync(fd); }
  return path;
}

function readState(path) {
  const fd = openSync(path, constants.O_RDONLY | (constants.O_NOFOLLOW || 0) | (constants.O_NONBLOCK || 0));
  try {
    const stat = fstatSync(fd);
    if (!stat.isFile() || stat.size > 32768 || (process.platform !== 'win32' && (stat.mode & 0o077) !== 0))
      throw new Error('Bootstrap state must be a small private regular file.');
    return JSON.parse(readFileSync(fd, 'utf8'));
  } finally { closeSync(fd); }
}

function validateState(value, code) {
  const body = value?.body;
  if (!uuid(body?.schoolId) || !uuid(value?.createKey) || !uuid(value?.slipKey)
      || !text(body?.code, 40) || !text(body?.name, 200)
      || !text(body?.principal?.fullName, 200) || !text(body?.principal?.phone, 32))
    throw new Error('Saved bootstrap state is invalid; preserve it for local review.');
  if (body.code !== code)
    throw new Error('A different school bootstrap is already saved. Use a separate development folder.');
  return {body: {schoolId: body.schoolId, code: body.code, name: body.name,
    principal: {fullName: body.principal.fullName, phone: body.principal.phone}},
    createKey: value.createKey, slipKey: value.slipKey};
}

export function loadOrCreateState(folder, input) {
  privateFolder(folder);
  const candidate = validateState({body: {schoolId: randomUUID(), ...input},
    createKey: randomUUID(), slipKey: randomUUID()}, input.code);
  const path = resolve(folder, 'bootstrap-state.json');
  // Publish a completed file exclusively. A concurrent retry never observes partial JSON.
  const temporary = temporaryJson(folder, candidate);
  try {
    try { linkSync(temporary, path); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
  } finally { unlinkSync(temporary); }
  return validateState(readState(path), input.code);
}

export function localAdminOrigin(configured) {
  const url = new URL(configured);
  if (!['http:', 'https:'].includes(url.protocol)
      || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
    throw new Error('School bootstrap must use a fixed loopback HTTP(S) origin.');
  return url.origin;
}

export function schoolReceipt(value) {
  if (!uuid(value?.schoolId) || !uuid(value?.principalSubjectId))
    throw new Error('School setup returned an invalid receipt.');
  return {schoolId: value.schoolId, principalSubjectId: value.principalSubjectId};
}

export function savePrincipalAccess(folder, schoolId, value) {
  if (!uuid(schoolId) || !text(value?.loginId, 64)
      || !/^[A-Za-z0-9_-]{8,256}$/.test(value?.activationCode || '')
      || !text(value?.expiresAt, 64) || !Number.isFinite(Date.parse(value.expiresAt))
      || value?.delivery !== 'PRIVATE_SCHOOL_HANDOVER')
    throw new Error('School setup returned invalid private access instructions.');
  const access = {schoolId, loginId: value.loginId, activationCode: value.activationCode,
    expiresAt: value.expiresAt, delivery: value.delivery};
  privateFolder(folder);
  const temporary = temporaryJson(folder, access);
  try { renameSync(temporary, resolve(folder, 'principal-access.json')); }
  catch (error) { unlinkSync(temporary); throw error; }
}
