/* OpenCall AI service worker — offline app shell + safe update flow */
const CACHE = 'opencall-v26';
const SHELL = [
  '/AI-Call-software/app.html',
  '/AI-Call-software/index.html',
  '/AI-Call-software/manifest.webmanifest',
  '/AI-Call-software/icons/icon-192.png',
  '/AI-Call-software/icons/icon-512.png',
  '/AI-Call-software/icons/icon-maskable-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // same-origin shell files: network-first, fall back to cache when offline
  if (url.origin === location.origin) {
    e.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
          return res;
        })
        .catch(() => caches.match(req))
    );
    return;
  }

  // CDN scripts (supabase/twilio): cache-first is risky, just pass through
  // (calls always need live network anyway — honest by design)
});
