# Milestone 3 — Supabase Configuration

Milestone 3 uses anonymous Supabase Auth as a bootstrap identity. Anonymous users run as the PostgreSQL `authenticated` role, allowing `auth.uid()`-based RLS without adding signup or login UI.

This identity is not yet a production account system. The Supabase Kotlin Auth client persists and reuses its session, but clearing app data or uninstalling the app can make an anonymous identity unrecoverable. Before public release, Journey Continuity must add permanent recoverable authentication or account linking.

## Android build configuration

Copy these entries into the existing ignored `local.properties` file, replacing the placeholders with the development project's client configuration:

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_your_key
```

The same names can be supplied as build environment variables. The build exposes them through generated `BuildConfig` fields; no project URL or key is hardcoded in Kotlin. A publishable key is expected to be present in a client APK. Authorization is enforced by the authenticated session and RLS.

Never add a service-role key to the Android build, `local.properties`, source files, logs, or test instructions.

If either property is absent, local Journey and telemetry persistence continue normally. Cloud sync records a configuration error instead of attempting unauthenticated or privileged access.

## Supabase project preparation

1. Enable anonymous sign-ins in the development project's Auth settings.
2. Apply [the repository migration](../supabase/migrations/20260915000100_milestone_3_cloud_sync.sql) through the normal reviewed Supabase migration workflow.
3. Confirm the two public tables have RLS enabled and only the migration-defined grants and policies.
4. Build/install the app with the URL and publishable key configured locally.

Do not create the tables or policies only through Dashboard edits; the checked-in SQL remains authoritative.
