# HumanMesh AI Help and support reports

## What this adds

- A Help & AI screen accessible from the question-mark action in the app top bar.
- Authenticated AI replies through a Supabase Edge Function; the OpenAI key is kept server-side.
- Private app-support tickets stored in public.support_tickets, protected by row-level security.
- Server-side limits of 8 AI prompts per minute and 60 per 24 hours per account.
- Demo mode clearly uses local guidance only; it does not send a real report or call the AI service.

## Deploy the database migration

Apply supabase/migrations/0005_ai_help_and_support.sql after migrations 0001–0004 using the Supabase SQL Editor or your normal migration workflow. The migration creates private support tickets, a server-only AI usage table, and the quota function.

## Configure the server secrets

In the Supabase project, configure these Edge Function secrets:

- OPENAI_API_KEY: a server-side OpenAI API key with billing configured.
- SUPABASE_SERVICE_ROLE_KEY: the service-role secret for the same Supabase project. Never add this to Android build config, the repository, client settings, or a public GitHub secret that is exposed to app code.
- OPENAI_MODEL: optional; defaults to gpt-4.1-mini.

Supabase provides SUPABASE_URL and SUPABASE_ANON_KEY in the Edge Function environment in standard projects; verify these exist for your deployment.

Using the Supabase CLI from a trusted computer or secured shell:

supabase functions deploy humanmesh-help
supabase secrets set OPENAI_API_KEY=YOUR_SERVER_SIDE_KEY SUPABASE_SERVICE_ROLE_KEY=YOUR_SERVICE_ROLE_KEY OPENAI_MODEL=gpt-4.1-mini

Do not paste actual secret values into source code, issue comments, or Android settings. Rotate any key that has been exposed.

## Important behavior and operational notes

- The Android app sends the signed-in access token to the Edge Function. The function verifies it with Supabase Auth before using OpenAI.
- The Edge Function never returns the user's auth token to OpenAI. It sends only the question text, uses a short response budget, and applies a per-account quota.
- The question-mark Help screen and app-report form require a signed-in account for online AI/report submission. In demo mode, prompts receive explicit local guidance and report submission is disabled.
- Support tickets can be read by their own reporter. Support staff access should be implemented through a trusted, role-protected admin process; do not grant all users access to tickets.
- The AI assistant does not inspect application logs, change account state, or claim a report was reviewed. Users should not put passwords, verification codes, financial credentials, or private messages into questions or reports.
- For security or moderation reports about a specific person, use the Safety tab's existing member-report flow.
