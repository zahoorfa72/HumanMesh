# HumanMesh Android

HumanMesh is an Android-first goal discovery and collaboration app. This repository is an **early, buildable implementation foundation**, not a claim of production readiness or perfect security.

## Current implementation
- Native Android app using Kotlin and Jetpack Compose.
- Email/password authentication against Supabase Auth when backend configuration is supplied.
- Goal discovery and creation with contribution and target-timeline fields, explainable skill-fit reasons, goal-linked team rooms with member invitations, room messages, connection requests, shared tasks/milestones, collaboration outcomes with teammate confirmations, profile editing, and report/block controls.
- Local encrypted storage for session tokens; Android backup disabled; cleartext traffic disabled.
- PostgreSQL schema with Row Level Security (RLS) and per-user policies.
- GitHub Actions builds a debug APK and runs Android lint/unit tests.

The app includes a clearly labelled local demo mode when no backend is configured. Demo content is sample content, not real people.

## Build an APK on GitHub
1. Open **Actions** and allow workflows if prompted.
2. Push a commit to `main` or manually run **HumanMesh Android CI** from Actions.
3. Open the successful workflow run and download the `HumanMesh-debug-apk` artifact.
4. Extract the ZIP and install the APK on your Android phone. Android may ask you to allow installation from your file manager/browser.

## Configure the real backend
1. Create a Supabase project.
2. In Supabase SQL Editor, run `supabase/migrations/0001_humanmesh_schema.sql` first, then run `supabase/migrations/0002_collaboration_progress.sql`, and finally `supabase/migrations/0003_room_invitations.sql`. Apply all three in order. The later migrations add contribution/timeline fields, tasks, milestones, teammate-confirmed outcomes, and room invitations.
3. In GitHub repository **Settings → Secrets and variables → Actions**, add:
   - `SUPABASE_URL`: the project's HTTPS project URL
   - `SUPABASE_ANON_KEY`: the project's publishable/anon key only. **Never put the service-role key in the Android app or GitHub build variables.**
4. For local Android builds, pass the same two values as Gradle project properties or environment variables.
5. Deploy `supabase/functions/delete-account` and set its server-only `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` secrets before enabling account deletion.
6. Configure Supabase Auth email verification, password policies, SMTP, and allowed redirect URLs before onboarding real users.

The anon/publishable key is expected to be visible in a mobile client; access must be controlled by Auth and RLS. Do not disable RLS. Test policies with separate normal user accounts before adding real data.

## Security status and production checklist
Security is layered, not absolute. Before inviting real users, perform:
- independent review of every RLS policy and Edge Function;
- tests proving User A cannot read, edit, or delete User B's private data;
- abuse/reporting workflow and moderation operations;
- rate limits, spam controls, retention rules, and monitoring;
- privacy policy, terms, data export/deletion verification, and jurisdiction-specific legal review;
- release signing, dependency review, Play Integrity strategy as appropriate, accessibility testing, and OWASP MASVS-aligned testing.

This project must not be marketed as "fully secure" until it has undergone appropriate testing and review. GitHub Actions provides a build/test signal, not a security certification.


## Email two-step verification

Setup instructions and required Supabase Dashboard configuration are in [supabase/EMAIL_MFA_SETUP.md](supabase/EMAIL_MFA_SETUP.md). Do not enable database enforcement until the Edge Function and Custom Access Token Hook are deployed and tested.
