// The service worker: keeps the app itself (the page, its script and stylesheet, its icons) on the
// device, so that it opens without the server. The notes are in IndexedDB already, and the API is
// never touched here: /api/ and /export always go to the server.
//
// - The page ("/"): from the server first, so a deploy shows at once; from the cache if the server
//   can't be reached or takes longer than NAVIGATION_TIMEOUT_MS. Each copy fetched replaces the
//   cached one, with the bundles it names; bundles it doesn't name are dropped.
// - The bundles: named after their content, so from the cache when there.
// - Icons and manifest: from the cache, refreshed in the background.
//
// Plain JavaScript, copied as is: its URL must stay /sw.js, and the browser compares its bytes to
// tell whether it changed.

const CACHE = "notes-app-v1";
const PAGE = "/";
const NAVIGATION_TIMEOUT_MS = 4000;
const BUNDLE = /^\/bundle-[0-9a-f]+\.(?:js|css)$/;
const BUNDLE_IN_PAGE = /bundle-[0-9a-f]+\.(?:js|css)/g;

self.addEventListener("install", (event) => {
  // The first offline start mustn't depend on a reload having happened since.
  event.waitUntil(fetch(PAGE, { cache: "no-cache" }).then(keepPage).catch(() => undefined).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key !== CACHE) await caches.delete(key);
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/api/") || url.pathname === "/export") return;
  if (request.mode === "navigate") {
    if (url.pathname === PAGE || url.pathname === "/index.html") event.respondWith(page(event));
    return;
  }
  if (BUNDLE.test(url.pathname)) event.respondWith(cacheFirst(request));
  else if (url.pathname.startsWith("/icons/") || url.pathname === "/manifest.webmanifest") event.respondWith(refreshInBackground(event));
});

/** The server's page if it answers in time, else the cached one; a late answer still updates the cache. */
async function page(event) {
  const fromServer = fetch(event.request).then(async (response) => {
    if (response.ok) event.waitUntil(keepPage(response.clone()));
    return response;
  });
  event.waitUntil(fromServer.catch(() => undefined));
  const timeout = new Promise((resolve) => setTimeout(() => resolve(undefined), NAVIGATION_TIMEOUT_MS));
  try {
    const response = await Promise.race([fromServer, timeout]);
    if (response) return response;
  } catch {
    // Offline, or the server is down: the cached page, below.
  }
  const cached = await caches.match(PAGE);
  return cached ?? fromServer;
}

/** Caches the page and the bundles it names, then drops the bundles it doesn't name. */
async function keepPage(response) {
  if (!response.ok) return;
  const html = await response.clone().text();
  const wanted = new Set([...html.matchAll(BUNDLE_IN_PAGE)].map((match) => "/" + match[0]));
  const cache = await caches.open(CACHE);
  for (const path of wanted) {
    if (await cache.match(path)) continue;
    const bundle = await fetch(path);
    if (!bundle.ok) return; // Not the page's bundles after all (a deploy in between): keep the previous page.
    await cache.put(path, bundle);
  }
  await cache.put(PAGE, response);
  for (const request of await cache.keys()) {
    const path = new URL(request.url).pathname;
    if (BUNDLE.test(path) && !wanted.has(path)) await cache.delete(request);
  }
}

async function cacheFirst(request) {
  const cached = await caches.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok) {
    const cache = await caches.open(CACHE);
    await cache.put(request, response.clone());
  }
  return response;
}

async function refreshInBackground(event) {
  const cached = await caches.match(event.request);
  const fromServer = fetch(event.request).then(async (response) => {
    if (response.ok) {
      const cache = await caches.open(CACHE);
      await cache.put(event.request, response.clone());
    }
    return response;
  });
  event.waitUntil(fromServer.catch(() => undefined));
  return cached ?? fromServer;
}
