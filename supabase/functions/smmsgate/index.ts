// deno-lint-ignore-file no-explicit-any
// OpenCall SMS Gateway — SMS-Gate-compatible REST API (v1.5.33)
// Implements the subset of the SMS Gate for Android 3rd-party API that
// third-party apps (SmartBookly, n8n, Zapier…) actually use:
//   POST /3rdparty/v1/messages       — send SMS (Basic auth OR Bearer JWT)
//   GET  /3rdparty/v1/messages/{id}  — message status
//   POST /3rdparty/v1/auth/token     — mint a Bearer token from Basic auth
//   GET  /3rdparty/v1/webhooks       — webhook list (compat, read-only)
//   POST /3rdparty/v1/webhooks       — webhook register (compat, stored no-op)
//   DELETE /3rdparty/v1/webhooks/{id}— webhook delete (compat, 204)
//   GET  /3rdparty/v1/state          — app state probe (compat)
// Auth maps 1:1 onto the existing gateway_accounts (username/password or
// ogt_ token via gateway_api_* RPCs), so credentials never change.

const SUPABASE_URL = "https://ijrfqjxdajgoaysazxle.supabase.co";
const ANON_KEY = "sb_publishable_Af8dsVLpjtO6Mpe3zpmI9Q_odky49Fh";
const RPC = `${SUPABASE_URL}/rest/v1/rpc`;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, prefer",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
};

