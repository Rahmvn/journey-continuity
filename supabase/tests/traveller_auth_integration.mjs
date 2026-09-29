// Run with: node --test supabase/tests/traveller_auth_integration.mjs
// Uses only this checkout's local Supabase Auth, PostgREST, Mailpit, and DB.
// Test users are created by GoTrue and removed by ID in finally. No hosted
// credentials, seeded auth.users rows, database reset, or email delivery.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../..', import.meta.url))
const config = readFileSync(path.join(root, 'supabase/config.toml'), 'utf8')
const projectId = config.match(/^project_id = "([a-z0-9-]+)"$/m)?.[1]
assert.equal(projectId, 'journey-continuity-m5-local', 'refuse to use a different Supabase project')
assert.match(config, /^enable_anonymous_sign_ins = true$/m)
assert.match(config, /^enable_manual_linking = true$/m)
assert.match(config, /^enable_confirmations = true$/m)
assert.match(config, /^content_path = "\.\/supabase\/templates\/traveller_email_change_code.html"$/m)

const local = JSON.parse(execFileSync('supabase', ['status', '-o', 'json'], {
  cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
}))
assert.equal(local.API_URL, 'http://127.0.0.1:56421', 'refuse a nonlocal Auth endpoint')
assert.equal(new URL(local.DB_URL).port, '56422', 'refuse a different database')
assert.equal(local.INBUCKET_URL, 'http://127.0.0.1:56424', 'refuse a different mail catcher')
assert.ok(local.ANON_KEY && local.SERVICE_ROLE_KEY, 'local CLI keys are required')

const dbContainer = `supabase_db_${projectId}`
const authContainer = `supabase_auth_${projectId}`
const [autoConfirm, manualLinking] = execFileSync('docker', [
  'exec', authContainer, 'printenv', 'GOTRUE_MAILER_AUTOCONFIRM',
  'GOTRUE_SECURITY_MANUAL_LINKING_ENABLED',
], { encoding: 'utf8' }).trim().split(/\r?\n/)
assert.equal(autoConfirm, 'false', 'running Auth must require email verification')
assert.equal(manualLinking, 'true', 'running Auth must enable documented linking')

function sql(query) {
  // Every interpolated value below is a UUID or a locally generated email/handle.
  return execFileSync('docker', ['exec', '-i', dbContainer, 'psql', '-U', 'postgres',
    '-d', 'postgres', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], {
    input: query, encoding: 'utf8', maxBuffer: 1024 * 1024,
  }).trim()
}

function authDbFlags(id) {
  const [anonymous, emailConfirmed] = sql(`select is_anonymous::text || '|' ||
    (email_confirmed_at is not null)::text from auth.users where id = '${id}'::uuid;`).split('|')
  return { is_anonymous: anonymous === 'true', emailConfirmed: emailConfirmed === 'true' }
}

function freshEmail() {
  return `journey-auth-proof-${randomUUID().replaceAll('-', '')}@example.test`
}

function requireUserId(user, stage) {
  const id = user?.id
  assert.match(id ?? '', /^[0-9a-f]{8}-[0-9a-f-]{27,}$/i, `${stage} returned no Auth user ID`)
  return id
}

function tokenSubject(accessToken) {
  assert.ok(accessToken, 'Auth access token is required')
  return JSON.parse(Buffer.from(accessToken.split('.')[1], 'base64url').toString()).sub
}

async function request(url, { method = 'GET', body, token, key = local.ANON_KEY } = {}) {
  const response = await fetch(url, {
    method,
    headers: {
      apikey: key,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000),
  })
  const text = await response.text()
  let data
  try { data = JSON.parse(text) } catch { data = null }
  return { status: response.status, data }
}

const auth = (path, options) => request(`${local.API_URL}/auth/v1${path}`, options)
const rpc = (token, fullName, handle) => request(
  `${local.API_URL}/rest/v1/rpc/set_traveller_profile_identity`,
  { method: 'POST', token, body: { p_full_name: fullName, p_handle: handle } },
)

