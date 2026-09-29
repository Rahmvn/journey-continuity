// Run with: node --test supabase/tests/traveller_auth_integration.mjs
// Uses only this checkout's local Supabase Auth, PostgREST, Mailpit, and DB.
// Test users are created by GoTrue and removed by ID in finally. No hosted
// credentials, seeded auth.users rows, database reset, or email delivery.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { after, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../..', import.meta.url))
const config = readFileSync(path.join(root, 'supabase/config.toml'), 'utf8')
const ANDROID_CODE_REDIRECT = 'http://127.0.0.1:4173/auth/traveller-code'
const UNKNOWN_REDIRECT = 'http://127.0.0.1:4173/auth/traveller-code-extra'
const VIEWER_REDIRECT = 'http://127.0.0.1:4173'
const ownedEmails = new Set()
const ownedAuthIds = new Set()
const projectId = config.match(/^project_id = "([a-z0-9-]+)"$/m)?.[1]
assert.equal(projectId, 'journey-continuity-m5-local', 'refuse to use a different Supabase project')
assert.match(config, /^enable_anonymous_sign_ins = true$/m)
assert.match(config, /^enable_manual_linking = true$/m)
assert.match(config, /^enable_confirmations = true$/m)
assert.match(config, /^content_path = "\.\/supabase\/templates\/traveller_email_change_code.html"$/m)
assert.match(config, /^content_path = "\.\/supabase\/templates\/passwordless_sign_in.html"$/m)
assert.ok(config.includes(`"${ANDROID_CODE_REDIRECT}"`), 'Android selector must be allow-listed locally')
assert.ok(config.includes(`"${UNKNOWN_REDIRECT}"`), 'unknown redirect fixture must be allow-listed locally')

const local = JSON.parse(execFileSync('supabase', ['status', '-o', 'json'], {
  cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
}))
assert.equal(local.API_URL, 'http://127.0.0.1:56421', 'refuse a nonlocal Auth endpoint')
assert.equal(new URL(local.DB_URL).port, '56422', 'refuse a different database')
assert.equal(local.INBUCKET_URL, 'http://127.0.0.1:56424', 'refuse a different mail catcher')
assert.ok(local.ANON_KEY && local.SERVICE_ROLE_KEY, 'local CLI keys are required')

const dbContainer = `supabase_db_${projectId}`
const authContainer = `supabase_auth_${projectId}`
const [autoConfirm, manualLinking, magicLinkTemplate, allowedRedirects] = execFileSync('docker', [
  'exec', authContainer, 'printenv', 'GOTRUE_MAILER_AUTOCONFIRM',
  'GOTRUE_SECURITY_MANUAL_LINKING_ENABLED',
  'GOTRUE_MAILER_TEMPLATES_MAGIC_LINK', 'GOTRUE_URI_ALLOW_LIST',
], { encoding: 'utf8' }).trim().split(/\r?\n/)
assert.equal(autoConfirm, 'false', 'running Auth must require email verification')
assert.equal(manualLinking, 'true', 'running Auth must enable documented linking')
assert.match(magicLinkTemplate ?? '', /\/email\/magic_link\.html$/,
  'running Auth must load the conditional passwordless template')
assert.ok(allowedRedirects?.split(',').includes(ANDROID_CODE_REDIRECT),
  'running Auth must allow the exact Android code redirect')
assert.ok(allowedRedirects?.split(',').includes(UNKNOWN_REDIRECT),
  'running Auth must allow the unexpected redirect fixture')

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
  const email = `journey-auth-proof-${randomUUID().replaceAll('-', '')}@example.test`
  ownedEmails.add(email)
  return email
}

function trackAuthUser(id) {
  ownedAuthIds.add(id)
  return id
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
  createdIds.push(trackAuthUser(id))
  return { ...session, id }
}

async function allMailMessages() {
  const messages = []
  for (let start = 0; ; start += 100) {
    const response = await fetch(`${local.INBUCKET_URL}/api/v1/messages?start=${start}&limit=100`, {
      signal: AbortSignal.timeout(10000),
    })
    assert.equal(response.status, 200, 'local Mailpit list must be available')
    const page = await response.json()
    messages.push(...page.messages)
    if (messages.length >= page.total) return messages
  }
}

