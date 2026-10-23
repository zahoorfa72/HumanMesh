import { createClient } from "npm:@supabase/supabase-js@2";
import nodemailer from "npm:nodemailer@6.9.16";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function reply(body: Record<string, unknown>, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function decodeJwtPayload(token: string): Record<string, unknown> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("Invalid session token.");
  const payload = parts[1].replace(/-/g, "+").replace(/_/g, "/");
  return JSON.parse(atob(payload + "=".repeat((4 - payload.length % 4) % 4)));
}

async function hashCode(pepper: string, userId: string, sessionId: string, code: string) {
  const bytes = new TextEncoder().encode(`${pepper}|${userId}|${sessionId}|${code}`);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest)).map((value) => value.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(a: string, b: string) {
  if (a.length !== b.length) return false;
  let result = 0;
  for (let i = 0; i < a.length; i++) result |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return result === 0;
}

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return reply({ error: "Method not allowed." }, 405);

  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return reply({ error: "Authentication required." }, 401);
  const accessToken = authorization.slice("Bearer ".length);

  let body: { action?: string; code?: string };
  try {
    body = await request.json();
  } catch {
    return reply({ error: "Invalid request body." }, 400);
  }
  if (body.action !== "send" && body.action !== "verify") return reply({ error: "Unsupported action." }, 400);

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    return reply({ error: "Email two-step verification is not configured on the server." }, 503);
  }

  let claims: Record<string, unknown>;
  try {
    claims = decodeJwtPayload(accessToken);
  } catch {
    return reply({ error: "Invalid session token." }, 401);
  }
  const sessionId = typeof claims.session_id === "string" ? claims.session_id : "";
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(sessionId)) {
    return reply({ error: "Session identifier is missing. Sign in again." }, 401);
  }

  const userClient = createClient(supabaseUrl, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userResult, error: userError } = await userClient.auth.getUser(accessToken);
  const user = userResult.user;
  if (userError || !user || claims.sub !== user.id) return reply({ error: "Invalid or expired session." }, 401);
  if (!user.email) return reply({ error: "This account has no email address." }, 400);

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const now = Date.now();

  if (body.action === "send") {
    if (!user.email_confirmed_at) {
      return reply({ error: "Verify your email address first, then sign in again." }, 403);
    }

    const { data: prior } = await admin
      .from("email_mfa_challenges")
      .select("created_at, expires_at, verified_at")
      .eq("session_id", sessionId)
      .maybeSingle();

    if (prior?.verified_at) return reply({ error: "This sign-in has already been verified." }, 409);
    if (prior?.created_at && now - Date.parse(prior.created_at) < 60_000) {
      return reply({ error: "Wait one minute before requesting another code." }, 429);
    }

    const smtpHost = Deno.env.get("SMTP_HOST") || "smtp.gmail.com";
    const smtpPort = Number(Deno.env.get("SMTP_PORT") || "465");
    const smtpUser = Deno.env.get("SMTP_USER");
    const smtpPassword = Deno.env.get("SMTP_PASSWORD");
    const smtpFrom = Deno.env.get("SMTP_FROM") || smtpUser;
    if (!smtpUser || !smtpPassword || !smtpFrom || !Number.isFinite(smtpPort)) {
      return reply({ error: "Email delivery is not configured. Set the HumanMesh Edge Function SMTP secrets." }, 503);
    }

    const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
    const pepper = Deno.env.get("EMAIL_MFA_PEPPER") || serviceRoleKey;
    const codeHash = await hashCode(pepper, user.id, sessionId, code);
    const expiresAt = new Date(now + 10 * 60_000).toISOString();
    const { error: storeError } = await admin.from("email_mfa_challenges").upsert({
      session_id: sessionId,
      user_id: user.id,
      code_hash: codeHash,
      created_at: new Date(now).toISOString(),
      expires_at: expiresAt,
      attempts: 0,
      verified_at: null,
    }, { onConflict: "session_id" });
    if (storeError) return reply({ error: "Could not create a verification code. Try again." }, 500);

    try {
      const transporter = nodemailer.createTransport({
        host: smtpHost,
        port: smtpPort,
        secure: smtpPort === 465,
        auth: { user: smtpUser, pass: smtpPassword },
      });
      await transporter.sendMail({
        from: `HumanMesh <${smtpFrom}>`,
        to: user.email,
        subject: "Your HumanMesh sign-in code",
        text: `Your HumanMesh verification code is ${code}. It expires in 10 minutes. Do not share this code with anyone.`,
      });
    } catch {
      await admin.from("email_mfa_challenges").delete().eq("session_id", sessionId);
      return reply({ error: "The verification email could not be sent. Check SMTP settings and try again." }, 503);
    }
    return reply({ sent: true, expires_in_seconds: 600 });
  }

  if (!/^[0-9]{6}$/.test(body.code || "")) return reply({ error: "Enter the six-digit code from your email." }, 400);
  const { data: challenge, error: challengeError } = await admin
    .from("email_mfa_challenges")
    .select("user_id, code_hash, expires_at, attempts, verified_at")
    .eq("session_id", sessionId)
    .maybeSingle();
  if (challengeError || !challenge || challenge.user_id !== user.id) {
    return reply({ error: "Code is invalid or expired. Request a new code." }, 400);
  }
  if (challenge.verified_at) return reply({ verified: true });
  if (Date.parse(challenge.expires_at) <= now) return reply({ error: "Code expired. Request a new code." }, 400);
  if (challenge.attempts >= 5) return reply({ error: "Too many incorrect attempts. Sign in again to request a new code." }, 429);

  const pepper = Deno.env.get("EMAIL_MFA_PEPPER") || serviceRoleKey;
  const submittedHash = await hashCode(pepper, user.id, sessionId, body.code || "");
  if (!constantTimeEqual(challenge.code_hash, submittedHash)) {
    await admin.from("email_mfa_challenges").update({ attempts: challenge.attempts + 1 }).eq("session_id", sessionId);
    return reply({ error: "Code is incorrect. Check the email and try again." }, 400);
  }

  const { error: verifyError } = await admin
    .from("email_mfa_challenges")
    .update({ verified_at: new Date(now).toISOString() })
    .eq("session_id", sessionId)
    .eq("user_id", user.id);
  if (verifyError) return reply({ error: "Could not verify this sign-in. Try again." }, 500);
  return reply({ verified: true });
});