function json(body: any, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

// ---- auth parsing -------------------------------------------------------

// Basic auth → {username, password}; Bearer ogt_… → {token}
async function parseAuth(req: Request): Promise<{
  username?: string; password?: string; token?: string;
} | null> {
  const h = req.headers.get("Authorization") || "";
  if (h.startsWith("Basic ")) {
    try {
      const raw = atob(h.slice(6).trim());
      const i = raw.indexOf(":");
      if (i < 0) return null;
      return { username: raw.slice(0, i), password: raw.slice(i + 1) };
    } catch { return null; }
  }
  if (h.startsWith("Bearer ")) {
    const t = h.slice(7).trim();
    // accept our own permanent tokens directly
    if (t.startsWith("ogt_")) return { token: t };
    return { token: t };
  }
  return null;
}

// call a gateway_api_* RPC; returns {ok, data} or {ok:false, error, status}
async function rpc(name: string, args: Record<string, any>) {
  const res = await fetch(`${RPC}/${name}`, {
    method: "POST",
    headers: {
      apikey: ANON_KEY,
      Authorization: `Bearer ${ANON_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(args),
  });
  const text = await res.text();
  let data: any = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok) {
    const msg = (data && (data.message || data.error)) || text || res.statusText;
    const status = /invalid gateway credentials/i.test(String(msg)) ? 401
      : /rate limit/i.test(String(msg)) ? 429
      : /device online|device_offline/i.test(String(msg)) ? 503
      : /must be|invalid/i.test(String(msg)) ? 400
      : 500;
    return { ok: false as const, error: String(msg), status };
  }
  return { ok: true as const, data };
}

// map a PostgREST error to the SMS-Gate error envelope
function errBody(message: string, error: string) {
  return { error, message };
}

// SMS-Gate message states: Pending → Sent → Delivered | Failed | Cancelled
// our statuses: queued | sent | delivered | failed
function mapState(s: string | null | undefined): string {
  switch (s) {
    case "queued": return "Pending";
    case "sent": return "Sent";
    case "delivered": return "Delivered";
    case "failed": return "Failed";
    case "cancelled": return "Cancelled";
    default: return "Pending";
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });

  const url = new URL(req.url);
  // Edge Functions strip nothing: the function name may or may not be in the
  // path depending on how it is invoked. Normalize to the part after
  // /3rdparty/v1/ so both /functions/v1/smsgate/3rdparty/v1/messages and
  // /functions/v1/smsgate/messages work.
  let p = url.pathname;
  const m = p.match(/3rdparty\/v1\/(.*)$/);
  const path = m ? m[1] : p.replace(/^\/+/, "").replace(/^smmsgate\/?/, "");

  try {
    // ---------- POST /auth/token ----------
    if (path === "auth/token" && req.method === "POST") {
      const auth = await parseAuth(req);
      if (!auth || (!auth.username && !auth.token)) {
        return json(errBody("missing Basic or Bearer credentials", "UnauthorizedError"), 401);
      }
      const body = await req.json().catch(() => ({}));
      const ttl = Math.min(Math.max(Number(body?.ttl) || 3600, 60), 86400);
      // our tokens are permanent; we still answer with the SMS-Gate shape
      const r = await rpc("gateway_api_login", {
        p_username: auth.username ?? null, p_password: auth.password ?? null,
      });
      if (!r.ok) {
        return json(errBody(r.error, r.status === 401 ? "UnauthorizedError" : "InternalError"), r.status);
      }
      return json({
        id: r.data.token.slice(-20),
        token_type: "Bearer",
        access_token: r.data.token,
        expires_at: new Date(Date.now() + ttl * 1000).toISOString(),
      }, 201);
    }

    // ---------- POST /messages ----------
    if ((path === "messages" || path === "message") && req.method === "POST") {
      const auth = await parseAuth(req);
      if (!auth || (!auth.username && !auth.token)) {
        return json(errBody("missing Basic or Bearer credentials", "UnauthorizedError"), 401);
      }
      const body = await req.json().catch(() => null);
      if (!body) return json(errBody("invalid JSON body", "ValidationError"), 400);

      // textMessage / legacy message / dataMessage (data not supported → 400)
      let text: string | null = null;
      if (body.textMessage && typeof body.textMessage.text === "string") {
        text = body.textMessage.text;
      } else if (typeof body.message === "string") {
        text = body.message; // legacy field
      } else if (body.dataMessage) {
        return json(errBody("data messages are not supported by this gateway", "ValidationError"), 400);
      }
      if (!text) return json(errBody("textMessage.text is required", "ValidationError"), 400);

      const phones: string[] = Array.isArray(body.phoneNumbers) ? body.phoneNumbers : [];
      if (phones.length === 0) return json(errBody("phoneNumbers must be a non-empty array", "ValidationError"), 400);
      // keep the E.164 rule of our backend; strip sms-gate style separators
      const to = phones[0].replace(/[\s\-()]/g, "");
      if (!/^\+[1-9][0-9]{3,15}$/.test(to) && url.searchParams.get("skipPhoneValidation") !== "true") {
        return json(errBody(`invalid phone number format: ${phones[0]} — use E.164 (+country…)`, "ValidationError"), 400);
      }

      const cred = auth.token
        ? { p_token: auth.token, p_to: to, p_body: text }
        : { p_username: auth.username, p_password: auth.password, p_to: to, p_body: text };
      const r = await rpc("gateway_api_send_sms", cred);
      if (!r.ok) {
        return json(errBody(r.error, r.status === 401 ? "UnauthorizedError" : r.status === 429 ? "RateLimitError" : "InternalError"), r.status);
      }
      // SMS-Gate returns 202 with id/status/createdAt
      return json({
        id: r.data.smsId,
        status: "queued",
        createdAt: new Date().toISOString(),
      }, 202);
    }

    // ---------- GET /messages/{id} ----------
    const msgMatch = path.match(/^messages\/([0-9a-fA-F-]{36})$/);
    if (msgMatch && req.method === "GET") {
      const auth = await parseAuth(req);
      if (!auth || (!auth.username && !auth.token)) {
        return json(errBody("missing Basic or Bearer credentials", "UnauthorizedError"), 401);
      }
      const cred = auth.token
        ? { p_token: auth.token, p_limit: 100 }
        : { p_username: auth.username, p_password: auth.password, p_limit: 100 };
      const r = await rpc("gateway_api_get_sms", cred);
      if (!r.ok) return json(errBody(r.error, r.status === 401 ? "UnauthorizedError" : "InternalError"), r.status);
      const rows = Array.isArray(r.data) ? r.data : [];
      const hit = rows.find((x: any) => x.id === msgMatch[1]);
      if (!hit) return json(errBody("message not found", "NotFoundError"), 404);
      return json({
        id: hit.id,
        state: mapState(hit.status),
        createdAt: hit.created_at,
      });
    }

    // ---------- webhooks (compat: accept + store nothing) ----------
    if (path === "webhooks" && req.method === "GET") {
      const auth = await parseAuth(req);
      if (!auth || (!auth.username && !auth.token)) return json(errBody("missing credentials", "UnauthorizedError"), 401);
      return json([]); // no webhooks registered (read-only compat)
    }
    if (path === "webhooks" && req.method === "POST") {
      const auth = await parseAuth(req);
      if (!auth || (!auth.username && !auth.token)) return json(errBody("missing credentials", "UnauthorizedError"), 401);
      const body = await req.json().catch(() => ({}));
      return json({ id: crypto.randomUUID().slice(0, 22), url: body?.url ?? "", event: body?.event ?? "sms:received" }, 201);
    }
    const whDel = path.match(/^webhooks\/(.+)$/);
    if (whDel && req.method === "DELETE") {
      const auth = await parseAuth(req);
      if (!auth || (!auth.username && !auth.token)) return json(errBody("missing credentials", "UnauthorizedError"), 401);
      return new Response(null, { status: 204, headers: CORS });
    }

    // ---------- GET /state ----------
    if (path === "state" && req.method === "GET") {
      const auth = await parseAuth(req);
      if (!auth || (!auth.username && !auth.token)) {
        return json(errBody("missing Basic or Bearer credentials", "UnauthorizedError"), 401);
      }
      const cred = auth.token
        ? { p_token: auth.token }
        : { p_username: auth.username, p_password: auth.password };
      const r = await rpc("gateway_api_status", cred);
      if (!r.ok) return json(errBody(r.error, r.status === 401 ? "UnauthorizedError" : "InternalError"), r.status);
      const d = r.data;
      return json({
        androidId: "opencall-gateway",
        device: d.device ? {
          id: "opencall-" + d.username,
          model: d.device.name ?? "gateway",
          androidVersion: "",
          apiLevel: 0,
          battery: d.device.battery ?? 100,
          simCount: 1,
        } : null,
        version: "1.5.33",
        pushNotificationEnabled: false,
        remoteProcessingEnabled: false,
        smsEnabled: !!d.sms?.enabled,
      });
    }

    return json(errBody(`no route for ${req.method} ${path}`, "NotFoundError"), 404);
  } catch (e: any) {
    return json(errBody(String(e?.message ?? e), "InternalError"), 500);
  }
});
