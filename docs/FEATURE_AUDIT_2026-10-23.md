# HumanMesh feature audit — 2026-10-23

Scope: review of the current `main` source tree at commit `37b7c4cfc62b9366356d29159457f04a334578c4`, repository README, Android data/API models, and Supabase migrations. This is a source-code audit, not a live penetration test or confirmation that a Supabase project has deployed the migrations/functions.

## Status key
- **Implemented (source-confirmed):** matching app/API/schema code exists.
- **Partial / setup required:** code exists but deployment, configuration, or an operational workflow remains.
- **Missing (not found in reviewed source):** no implementation found in the inspected Android source/schema.
- **Needs deeper verification:** source indicates a feature but security or behavior requires tests beyond a quick source review.

## Findings

| Requirement | Status | Evidence / next step |
|---|---|---|
| Native Android app and CI | Implemented (source-confirmed) | Kotlin/Jetpack Compose; GitHub Actions builds debug APK and runs lint/unit tests. Latest main CI run 39988709691 succeeded. |
| Supabase email/password authentication and session storage | Implemented (source-confirmed) | `HumanMeshApi.kt`, `SessionStore.kt`; validate real project configuration and refresh/expiry cases on-device. |
| Profile editing and skill-based goal discovery | Implemented (source-confirmed) | `UserProfile`, `Goal`, `SkillMatcher`, and main screen panels. |
| Goal creation with contribution/timeline details | Implemented (source-confirmed) | Migration 0002 and goal model/API. |
| Connection requests and acceptance/rejection | Implemented (source-confirmed) | `match_requests` schema/API/UI; database guard restricts responses to recipient. |
| Private one-to-one chat after connection acceptance | Missing (not found in reviewed source) | Current message model and messaging table are room-scoped; no direct-message model or UI found in MainActivity scan. Add schema/RLS and UI only after confirming accepted-request authorization server-side. |
| Opportunity Rooms and text messages | Implemented (source-confirmed) | Room/member/message models and schema; test that non-members cannot read/post to private rooms. |
| Room invitations | Implemented (source-confirmed) | Migration 0003 and invitation model/API. |
| Tasks, milestones, and outcome confirmations | Implemented (source-confirmed) | Migration 0002 and related models/API. |
| Blocking and member reports | Implemented in source; needs deeper verification | Schema and UI/API exist. Test that blocks prevent discovery, requests, invitations, and messaging in both directions. Verify report review/moderation operations. |
| Theme selection (System/Light/Dark) | Implemented (source-confirmed) | Merged PR #10. Test persistence across restarts and screens. |
| Password recovery link handling | Implemented; backend setup required | Merged PR #10. Confirm Supabase email templates, SMTP, and redirect allowlist. |
| Unexpected logout on startup | Fix merged; edge cases need device testing | Merged PR #11 addresses optional MFA claim/config handling. Expired/revoked refresh tokens still legitimately require sign-in. |
| AI Help | Partial / setup required | Merged PR #12 adds UI and Edge Function. Requires migration 0005, function deployment, and server-side OpenAI/Supabase secrets. No claim of live AI until deployed and tested. |
| Private app issue reports | Partial / setup required | Merged PR #12 adds RLS-protected `support_tickets`. No admin review dashboard or support notifications found; define a restricted staff workflow before launch. |
| Voice messages in rooms and personal chats | Missing (not found in reviewed source) | No voice/audio message model or recording/playback flow found in MainActivity scan. |
| Photo/video sharing and automatic compression | Missing (not found in reviewed source) | No media upload, compression, or playback flow found in MainActivity scan. |
| Discover feed for casual posts, questions, and ideas | Missing (not found in reviewed source) | Current discovery is goal-oriented; no general community post/feed model or UI found in reviewed source. |
| Production security and Play Store readiness | Needs deeper verification | README correctly notes this is not production-ready. Perform RLS isolation tests, moderation/retention review, privacy policy/terms, accessibility, dependency, and release-signing checks. |

## Highest-priority sequence

1. **Backend safety verification:** run migrations 0001–0005 in order on a test Supabase project; verify all RLS policies and Edge Functions with two independent user accounts. Do not use real user data for initial testing.
2. **Connection-gated direct messaging:** implement one-to-one text chat only for accepted connections, with server-enforced authorization, block checks, and report controls. This is a separate feature from room chat.
3. **Support operations:** add a restricted staff review workflow for app-support tickets without exposing other users' reports to ordinary accounts.
4. **Media pipeline:** add bounded-size image/video uploads with client-side compression, storage access policies, quotas, and clear deletion/retention behavior.
5. **Voice messages:** add recording/playback and upload limits after the media storage/security layer is in place.
6. **Discover/community feed:** introduce public posts/questions/ideas with pagination, report/block filtering, spam limits, and moderation.
7. **Release validation:** device tests, privacy/legal documents, dependency/security review, release signing, and a staged rollout.

## Important limits

- Repository source cannot prove that migrations, secrets, email templates, or Edge Functions are deployed in the user's Supabase project.
- A passing Android CI run proves the tested build/lint/unit-test steps passed; it is not a security certification or full end-to-end test.
- Keep secrets out of Android code and Git. Never grant ordinary users access to other users' support tickets.
