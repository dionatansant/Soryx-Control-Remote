const CACHE_NAME = 'soryx-remote-v1';
const CORE_ASSETS = ['/', '/documentacao', '/manifest.json', '/icon-192.png', '/icon-512.png'];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(CORE_ASSETS))
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (event) => {
  // Only GET requests are safe to serve from cache as a fallback — commands
  // (POST /key, /text, /apps/launch, ...) must always hit the network live,
  // never get swallowed by a stale cache lookup.
  if (event.request.method !== 'GET') return;

  event.respondWith(
    fetch(event.request).catch(() => caches.match(event.request))
  );
});
