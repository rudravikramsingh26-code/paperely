const VERSION = 'paperly-v3';
const APP_CACHE = `${VERSION}-app`;
const RUNTIME_CACHE = `${VERSION}-runtime`;
const APP_FILES = ['./', './index.html', './manifest.webmanifest', './icons/paperly.svg'];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(APP_CACHE).then(cache => cache.addAll(APP_FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => ![APP_CACHE, RUNTIME_CACHE].includes(k)).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.hostname === 'cdn.jsdelivr.net' && (url.pathname.includes('/pdfjs-dist/') || url.pathname.includes('/jspdf/') || url.pathname.includes('/jszip/'))) {
    event.respondWith((async () => {
      const cache = await caches.open(RUNTIME_CACHE);
      const cached = await cache.match(req);
      if (cached) return cached;
      try { const response = await fetch(req); if (response && response.ok) await cache.put(req, response.clone()); return response; }
      catch { return cached || Response.error(); }
    })());
    return;
  }
  if (url.origin === self.location.origin) {
    event.respondWith((async () => {
      const cache = await caches.open(APP_CACHE);
      const cached = await cache.match(req, {ignoreSearch:true});
      if (cached) return cached;
      try { const response = await fetch(req); if (response && response.ok) await cache.put(req,response.clone()); return response; }
      catch { return cached || (req.mode === 'navigate' ? cache.match('./index.html') : Response.error()); }
    })());
  }
});