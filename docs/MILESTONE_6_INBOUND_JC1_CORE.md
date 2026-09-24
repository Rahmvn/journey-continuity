# Milestone 6 Provider-Neutral Inbound JC1 Core

Milestone 6 remains **IN PROGRESS**. This document defines the boundary between an SMS-provider adapter and the provider-neutral JC1 core. The Africa's Talking sandbox adapter is deployed and has persisted a real callback, but production JC1 authentication exposed the installation-identifier unwrap defect. Its correction is local only; see `MILESTONE_6_KEY_UNWRAP_FIX.md`. This is not production provider acceptance.

## Adapter responsibility

A provider adapter must authenticate and validate its provider request before calling the core. Provider-specific signatures, certificates, replay windows, source-network controls, request parsing, acknowledgement behavior, and secret rotation remain outside the core.

After provider verification, the adapter supplies exactly:

```text
provider                 stable bounded adapter identifier
providerEventId          provider message or event identifier
rawSmsBody               exact SMS body, without normalization
providerArrivedAt        optional provider-reported arrival timestamp
serverReceivedAt         trusted server receive timestamp
```

The sender phone number is not an authentication factor and is not used for Journey or key resolution. If a future adapter retains masked source metadata for operations, it must remain outside cryptographic identity and must not contain a full number in application logs.

The core returns a receipt identifier, result classification, provider-event duplicate flag, envelope duplicate flag, reconciliation outcome, and whether current authenticated-device evidence advanced. Adapters must not infer delivery, safety, internet restoration, or current location from that result.

## Core processing contract

The core performs these steps in order:

1. Enforce transport-field and body-size bounds.
2. Return the existing immutable result for a repeated `(provider, providerEventId)`.
3. Compute the exact-body SHA-256 digest without logging the body.
4. Strictly parse canonical JC1 V1 framing before key lookup.
5. Resolve the private installation key and Journey binding by `key_id` plus opaque handle only.
6. Enforce key and binding lifecycle.
7. Decrypt the installation master key through the external KEK ring using the explicitly resolved provisioning `installation_identifier` for AAD, derive the Journey key, and authenticate/decrypt JC1. The separate `installation_row_id` is never a crypto input.
8. Transactionally record the immutable receipt, authenticated envelope, canonical reconciliation, and eligible transport-neutral evidence.
9. Clear transient plaintext key arrays where the runtime permits.

Malformed, unknown, revoked, unavailable-KEK, and authentication-failed results retain bounded classification and digest evidence without storing the complete raw body. The local correction distinguishes `KEY_UNWRAP_FAILED` from `ENVELOPE_AUTHENTICATION_FAILED`; historical `AUTHENTICATION_FAILED` receipts remain valid and immutable. Full JC1 text, KEKs, master keys, derived keys, exception strings, and plaintext coordinates must not enter logs or receipt diagnostics.

## Persistence and reconciliation

Transport identity is `UNIQUE(provider, provider_event_id)`. Authenticated envelope identity is `UNIQUE(key_id, journey_handle, envelope_sequence)`. Logical observation identity remains the existing telemetry primary key `(journey_id, sequence)`.

SMS-first and internet-first arrivals converge on that canonical observation. Matching data remains one observation. Conflicting same-sequence data is preserved in private discrepancy evidence and is not silently applied. Duplicate and delayed receipts remain immutable history. Original receipt timing is never rewritten by later internet telemetry.

`observation_event_time`, `provider_arrived_at`, and `received_at` are distinct. Watchdog eligibility requires successful authentication, a new envelope and telemetry sequence, an active relevant Journey, valid key/binding state, bounded age, and bounded provider delay. Eligible SMS evidence updates transport-neutral device evidence; it never updates `last_cloud_contact_at`.

## Security boundary

The resolution, receipt lookup, and record RPCs are executable only by `service_role`. Anonymous and authenticated application clients have no private-schema access and cannot invoke ingestion. The core itself exposes no HTTP route.

The server KEK ring remains external configuration. No provider credential, KEK, service-role credential, phone number, full payload, or decrypted location belongs in the repository.

## Future adapters

An AWS adapter would follow:

```text
verified SNS or Lambda transport
-> provider-specific authentication and replay checks
-> normalized inbound model
-> provider-neutral core
```

The Africa's Talking sandbox adapter follows:

```text
bounded form callback plus sandbox-only shared URL secret
-> provider-specific validation and provider-event identity
-> normalized inbound model
-> provider-neutral core
```

No AWS adapter exists. The sandbox adapter is deliberately not described as cryptographically verified because Africa's Talking does not document a signed incoming-SMS webhook in the reviewed material. Deployment/configuration and exact-text receipt are evidenced; corrected JC1 authentication, reconciliation, and real sandbox delayed/duplicate/failure acceptance remain outstanding. A production provider adapter requires a stronger, separately reviewed authentication boundary.
