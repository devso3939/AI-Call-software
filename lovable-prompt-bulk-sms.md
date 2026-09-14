# SmartBookly: Bulk SMS support (small change, few credits)

OpenCall's SMS Gateway now supports **bulk sending** — up to **500 SMS in ONE API request**. One rate-limit hit for the whole batch, one validation pass, per-message tracking ids returned.

## What to change

**1. In `src/lib/smsGateway.ts` — add ONE function** (keep everything else as-is):

```ts
export async function sendBulkSms(messages: { to: string; body: string }[]) {
  const username = localStorage.getItem('oc_gw_username');
  const password = localStorage.getItem('oc_gw_password');
  if (!username || !password) throw new Error('Gateway not configured');
  if (messages.length === 0) return { queued: 0, messages: [] };
  if (messages.length > 500) {
    // split into chunks of 500 and send sequentially
    const results = [];
    for (let i = 0; i < messages.length; i += 500) {
      results.push(await sendBulkSms(messages.slice(i, i + 500)));
    }
    return { queued: messages.length, messages: results.flatMap(r => r.messages) };
  }
  const res = await fetch(`${API}/gateway_api_send_bulk`, {
    method: 'POST',
    headers: { apikey: KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      p_username: username,
      p_password: password,
      p_messages: messages.map(m => ({ to: m.to, body: m.body })),
    }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || 'Bulk send failed');
  return data; // { queued: n, device: "...", messages: [{ smsId, to }, ...] }
}
```

IMPORTANT: `API` is the plain string `https://ijrfqjxdajgoaysazxle.supabase.co/rest/v1/rpc` (no backticks inside the string, no trailing slash). `KEY` is the same anon key already used in the file.

**2. In `OpenCallSmsGatewayCard.tsx` — optional small addition:** show a "Bulk: up to 500 SMS per request ✓" line in the status area (just static text, no new UI component needed).

## API contract (for reference)

- `POST {API}/gateway_api_send_bulk`
- Body: `{ p_username, p_password, p_messages: [{to, body}, ...] }` (1..500 entries)
- `to` must be E.164 format (`+995...`), `body` 1..1600 chars
- **All-or-nothing**: if ANY entry is invalid, NOTHING is sent and the error names the bad index, e.g. `invalid message at index 3 — "to" must be E.164 ... and body 1..1600 chars; NOTHING was sent`
- Success response: `{ queued: 3, device: "SM-S936B", messages: [{ smsId: "...", to: "+995..." }, ...] }` — store `smsId`s if you want delivery tracking later
- Errors to handle: `no gateway device online — the bound phone must heartbeat (open the gateway app on the phone)` (phone offline), `rate limit exceeded — 120 requests per minute` (20+ full batches per minute — unlikely), `invalid gateway credentials`
- The old single-send `gateway_api_send_sms` still works unchanged — no need to touch existing code

## Do NOT

- Do not modify any existing functions or files other than the two above
- Do not add retry logic — failures are all-or-nothing and safe to retry the whole batch
- Do not send more than one batch concurrently (the phone processes them in order anyway)
