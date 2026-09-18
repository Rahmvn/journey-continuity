# Milestone 5 physical and end-to-end acceptance

Automated tests are necessary but do not establish real email delivery, browser behavior, Android sharing, hosted authorization, or physical-device acceptance.

## Preconditions

1. Apply the migrations to the intended development Supabase project through the approved deployment process. Do not expose a secret/service-role key to either client.
2. Configure the Auth Site URL and redirect allow-list for the exact trusted-viewer URL.
3. Configure a working email provider for hosted testing, or use local Mailpit for local-only testing.
4. Build the trusted viewer with its Supabase URL and publishable key.
5. Configure Android with the same development Supabase URL, publishable key, and viewer base URL.
6. Use two distinct identities: the Redmi traveller's existing anonymous account and a recoverable contact email account. Keep an unrelated third email/browser profile for isolation tests.

Development viewer URL:

```text
https://journey-continuity-trusted-viewer.vercel.app/
```

Before testing magic links, set the Journey Continuity Supabase Auth Site URL to that exact HTTPS root and add `https://journey-continuity-trusted-viewer.vercel.app/**` as an additional redirect. Retain `http://127.0.0.1:4173/**` only if local viewer testing remains useful.

## Invitation and relationship

1. On the Redmi 14C, create an invitation with mixed-case/space-padded email input.
2. Verify Pending is displayed and the share sheet/copy action contains a one-time viewer URL.
3. Open the URL in a signed-out browser. Confirm no Journey data is visible before authentication.
4. Authenticate with a different verified email. Confirm acceptance is denied and no Journey data appears.
5. Authenticate with the invited email through the received magic link. Confirm acceptance succeeds once.
6. Reopen the raw invitation URL. Confirm it cannot create another relationship.
7. Confirm Android refresh shows Accepted rather than a duplicate invitation/relationship pair.

## Healthy privacy

1. Start and synchronize a Journey while cloud monitoring is `EVIDENCE_FRESH`.
2. Open the viewer as the accepted contact.
3. Confirm destination, start, ETA, lifecycle, monitoring phase, and last cloud contact are visible.
4. Inspect the browser network response for `get_trusted_journey_snapshot`.
5. Confirm it contains no latitude, longitude, movement trail, or exact telemetry evidence. CSS/DOM hiding is not sufficient.

## Verification case and immutable evidence

1. Record at least six cloud telemetry observations, then prevent fresh heartbeats until the watchdog transitions to `VERIFYING`.
2. Confirm exactly one OPEN case exists for the Journey and its opening monitoring-event ID is retained.
3. In the viewer, confirm the wording is “Current whereabouts are unknown” and “Last verified device location,” never “Current location.”
4. Confirm observation time, accuracy, battery, charging, connectivity, the last five cloud-known points, and system-derived verification history are visible and separately labelled.
5. Restore an older offline telemetry backlog without a fresh heartbeat. Confirm the opening snapshot does not change and monitoring remains `VERIFYING`.
6. Trigger overlapping/repeated watchdog evaluations. Confirm no second OPEN case appears for that transition.

## Reports, resolution, expiry, and revocation

1. Submit each supported report type, including one with a user-entered contact time.
2. Confirm reports are labelled `trusted_contact_reported`, retain server `created_at`, and do not change telemetry, heartbeat history, case status, or monitoring phase.
3. Restore fresh heartbeat contact. Confirm one `CONTACT_RESTORED` event and case resolution `DEVICE_CONTACT_RESTORED` occur together.
4. Repeat silence to open a second case, then complete the Journey while offline and synchronize completion. Confirm resolution `JOURNEY_COMPLETED` and no fabricated `CONTACT_RESTORED` event.
5. Confirm resolved sensitive evidence is available inside the 24-hour development window and denied after simulated expiry without deleting database evidence.
6. Revoke the relationship from Android. Confirm the current access row is revoked promptly, all trusted viewer RPCs deny access, and audit/history rows remain.

## Isolation and audit evidence

1. As an unrelated authenticated browser user, call viewer RPCs with known Journey and case UUIDs. Confirm authorization denial.
2. As an unauthenticated browser user holding only the invitation token, confirm evidence reads are denied.
3. Refresh Journey and case views repeatedly. Confirm one `JOURNEY_VIEWED`, one `VERIFICATION_CASE_VIEWED`, and one `PRECISE_LOCATION_REVEALED` event per relevant contact/scope.
4. Confirm every submitted report has its own `REPORT_SUBMITTED` audit event and no audit metadata contains exact coordinates.

Record screenshots, relevant redacted database rows, browser network responses, Redmi share-sheet behavior, and timestamps. Do not mark Milestone 5 accepted until the physical and hosted checks above pass.