async function emailMessage(email) {
  const until = Date.now() + 10000
  do {
    const message = (await allMailMessages()).find(item =>
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

async function cleanupOwnedMail() {
  const messages = await allMailMessages()
  const ids = messages.filter(item => item.To?.some(recipient =>
    ownedEmails.has(recipient.Address ?? recipient.Email))).map(item => item.ID)
  if (ids.length) {
    const response = await fetch(`${local.INBUCKET_URL}/api/v1/messages`, {
      method: 'DELETE', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ IDs: ids }), signal: AbortSignal.timeout(10000),
    })
    assert.equal(response.status, 200, 'delete only harness-owned Mailpit messages')
  }
  assert.equal((await allMailMessages()).filter(item => item.To?.some(recipient =>
    ownedEmails.has(recipient.Address ?? recipient.Email))).length, 0,
  'harness-owned Mailpit messages must be gone')
  return ids.length
}

after(async () => {
  const deletedMailCount = await cleanupOwnedMail()
  if (ownedAuthIds.size) {
    const ids = [...ownedAuthIds].map(id => `'${id}'`).join(',')
    for (const table of ['auth.users', 'auth.sessions', 'auth.refresh_tokens',
      'auth.one_time_tokens', 'public.traveller_profiles']) {
      const column = table === 'public.traveller_profiles' ? 'owner_id' :
        table === 'auth.users' ? 'id' : 'user_id'
      assert.equal(sql(`select count(*) from ${table} where ${column} in (${ids});`), '0',
        `${table} must contain no harness-owned artifacts`)
    }
  }
  if (ownedEmails.size) {
    const emails = [...ownedEmails].map(email => `'${email}'`).join(',')
    assert.equal(sql(`select count(*) from auth.users where email in (${emails});`), '0',
      'no harness-owned email account may remain')
  }
  console.log(JSON.stringify({ cleanupVerified: true, mailMessagesDeleted: deletedMailCount,
    authUsersChecked: ownedAuthIds.size }))
})

async function emailCode(email) {
  const html = (await emailMessage(email)).HTML ?? ''
  const code = html.match(/<strong>(\d{6})<\/strong>/)?.[1]
  assert.ok(code, 'email-change template must contain one six-digit code')
  assert.ok(!html.includes('/auth/v1/verify?'), 'email-change template must not send a link')
  return code
}

async function verifiedExistingUser(createdIds) {
  const email = freshEmail()
  const password = `${randomUUID()}Aa9!`
  const created = expectStatus(await auth('/admin/users', {
    method: 'POST', token: local.SERVICE_ROLE_KEY, key: local.SERVICE_ROLE_KEY,
    body: { email, password, email_confirm: true },
  }), 200, 'create verified existing login fixture')
  const id = requireUserId(created.user ?? created, 'verified existing login fixture')
  createdIds.push(trackAuthUser(id))
  assert.equal((await adminUser(id)).email_confirmed_at != null, true)
  return { email, id }
}

async function requestPasswordless(email, redirect, createUser = false) {
  const path = redirect === undefined ? '/otp' :
    `/otp?redirect_to=${encodeURIComponent(redirect)}`
  return auth(path, { method: 'POST', body: { email, create_user: createUser } })
}

async function passwordlessMail(email) {
  return (await emailMessage(email)).HTML ?? ''
}

function codeFromPasswordlessMail(html) {
  const code = html.match(/<strong>(\d{6})<\/strong>/)?.[1]
  assert.ok(code, 'Android passwordless mail must show one six-digit code')
  assert.doesNotMatch(html, /<a\b|\/auth\/v1\/verify\?/i,
    'Android passwordless mail must not expose a clickable Auth link')
  return code
}

function linkFromPasswordlessMail(html) {
  assert.doesNotMatch(html, /<strong>\d{6}<\/strong>/,
    'Viewer/default mail must not switch to the Android code-only branch')
  const href = html.match(/<a href="([^"]+)"[^>]*>Sign in<\/a>/)?.[1]
  assert.ok(href, 'Viewer/default mail must show its normal sign-in link')
  const link = new URL(href.replaceAll('&amp;', '&'))
  assert.equal(link.origin, local.API_URL)
  assert.equal(link.pathname, '/auth/v1/verify')
  assert.equal(link.searchParams.get('type'), 'magiclink')
  return link
}

async function verifyEmailCode(email, code) {
  return auth('/verify', { method: 'POST', body: { type: 'email', email, token: code } })
}

function assertNoSession(result, stage) {
  assert.notEqual(result.status, 200, `${stage} must not authenticate`)
  assert.ok(!result.data?.access_token && !result.data?.refresh_token,
    `${stage} must not issue tokens`)
}

async function deleteCreatedUsers(ids) {
  for (const id of ids.reverse()) {
    const result = await auth(`/admin/users/${id}`, {
      method: 'DELETE', token: local.SERVICE_ROLE_KEY, key: local.SERVICE_ROLE_KEY,
    })
    assert.ok(result.status === 200 || result.status === 204,
      `cleanup of test-owned Auth user failed with HTTP ${result.status}`)
  }
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
    createdIds.push(trackAuthUser(otherId))
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
    await deleteCreatedUsers(createdIds)
  }
})

