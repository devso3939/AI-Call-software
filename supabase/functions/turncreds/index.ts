// OpenCall TURN credentials proxy (v1.5.36)
// Cloudflare's free TURN endpoint (speed.cloudflare.com/turn-creds) returns
// 403 when a browser Origin header is present, so the web app cannot fetch
// it directly. This function proxies it server-side and adds CORS headers.
// The Android app keeps fetching Cloudflare directly (native HTTP, no CORS).
const UPSTREAM = "https://speed.cloudflare.com/turn-creds";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const r = await fetch(UPSTREAM, {
      // Cloudflare's turn-creds only answers 200 when the request looks like
      // it comes from their own speed-test page (same-site Origin+Referer +
      // browser UA). Anything else → 403. Verified live via curl matrix.
      headers: {
        "Accept": "application/json",
        "Origin": "https://speed.cloudflare.com",
        "Referer": "https://speed.cloudflare.com/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
      },
    });
    const body = await r.text();
    if (r.status !== 200) {
      return new Response(JSON.stringify({ error: "upstream " + r.status }), {
        status: 502,
        headers: { ...CORS, "Content-Type": "application/json" },
      });
    }
    return new Response(body, {
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  } catch (e) {
    return new Response(JSON.stringify({ error: String(e) }), {
      status: 502,
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  }
});
