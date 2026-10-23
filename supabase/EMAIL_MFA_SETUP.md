# HumanMesh email two-step verification setup

The Android app's second sign-in step uses a six-digit code emailed to the confirmed account address. Codes expire after 10 minutes, are rate-limited to one send per minute, and allow at most five incorrect attempts per challenge. The verified marker is tied to the Supabase Auth session and RLS checks the token claim.

**This feature is not active in the hosted Supabase project until the following steps are completed.** The Android APK alone cannot configure Supabase database policies or Auth hooks.

## 1. Configure Edge Function SMTP secrets

In Supabase Dashboard, open **Project Settings → Edge Functions → Secrets** and add:

- `SMTP_HOST` = `smtp.gmail.com`
- `SMTP_PORT` = `465`
- `SMTP_USER` = the Gmail address used to send HumanMesh mail
- `SMTP_PASSWORD` = that account's Google App Password (never commit it to GitHub)
- `SMTP_FROM` = the same authorized sender email address

The SMTP configuration under Supabase Auth is separate from Edge Function secrets. The function uses `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` supplied by the Supabase Edge Function runtime; do not put the service-role key in the Android app.

## 2. Deploy the function

Deploy `supabase/functions/email-mfa/index.ts` as the `email-mfa` Edge Function. Ensure the project URL matches the `SUPABASE_URL` GitHub Actions secret used for the Android build.

## 3. Apply the database migration

Run `supabase/migrations/0004_email_mfa.sql` in the Supabase SQL Editor after migrations 0001, 0002, and 0003. The migration creates the private challenge store, the access-token hook, and wraps existing public-table RLS policies in an MFA gate. The enforcement toggle initially stays off so you can configure the hook before turning the gate on.

## 4. Enable the Custom Access Token Hook

In Supabase Dashboard, go to **Authentication → Hooks → Custom Access Token Hook** and select the database function:

`public.humanmesh_custom_access_token_hook`

Save and enable the hook. It adds `humanmesh_email_mfa_verified` to new access tokens only after the email code has been verified for that exact Auth session.

## 5. Enable enforcement after a successful test

Before enforcing the gate, install a test APK and confirm that sign-in sends an email code, a correct code signs in, and incorrect/expired codes are rejected. Then run:

```sql
update public.email_mfa_settings
set enforce_email_mfa = true, updated_at = now()
where singleton = true;
```

When enforcement is enabled, database RLS rejects app data access for sessions without the verified claim. Old sessions from before email MFA will need to sign in again and verify their code. Do not enable enforcement until the Edge Function is deployed and the Auth hook is enabled, or app data access will be blocked.

## Troubleshooting

- **Email could not be sent:** check the Edge Function SMTP secrets and logs. Never paste an App Password into an issue or repository.
- **Verification completes but sign-in fails:** the Custom Access Token Hook is not active or the migration has not been applied.
- **Existing users are asked to sign in again:** expected after changing to session-bound email verification; their old session lacks the claim.