test('local Trusted Viewer sign-in email remains a magic link', async () => {
  const email = freshEmail()
  let userId
  try {
    expectStatus(await auth('/otp', {
      method: 'POST', body: { email, create_user: true },
    }), 200, 'request Viewer passwordless email')
    userId = trackAuthUser(sql(`select id::text from auth.users where email = '${email}';`))
    assert.match(userId, /^[0-9a-f-]{36}$/i, 'Viewer Auth fixture must exist')
    const html = (await emailMessage(email)).HTML ?? ''
    assert.match(html, /href=["'][^"']*\/auth\/v1\/verify\?[^"']*["']/,
      'Viewer email must still contain a Supabase verification link')
  } finally {
    if (userId) await deleteCreatedUsers([userId])
  }
})

test('one local Auth project sends Android codes and retains Viewer magic links', async () => {
  const createdIds = []
  try {
    const android = await verifiedExistingUser(createdIds)
    expectStatus(await requestPasswordless(android.email, ANDROID_CODE_REDIRECT), 200,
      'request existing-account Android code')
    const androidCode = codeFromPasswordlessMail(await passwordlessMail(android.email))
    assert.equal(sql(`select count(*) from auth.users where email = '${android.email}';`), '1',
      'Android request must not create an account')
    assert.equal(sql(`select count(*) from auth.users where id = '${android.id}'::uuid
      and recovery_token is not null and recovery_token <> '' and recovery_sent_at is not null;`), '1',
    'Android code must correspond to a stored GoTrue passwordless challenge')
    const wrongCode = androidCode === '000000' ? '000001' : '000000'
    assertNoSession(await verifyEmailCode(android.email, wrongCode), 'wrong Android code')
    const androidSession = expectStatus(await verifyEmailCode(android.email, androidCode), 200,
      'verify Android EMAIL code')
    assert.equal(requireUserId(androidSession.user, 'Android login'), android.id)
    assert.equal(tokenSubject(androidSession.access_token), android.id)
    assert.ok(androidSession.refresh_token, 'Android login must issue a refreshable session')
    assertNoSession(await verifyEmailCode(android.email, androidCode), 'replayed Android code')
    assert.equal(sql(`select count(*) from auth.users where email = '${android.email}';`), '1',
      'Android verification must preserve the existing Auth user')

    const viewer = await verifiedExistingUser(createdIds)
    expectStatus(await requestPasswordless(viewer.email, VIEWER_REDIRECT), 200,
      'request existing-account Viewer link')
    const viewerLink = linkFromPasswordlessMail(await passwordlessMail(viewer.email))
    const linkResponse = await fetch(viewerLink, {
      redirect: 'manual', headers: { apikey: local.ANON_KEY },
      signal: AbortSignal.timeout(10000),
    })
    assert.ok([302, 303].includes(linkResponse.status),
      'Viewer link verification must redirect with an authenticated session')
    const location = new URL(linkResponse.headers.get('location'))
    assert.equal(location.origin, VIEWER_REDIRECT)
    const viewerAccessToken = new URLSearchParams(location.hash.slice(1)).get('access_token')
    assert.equal(tokenSubject(viewerAccessToken), viewer.id,
      'Viewer link must authenticate its pre-existing user')

    const noRedirect = await verifiedExistingUser(createdIds)
    expectStatus(await requestPasswordless(noRedirect.email, undefined), 200,
      'request passwordless email without a redirect')
    linkFromPasswordlessMail(await passwordlessMail(noRedirect.email))

    const unknown = await verifiedExistingUser(createdIds)
    expectStatus(await requestPasswordless(unknown.email, UNKNOWN_REDIRECT), 200,
      'request passwordless email with an unexpected allowed redirect')
    linkFromPasswordlessMail(await passwordlessMail(unknown.email))

    const expired = await verifiedExistingUser(createdIds)
    expectStatus(await requestPasswordless(expired.email, ANDROID_CODE_REDIRECT), 200,
      'request Android code to expire')
    const expiredCode = codeFromPasswordlessMail(await passwordlessMail(expired.email))
    assert.equal(sql(`update auth.users set recovery_sent_at = now() - interval '2 hours'
      where id = '${expired.id}'::uuid and recovery_token is not null
        and recovery_token <> '' and recovery_sent_at is not null
      returning id;`), expired.id, 'expire only the harness-owned passwordless challenge')
    assertNoSession(await verifyEmailCode(expired.email, expiredCode), 'expired Android code')

    const absentEmail = freshEmail()
    const absent = await requestPasswordless(absentEmail, ANDROID_CODE_REDIRECT)
    assert.equal(absent.status, 422, 'create_user=false must reject an absent email')
    assert.equal(absent.data?.error_code, 'otp_disabled',
      'record the running GoTrue absent-email response')
    assert.ok(!absent.data?.access_token && !absent.data?.refresh_token)
    assert.equal(sql(`select count(*) from auth.users where email = '${absentEmail}';`), '0',
      'absent-email login must not create an Auth user')
    assert.equal((await allMailMessages()).some(item => item.To?.some(recipient =>
      (recipient.Address ?? recipient.Email) === absentEmail)), false,
    'absent-email login must not send a passwordless email')

    console.log(JSON.stringify({ androidCodeMail: true, androidEmailOtpVerified: true,
      wrongCodeRejected: true, expiredCodeRejected: true, replayRejected: true,
      viewerLinkMail: true, viewerLinkVerified: true, defaultLinkMail: true,
      unknownRedirectLinkMail: true, absentEmailStatus: absent.status,
      absentEmailCode: absent.data?.error_code,
      absentEmailCreatedUser: false }))
  } finally {
    await deleteCreatedUsers(createdIds)
  }
})
