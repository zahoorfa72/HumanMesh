# Connection-gated direct chat

Apply `supabase/migrations/0006_connection_gated_direct_chat.sql` after migrations 0001–0005.

The table is keyed to a real `match_requests` row. Both read and send are permitted only while that request is `accepted`, only to its two participants, and only while neither participant has blocked the other. The sender ID is checked against the authenticated Supabase identity. Anonymous access is revoked.

## Verification before release

Use two test accounts (A and B), plus unrelated account C, in a non-production Supabase project:

1. A sends B a connection request. Before acceptance, neither account can read or send direct messages.
2. B accepts. A and B can read/send messages for that request.
3. C cannot read or send messages for A–B's request, even if C knows the request UUID.
4. Try inserting a message with another account's `sender_id`; it must fail.
5. Block either direction; reads and sends must fail for both participants.
6. Reject a request; reads and sends must fail.
7. Deleting a connection request in a test fixture removes its messages by foreign-key cascade.

These are manual integration checks; a successful Android build does not substitute for executing them against the deployed Supabase project.
