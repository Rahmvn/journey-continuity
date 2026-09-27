// Real PostgreSQL transactions/process restart with a fake AWS boundary only.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createHandler } from "../../aws/watchdog/function/index.mjs";

export async function runDispatchCases({ client, connect, restart, migration, phase }) {
  let db = client;
  const one = async (sql, args = []) => (await db.query(sql, args)).rows[0];
  const scalar = async (sql, args = []) => Object.values(await one(sql, args))[0];
  const state = (id) => one("select state,attempt_count,dispatch_token,provider_message_id,failure_classification from trusted_contact_notification_outbox where id=$1", [id]);
  const holdAll = () => db.query("update trusted_contact_notification_outbox set next_attempt_at='infinity' where state in ('PENDING','RETRY_PENDING')");
  const claim = async (id) => {
    await holdAll();
    await db.query("update trusted_contact_notification_outbox set next_attempt_at=now()-interval '1 second' where id=$1", [id]);
    const result = await one("select * from claim_due_sms_notifications(1)");
    assert.equal(result?.notification_id, id);
    return result;
  };
  const authorize = (c) => scalar("select authorize_sms_dispatch($1,$2)", [c.notification_id, c.lease_token]);
  const success = (c, message = "fake-accepted") => scalar("select record_sms_send_success($1,$2,$3)", [c.notification_id, c.lease_token, message]);
  const failure = (c, permanent = false) => scalar("select record_sms_send_failure($1,$2,$3,$4)",
    [c.notification_id, c.lease_token, permanent ? "PERMANENT" : "TRANSIENT", permanent ? "VALIDATIONEXCEPTION" : "THROTTLINGEXCEPTION"]);
  const expire = (id) => db.query("update trusted_contact_notification_outbox set lease_expires_at=clock_timestamp()-interval '1 second' where id=$1", [id]);
  const sweep = async () => { await holdAll(); return db.query("select * from claim_due_sms_notifications(25)"); };
  const fixture = async () => {
    const owner = randomUUID(), contact = randomUUID(), relationship = randomUUID(), journey = randomUUID();
    await db.query("insert into auth.users(id,email) values($1,'owner@example.invalid'),($2,'contact@example.invalid')", [owner, contact]);
    await db.query(`insert into trusted_contact_relationships(id,traveller_user_id,contact_user_id,display_name,contact_email_normalized)
      values($1,$2,$3,'Synthetic contact','contact@example.invalid')`, [relationship, owner, contact]);
    // Synthetic fixture destination, never connected to an AWS sender.
    await db.query(`insert into trusted_contact_sms_preferences(relationship_id,contact_user_id,phone_e164,sms_enabled,consented_at)
      values($1,$2,'+12025550123',true,now())`, [relationship, contact]);
    await db.query("insert into journeys(id,owner_id,destination,expected_arrival_at,started_at,status) values($1,$2,'Synthetic dispatch test',now()+interval '1 hour',now(),'ACTIVE')", [journey, owner]);
    await db.query("select set_config('request.jwt.claim.sub',$1,false)", [owner]);
    await db.query("select * from record_journey_heartbeat($1,1,now(),80,false,'WIFI',0)", [journey]);
    await db.query(`update journey_monitoring_state set last_cloud_contact_at=now()-interval '6 minutes',
      last_authenticated_device_evidence_at=now()-interval '6 minutes',last_authenticated_device_evidence_received_at=now()-interval '6 minutes' where journey_id=$1`, [journey]);
    await db.query("select * from evaluate_due_journeys()");
    const { id, verification_case_id: caseId } = await one("select id,verification_case_id from trusted_contact_notification_outbox where journey_id=$1 and notification_kind='VERIFICATION_STARTED'", [journey]);
    await holdAll();
    return { id, caseId, owner, contact, relationship, journey };
  };
  const resolve = async (f, completion = false) => {
    await db.query("select set_config('request.jwt.claim.sub',$1,false)", [f.owner]);
    if (completion) await db.query("update journeys set status='COMPLETED',completed_at=now() where id=$1", [f.journey]);
    else await db.query("select * from record_journey_heartbeat($1,2,now(),80,false,'WIFI',0)", [f.journey]);
    const kind = completion ? "JOURNEY_COMPLETED" : "DEVICE_CONTACT_RESTORED";
    const rows = (await db.query("select id from trusted_contact_notification_outbox where verification_case_id=$1 and notification_kind=$2", [f.caseId, kind])).rows;
    assert.equal(rows.length, 1);
    await holdAll();
    return rows[0].id;
  };

  if (phase === "migration") {
    await db.query("begin");
    const sending = await fixture(); await claim(sending.id);
    const sendingBefore = await one("select lease_token,leased_at,lease_expires_at from trusted_contact_notification_outbox where id=$1", [sending.id]);
    const uncertain = await fixture(); const uc = await claim(uncertain.id);
    await failure(uc); // Throttling remains a confirmed retry.
    const legacyTimeout = await fixture(); const tc = await claim(legacyTimeout.id);
    await db.query("select record_sms_send_failure($1,$2,'TRANSIENT','TIMEOUTERROR')", [tc.notification_id, tc.lease_token]);
    const accepted = await fixture(); const ac = await claim(accepted.id); await success(ac);
    const acceptedBefore = await scalar("select to_jsonb(o) from trusted_contact_notification_outbox o where id=$1", [accepted.id]);
    // Run the new migration transactionally over representative old rows.
    await db.query(migration.replace(/^begin;\s*/i, "").replace(/commit;\s*$/i, ""));
    assert.equal((await state(sending.id)).state, "PROVIDER_OUTCOME_UNKNOWN");
    assert.equal((await state(legacyTimeout.id)).state, "PROVIDER_OUTCOME_UNKNOWN");
    assert.equal((await state(uncertain.id)).state, "RETRY_PENDING");
    assert.equal((await state(sending.id)).attempt_count, 1);
    const snapshot = await one("select dispatch_token,claimed_at,lease_expires_at,legacy_state from trusted_contact_notification_attempts where notification_id=$1", [sending.id]);
    assert.equal(snapshot.dispatch_token, sendingBefore.lease_token);
    assert.deepEqual(snapshot.claimed_at, sendingBefore.leased_at);
    assert.deepEqual(snapshot.lease_expires_at, sendingBefore.lease_expires_at);
    assert.equal(snapshot.legacy_state, "SENDING");
    const acceptedAfter = await scalar("select to_jsonb(o)-'dispatch_token'-'dispatch_started_at' from trusted_contact_notification_outbox o where id=$1", [accepted.id]);
    assert.deepEqual(acceptedAfter, acceptedBefore);
    await db.query("rollback");
    await db.query(migration);
    console.log(JSON.stringify({ suite: "legacy-dispatch-migration", passed: true }));
    return;
  }

  // Before/after boundary for both canonical resolution paths.
  for (const completion of [false, true]) for (const after of [false, true]) {
    const f = await fixture(); const c = await claim(f.id);
    if (after) assert.equal(await authorize(c), true);
    await resolve(f, completion);
    if (after) {
      assert.equal((await state(f.id)).state, "DISPATCHING");
      assert.equal(await success(c), true);
      assert.equal(await success(c), true); // Idempotent acknowledgement retry.
      assert.equal(await success(c, "fake-different"), false);
      assert.equal(await failure(c), null);
    } else {
      assert.equal(await authorize(c), false);
      assert.equal((await state(f.id)).failure_classification, "SUPERSEDED");
    }
    await db.query("select * from evaluate_due_journeys()");
    assert.equal(await scalar("select count(*)::integer from trusted_contact_notification_outbox where verification_case_id=$1", [f.caseId]), 2);
  }

  // Real concurrent sessions: the lease and final authorization each have one winner.
  const concurrent = await fixture();
  await db.query("update trusted_contact_notification_outbox set next_attempt_at=now() where id=$1", [concurrent.id]);
  const peer = await connect();
  const claimed = await Promise.all([db.query("select * from claim_due_sms_notifications(1)"), peer.query("select * from claim_due_sms_notifications(1)")]);
  const claims = claimed.flatMap((r) => r.rows);
  assert.equal(claims.length, 1);
  const concurrentClaim = claims[0];
  const authorization = await Promise.all([
    db.query("select authorize_sms_dispatch($1,$2) as ok", [concurrent.id, concurrentClaim.lease_token]),
    peer.query("select authorize_sms_dispatch($1,$2) as ok", [concurrent.id, concurrentClaim.lease_token]),
  ]);
  assert.equal(authorization.filter((r) => r.rows[0].ok).length, 1);
  assert.equal(await success(concurrentClaim), true);

  // Consent, relationship and grant are rechecked after the claim.
  for (const mutation of ["consent", "relationship", "grant", "destination"]) {
    const f = await fixture(); const c = await claim(f.id);
    if (mutation === "consent") await db.query("update trusted_contact_sms_preferences set sms_enabled=false,disabled_at=now() where relationship_id=$1", [f.relationship]);
    if (mutation === "relationship") await db.query("update trusted_contact_relationships set status='REVOKED',revoked_at=now() where id=$1", [f.relationship]);
    if (mutation === "grant") await db.query("update journey_trusted_contact_access set revoked_at=now() where journey_id=$1", [f.journey]);
    if (mutation === "destination") await db.query("update trusted_contact_sms_preferences set phone_e164='+12025550124' where relationship_id=$1", [f.relationship]);
    assert.equal(await authorize(c), false);
    assert.equal((await state(f.id)).attempt_count, 0);
  }

  // Resolved-case viewer TTL, both at claim and final authorization.
  for (const afterClaim of [false, true]) {
    const f = await fixture(); const resolution = await resolve(f);
    const c = afterClaim ? await claim(resolution) : null;
    await db.query("update verification_cases set sensitive_access_expires_at=now()-interval '1 second' where id=$1", [f.caseId]);
    if (afterClaim) assert.equal(await authorize(c), false);
    else { await db.query("update trusted_contact_notification_outbox set next_attempt_at=now() where id=$1", [resolution]); assert.equal((await sweep()).rowCount, 0); }
    assert.equal((await state(resolution)).failure_classification, "AUTHORIZATION_EXPIRED");
  }

  // Canonical consent/revocation after dispatch cannot destroy its evidence.
  for (const revoke of [false, true]) {
    const f = await fixture(); const c = await claim(f.id); assert.equal(await authorize(c), true);
    await db.query("select set_config('request.jwt.claim.sub',$1,false)", [revoke ? f.owner : f.contact]);
    if (revoke) await db.query("select revoke_trusted_contact($1)", [f.relationship]);
    else await db.query("select * from set_trusted_contact_sms_preference($1,null,false)", [f.relationship]);
    assert.equal((await state(f.id)).state, "DISPATCHING");
    assert.equal(await success(c), true);
  }

  // Parent locks serialize lifecycle mutation against the final boundary.
  for (const completion of [false, true]) {
    const f = await fixture(); const c = await claim(f.id);
    await db.query("begin");
    await resolve(f, completion); // Resolution is not yet committed.
    const waiting = peer.query("select authorize_sms_dispatch($1,$2) as ok", [f.id, c.lease_token]);
    await db.query("commit");
    assert.equal((await waiting).rows[0].ok, false);
    assert.equal((await state(f.id)).attempt_count, 0);
  }

  const pre = await fixture(); const preClaim = await claim(pre.id);
  await expire(pre.id); const reclaimed = await claim(pre.id);
  assert.notEqual(reclaimed.lease_token, preClaim.lease_token);
  assert.equal(await authorize(preClaim), false);
  assert.equal((await state(pre.id)).attempt_count, 0);
  assert.equal(await authorize(reclaimed), true);
  assert.equal(await failure(reclaimed, true), "FAILED");

  const retry = await fixture(); let rc = await claim(retry.id); await authorize(rc);
  assert.equal(await failure(rc), "RETRY_PENDING");
  const unknown = await fixture(); const uc = await claim(unknown.id); await authorize(uc);
  await db.query("select record_sms_outcome_unknown($1,$2)", [unknown.id, uc.lease_token]);
  const inFlight = await fixture(); const ic = await claim(inFlight.id); await authorize(ic);
  await expire(inFlight.id);
  // Stop PostgreSQL entirely, restart the same isolated cluster and reconnect.
  db = await restart();
  assert.equal((await state(retry.id)).attempt_count, 1);
  assert.equal((await state(unknown.id)).state, "PROVIDER_OUTCOME_UNKNOWN");
  assert.equal((await state(inFlight.id)).state, "DISPATCHING");
  for (let n = 0; n < 3; n += 1) assert.equal((await sweep()).rowCount, 0);
  assert.equal((await state(inFlight.id)).state, "PROVIDER_OUTCOME_UNKNOWN");
  assert.equal((await state(inFlight.id)).attempt_count, 1);
  assert.equal(await authorize(ic), false);
  assert.equal(await success(ic, "fake-late-accepted"), true);
  for (let n = 2; n <= 5; n += 1) {
    rc = await claim(retry.id); assert.equal(await authorize(rc), true);
    assert.equal(await failure(rc), n < 5 ? "RETRY_PENDING" : "FAILED");
    assert.equal((await state(retry.id)).attempt_count, n);
  }
  assert.equal((await state(retry.id)).failure_classification, "MAX_ATTEMPTS");
  assert.equal((await sweep()).rowCount, 0);
  assert.equal(await scalar("select count(*)::integer from trusted_contact_notification_attempts where notification_id=$1", [retry.id]), 5);

  // Execute the production JS handler against REAL RPCs, with only AWS replaced.
  const lostAck = await fixture();
  await db.query("update trusted_contact_notification_outbox set next_attempt_at=now() where id=$1", [lostAck.id]);
  let sends = 0;
  let acknowledgements = 0;
  const fetchImpl = async (url, options) => {
    const rpc = url.split("/").at(-1), args = JSON.parse(options.body);
    if (rpc === "record_sms_send_success") { acknowledgements += 1; return { ok: false, status: 503 }; }
    const allowed = new Set(["evaluate_due_journeys","claim_due_sms_notifications","authorize_sms_dispatch","record_sms_outcome_unknown"]);
    assert.ok(allowed.has(rpc));
    const values = Object.values(args), placeholders = values.map((_, i) => `$${i + 1}`).join(",");
    const result = await db.query(`select * from public.${rpc}(${placeholders})`, values);
    const payload = ["authorize_sms_dispatch","record_sms_outcome_unknown"].includes(rpc) ? Object.values(result.rows[0])[0] : result.rows;
    return { ok: true, json: async () => payload };
  };
  const handler = () => createHandler({ getConfiguration: async () => ({ url: "https://fake.invalid", secretKey: "fake", viewerUrl: "https://viewer.example.invalid", originationIdentity: "fake", batchSize: 1 }),
    fetchImpl, sendTextMessage: async () => { sends += 1; return { MessageId: "fake-provider-result" }; }, acknowledgementDelay: async () => {}, log: () => {} });
  await handler()();
  assert.equal(sends, 1); assert.equal(acknowledgements, 3);
  assert.equal((await state(lostAck.id)).state, "PROVIDER_OUTCOME_UNKNOWN");
  db = await restart();
  await handler()(); await handler()();
  assert.equal(sends, 1);
  assert.equal((await state(lostAck.id)).attempt_count, 1);
  assert.equal(await scalar("select count(*)::integer from trusted_contact_notification_attempts where notification_id=$1", [lostAck.id]), 1);
  console.log(JSON.stringify({ suite: "dispatch-races-concurrency-restart-fake-provider", passed: true, realSends: 0 }));
}
