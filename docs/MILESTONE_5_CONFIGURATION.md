# Milestone 5 configuration

Milestone 5 uses Supabase Auth and PostgreSQL as the authority. Android and the trusted viewer use only the publishable key. Never place a service-role or secret key in either client.

## Local Supabase isolation

This repository's local stack is configured in `supabase/config.toml` as:

```text
project_id: journey-continuity-m5-local
API:        http://127.0.0.1:56421
Database:   postgresql://127.0.0.1:56422/postgres
Studio:     http://127.0.0.1:56423
Mailpit:    http://127.0.0.1:56424
```

It deliberately uses ports separate from civil-exam-app. Always run local commands from this repository and include `--local` for resets/tests. Do not use `--linked`, `db push`, or a hosted project ref as part of local validation.

## Android

Add these ignored values to `local.properties`:

```properties
SUPABASE_URL=https://your-development-project.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_your_key
TRUSTED_VIEWER_BASE_URL=https://your-trusted-viewer.example/
```

The viewer base URL is used only to construct the manually copied/shared invitation URL. The server generates and returns the raw one-time token; Android does not persist it.

## Trusted viewer

The Milestone 5 development viewer is deployed at:

```text
https://journey-continuity-trusted-viewer.vercel.app/
```

The Vercel project is `journey-continuity-trusted-viewer`. Its Production environment contains the following client-safe variables by name:

- `NEXT_PUBLIC_SUPABASE_URL`
- `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY`

Copy `trusted-viewer/.env.example` to `trusted-viewer/.env.local` and provide:

```text
NEXT_PUBLIC_SUPABASE_URL=...
NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY=...
```

Then run:

```text
cd trusted-viewer
npm install
npm run dev
```

Add the exact deployed viewer URL to the Supabase Auth Site URL or redirect allow-list. The viewer calls `signInWithOtp` with that URL as `emailRedirectTo`; the default Supabase email template sends a magic link. If the template is changed to expose `{{ .Token }}`, the UI would also need an OTP-entry step before that mode is usable.

For the hosted Journey Continuity project, configure **Authentication > URL Configuration** manually:

```text
Site URL
https://journey-continuity-trusted-viewer.vercel.app/

Additional Redirect URLs
https://journey-continuity-trusted-viewer.vercel.app/**
http://127.0.0.1:4173/**
```

The wildcard covers the one-time `?token=...` invitation URL passed as `emailRedirectTo`. Do not add broad unrelated Vercel or localhost patterns.

The current local config points Auth redirects to `http://127.0.0.1:4173`. Mailpit at `http://127.0.0.1:56424` captures local magic-link emails.

## Provisional policies

- Invitation expiry: seven days, centralized in `trusted_contact_invitation_ttl()`.
- Resolved sensitive-case access: 24 hours, centralized in `sensitive_case_access_ttl()`.
- Accepted active relationships are automatically provisioned for every new Journey and for the current ACTIVE Journey when an invitation is accepted.

These are development policies for Milestone 5, not final retention policy.