function expectStatus(result, status, stage) {
  assert.equal(result.status, status,
    `${stage}: HTTP ${result.status}, code ${result.data?.code ?? 'none'}`)
  return result.data
}

function expectBlocked(result, stage) {
  assert.equal(result.data?.code, '42501', `${stage}: profile write must be denied`)
  assert.notEqual(result.status, 200, `${stage}: profile write unexpectedly succeeded`)
}

async function adminUser(id) {
  const result = await auth(`/admin/users/${id}`, {
    token: local.SERVICE_ROLE_KEY, key: local.SERVICE_ROLE_KEY,
  })
  return expectStatus(result, 200, 'read test Auth user').user ?? result.data
}

async function anonymousUser(createdIds) {
  const session = expectStatus(await auth('/signup', { method: 'POST', body: {} }), 200,
    'anonymous sign-in')
  const id = requireUserId(session.user, 'anonymous sign-in')
  assert.equal(tokenSubject(session.access_token), id)
  createdIds.push(id)
  return { ...session, id }
}

async function emailMessage(email) {
  const until = Date.now() + 10000
  do {
    const listed = await fetch(`${local.INBUCKET_URL}/api/v1/messages`, {
      signal: AbortSignal.timeout(10000),
    }).then(response => response.json())
    const message = listed.messages.find(item =>
      item.To?.some(recipient => (recipient.Address ?? recipient.Email) === email))
    if (message) {
      const detail = await fetch(`${local.INBUCKET_URL}/api/v1/message/${message.ID}`, {
        signal: AbortSignal.timeout(10000),
      }).then(response => response.json())
      return detail
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  } while (Date.now() < until)
  throw new Error('local Mailpit did not receive the test email')
}

async function emailCode(email) {
  const html = (await emailMessage(email)).HTML ?? ''
  const code = html.match(/<strong>(\d{6})<\/strong>/)?.[1]
  assert.ok(code, 'email-change template must contain one six-digit code')
  assert.ok(!html.includes('/auth/v1/verify?'), 'email-change template must not send a link')
  return code
}

test('real local Auth preserves the anonymous owner through verified email conversion', async () => {
  const createdIds = []
  let ownedProfileId
  let ownedHandle
  try {
    const original = await anonymousUser(createdIds)
    const recordedOwnerId = original.id
    const originalState = await adminUser(recordedOwnerId)
    assert.equal(originalState.is_anonymous, true)
    assert.equal(originalState.email_confirmed_at ?? null, null)
    assert.deepEqual(authDbFlags(recordedOwnerId), { is_anonymous: true, emailConfirmed: false })
    expectBlocked(await rpc(original.access_token, 'Proof Owner', 'proofowner'),
      'before email request')

    const email = freshEmail()
    const handle = `proof${randomUUID().replaceAll('-', '').slice(0, 12)}`
    expectStatus(await auth('/user', {
      method: 'PUT', token: original.access_token, body: { email },
    }), 200, 'add email to current anonymous user')
    const pending = await adminUser(recordedOwnerId)
    assert.equal(pending.is_anonymous, true, 'request alone must not upgrade')
    assert.equal(pending.email_confirmed_at ?? null, null, 'request alone must not confirm email')
    assert.deepEqual(authDbFlags(recordedOwnerId), { is_anonymous: true, emailConfirmed: false })
    assert.equal(requireUserId(expectStatus(await auth('/user', {
      token: original.access_token,
    }), 200, 'pending session'), 'pending session'), recordedOwnerId)
    expectBlocked(await rpc(original.access_token, 'Proof Owner', handle),
      'while email verification is pending')

    const code = await emailCode(email)
    const wrong = code === '000000' ? '000001' : '000000'
    const wrongResult = await auth('/verify', {
      method: 'POST', body: { type: 'email_change', email, token: wrong },
    })
    assert.notEqual(wrongResult.status, 200, 'wrong code must fail')
    assert.equal((await adminUser(recordedOwnerId)).is_anonymous, true)
    expectBlocked(await rpc(original.access_token, 'Proof Owner', handle),
      'after wrong verification code')

    const verified = expectStatus(await auth('/verify', {
      method: 'POST', body: { type: 'email_change', email, token: code },
    }), 200, 'verify email-change code')
    assert.equal(requireUserId(verified.user, 'verified session'), recordedOwnerId)
    assert.equal(tokenSubject(verified.access_token), recordedOwnerId)
    const confirmed = await adminUser(recordedOwnerId)
    assert.equal(confirmed.is_anonymous, false)
    assert.ok(confirmed.email_confirmed_at, 'verification must populate email_confirmed_at')
    assert.deepEqual(authDbFlags(recordedOwnerId), { is_anonymous: false, emailConfirmed: true })
    assert.equal(requireUserId(expectStatus(await auth('/user', {
      token: verified.access_token,
    }), 200, 'verified session remains usable'), 'verified session'), recordedOwnerId)

    // The setter reads auth.users, so even an unexpired pre-verification JWT
    // should be eligible after server-side confirmation of the SAME user ID.
    const saved = expectStatus(await rpc(original.access_token, '  Proof Owner  ',
      `@${handle.toUpperCase()}`), 200, 'profile write after confirmation')
    ownedProfileId = recordedOwnerId
    ownedHandle = handle
    assert.deepEqual(saved, { full_name: 'Proof Owner', handle })
    assert.equal(sql(`select owner_id::text || '|' || handle from public.traveller_profiles where owner_id = '${recordedOwnerId}'::uuid;`),
      `${recordedOwnerId}|${handle}`, 'stored profile must retain owner A and normalized handle')

    const refreshed = expectStatus(await auth('/token?grant_type=refresh_token', {
      method: 'POST', body: { refresh_token: verified.refresh_token },
    }), 200, 'refresh verified session')
    assert.equal(tokenSubject(refreshed.access_token), recordedOwnerId)
    assert.equal(requireUserId(expectStatus(await auth('/user', {
      token: refreshed.access_token,
    }), 200, 'refreshed session'), 'refreshed session'), recordedOwnerId)

    const otherEmail = freshEmail()
    const password = `${randomUUID()}Aa9!`
    const other = expectStatus(await auth('/admin/users', {
      method: 'POST', token: local.SERVICE_ROLE_KEY, key: local.SERVICE_ROLE_KEY,
      body: { email: otherEmail, password, email_confirm: true },
    }), 200, 'create independent confirmed Auth fixture')
    const otherId = requireUserId(other.user ?? other, 'other Auth fixture')
    createdIds.push(otherId)
    const otherSession = expectStatus(await auth('/token?grant_type=password', {
      method: 'POST', body: { email: otherEmail, password },
    }), 200, 'authenticate other fixture')
    assert.equal(tokenSubject(otherSession.access_token), otherId)
    const unavailable = await rpc(otherSession.access_token, 'Another Owner', handle)
    assert.equal(unavailable.data?.code, '23505', 'second owner cannot claim handle')
    assert.equal(sql(`select count(*) from public.traveller_profiles where owner_id = '${otherId}'::uuid;`), '0')

    const duplicate = await anonymousUser(createdIds)
    const duplicateOwnerId = duplicate.id
    const duplicateResult = await auth('/user', {
      method: 'PUT', token: duplicate.access_token, body: { email },
    })
    assert.equal(duplicateResult.status, 422,
      'duplicate registered email must fail without account merge')
    assert.equal(requireUserId(expectStatus(await auth('/user', {
      token: duplicate.access_token,
    }), 200, 'duplicate owner session'), 'duplicate owner session'), duplicateOwnerId)
    assert.equal((await adminUser(duplicateOwnerId)).is_anonymous, true)
    expectBlocked(await rpc(duplicate.access_token, 'Duplicate Owner', 'duplicateowner'),
      'after duplicate-email failure')
    assert.equal((await adminUser(recordedOwnerId)).id, recordedOwnerId)

    const expired = await anonymousUser(createdIds)
    const expiredEmail = freshEmail()
    expectStatus(await auth('/user', {
      method: 'PUT', token: expired.access_token, body: { email: expiredEmail },
    }), 200, 'request code for expiry fixture')
    const expiredCode = await emailCode(expiredEmail)
    assert.equal(sql(`update auth.users set email_change_sent_at = now() - interval '2 hours'
      where id = '${expired.id}'::uuid and email_change = '${expiredEmail}' and is_anonymous is true
      returning id;`), expired.id, 'expire only the test-owned pending challenge')
    const expiredResult = await auth('/verify', {
      method: 'POST', body: { type: 'email_change', email: expiredEmail, token: expiredCode },
    })
    assert.notEqual(expiredResult.status, 200, 'expired code must fail')
    const expiredState = await adminUser(expired.id)
    assert.equal(expiredState.is_anonymous, true)
    assert.equal(expiredState.email_confirmed_at ?? null, null)
    assert.deepEqual(authDbFlags(expired.id), { is_anonymous: true, emailConfirmed: false })
    expectBlocked(await rpc(expired.access_token, 'Expired Owner', 'expiredowner'),
      'after expired verification code')

    console.log(JSON.stringify({
      localAuthImage: execFileSync('docker', ['inspect', '--format', '{{.Config.Image}}', authContainer],
        { encoding: 'utf8' }).trim(),
      recordedOwnerId, sameIdAfterVerification: true,
      before: { is_anonymous: originalState.is_anonymous, email_confirmed_at: originalState.email_confirmed_at ?? null },
      pending: { is_anonymous: pending.is_anonymous, email_confirmed_at: pending.email_confirmed_at ?? null },
      after: { is_anonymous: confirmed.is_anonymous, emailConfirmed: Boolean(confirmed.email_confirmed_at) },
      oldTokenSetterWorkedAfterVerification: true, refreshedSessionWorked: true,
      wrongCodeRejected: true, expiredCodeRejected: true, duplicateEmailRejected: true,
      duplicateHandleRejected: true, duplicateEmailHttpStatus: duplicateResult.status,
    }))
  } finally {
    if (ownedProfileId && ownedHandle) {
      sql(`delete from public.traveller_profiles where owner_id = '${ownedProfileId}'::uuid and handle = '${ownedHandle}';`)
    }
    for (const id of createdIds.reverse()) {
      const result = await auth(`/admin/users/${id}`, {
        method: 'DELETE', token: local.SERVICE_ROLE_KEY, key: local.SERVICE_ROLE_KEY,
      })
      assert.ok(result.status === 200 || result.status === 204,
        `cleanup of test-owned Auth user failed with HTTP ${result.status}`)
    }
  }
})

test('local Trusted Viewer sign-in email remains a magic link', async () => {
  const email = freshEmail()
  let userId
  try {
    expectStatus(await auth('/otp', {
      method: 'POST', body: { email, create_user: true },
    }), 200, 'request Viewer passwordless email')
    userId = sql(`select id::text from auth.users where email = '${email}';`)
    assert.match(userId, /^[0-9a-f-]{36}$/i, 'Viewer Auth fixture must exist')
    const html = (await emailMessage(email)).HTML ?? ''
    assert.match(html, /href=["'][^"']*\/auth\/v1\/verify\?[^"']*["']/,
      'Viewer email must still contain a Supabase verification link')
  } finally {
    if (userId) {
      const result = await auth(`/admin/users/${userId}`, {
        method: 'DELETE', token: local.SERVICE_ROLE_KEY, key: local.SERVICE_ROLE_KEY,
      })
      assert.ok(result.status === 200 || result.status === 204,
        `cleanup of Viewer Auth fixture failed with HTTP ${result.status}`)
    }
  }
})
