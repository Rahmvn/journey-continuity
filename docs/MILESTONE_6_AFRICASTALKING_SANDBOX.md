# Milestone 6 Africa's Talking Sandbox Adapter

Milestone 6 remains **IN PROGRESS**. The sandbox adapter/callback, migrations through `20260924000200`, and corrected function code are hosted. The original attempt-5 receipt remains `AUTHENTICATION_FAILED`; a new genuine provider event after the correction authenticated that same production JC1, a later event proved duplicate-envelope handling, and existing earlier attempt 4 proved historical out-of-order handling. See `MILESTONE_6_ACCEPTANCE.md`. This is sandbox, not live-provider, acceptance.

## Documented callback contract

Africa's Talking documents incoming SMS callbacks as `POST` requests using `application/x-www-form-urlencoded`. The incoming message model contains:

- `date`: provider-reported message time;
- `from`: sender address;
- `id`: provider message identifier;
- `linkId`: optional on-demand reply association;
- `text`: SMS body;
- `networkCode`: optional carrier network identifier;
- `to`: receiving shortcode.

The adapter requires one non-empty `date`, `from`, `id`, `text`, and `to` value and permits at most one bounded `linkId` and `networkCode`. It accepts only the configured sandbox shortcode, currently `35549` through external configuration. The real provider `id` becomes `provider_event_id`; no fallback identity is invented.

Africa's Talking documents `text` as the received message body. The attempt-5 hosted receipt matched the exact persisted 102-character text by SHA-256, confirming unchanged text for this sandbox delivery without a routing prefix. The adapter requires `text` to begin with `JC1.` and performs no trimming, keyword stripping, case conversion, or JC1 rewriting.

The sender value is required only because it is part of the provider callback shape. It is discarded after boundary validation: it is not logged, persisted, supplied to the core, used for authentication, or used for Journey/key resolution.

## Public adapter boundary

The only provider-facing surface for this sandbox slice is:

```text
POST https://<project-ref>.supabase.co/functions/v1/africastalking-inbound?token=<callback-secret>
```

The function:

1. accepts only `POST`;
2. compares the configured callback token without direct string comparison;
3. requires the form content type and limits the complete body to 2,048 bytes;
4. rejects duplicate, missing, malformed, oversized, wrong-shortcode, and non-`JC1.` fields before core invocation;
5. normalizes provider `africastalking`, the exact provider `id`, exact `text`, parsed provider `date`, and a local server receive time;
6. invokes the existing service-role-only provider-neutral core;
7. returns HTTP 200 only after the core has safely returned, including an idempotent prior result or a durably classified malformed/authentication failure.

Africa's Talking retries incoming callbacks when it does not receive HTTP 200, so temporary internal failures return HTTP 503 and remain retryable. Response bodies contain only a generic result. The adapter contains no logging of callback fields or JC1 data.

## Sandbox security limitation

The reviewed Africa's Talking material does not document a cryptographic signature, signed timestamp, or callback-specific verification header for incoming SMS callbacks. Its API-key guidance applies to client requests to Africa's Talking and cannot authenticate an unsolicited callback to this function.

For sandbox acceptance only, the dashboard callback URL must include a high-entropy adapter-specific secret in the `token` query parameter. The same value is held in the Edge Function secret `AFRICASTALKING_SANDBOX_CALLBACK_SECRET`. This rejects untokened requests but is weaker than signed webhooks because a URL secret can appear in provider configuration and infrastructure access metadata. It must be tightly handled and rotated after suspected exposure. The public function must not be represented as production-grade provider authentication.

JC1 cryptographic authentication remains independent and authoritative for device/Journey evidence. Knowing the callback URL secret cannot manufacture an authenticated JC1 envelope, but it could submit traffic for bounded parsing and durable rejection evidence. Body bounds, strict form validation, provider-event idempotency, and JC1 parsing limits reduce that abuse surface.

## Required external configuration

These values belong in Supabase Edge Function secrets or platform-provided runtime configuration, never in source:

- `AFRICASTALKING_SANDBOX_CALLBACK_SECRET`: newly generated high-entropy callback token, at least 32 characters;
- `AFRICASTALKING_SANDBOX_SHORTCODE`: `35549`;
- `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`: Supabase-provided function runtime values;
- `FALLBACK_KEY_KEKS_JSON` and `FALLBACK_ACTIVE_KEK_VERSION`: the existing external fallback KEK ring configuration.

An Africa's Talking API key is not required to receive this inbound callback and must not be added to the adapter.

## Hosted sandbox state and limit

The initial inbound migration and the later installation-identifier correction
are hosted. Supabase CLI function deployment was blocked by account privileges;
the corrected single-file bundle was deployed through the Dashboard. JWT
verification remains disabled for this public callback boundary. The callback
secret, shortcode, and KEK ring are configured externally. The sandbox callback
is registered, and real simulator deliveries have exercised authentication,
duplicate-envelope handling, and historical ordering. Do not place a callback
URL containing its token in source, logs, tickets, screenshots, or command
history.

Observed sandbox webhook delivery delay varied substantially, including roughly
13–20 minutes for some deliveries. This precedes JOURNEY processing and is not
a production carrier/provider latency measurement. This sandbox result does not
establish signed webhook authentication or live-provider acceptance.

## Provider references reviewed

- Africa's Talking official C# SDK callback example: <https://github.com/AfricasTalkingLtd/africastalking.Net>
- Africa's Talking sandbox setup and simulator boundary: <https://help.africastalking.com/en/articles/1170660-how-do-i-get-started-on-the-africa-s-talking-sandbox>
- Africa's Talking incoming-message retry behavior: <https://help.africastalking.com/en/articles/742510-where-are-my-incoming-messages>
- Africa's Talking callback-request security guidance: <https://help.africastalking.com/en/articles/5947646-how-can-ensure-that-the-post-request-to-my-callback-url-is-coming-from-africa-s-talking-and-not-some-other-place>
