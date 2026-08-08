const CACHE = 'envbudget-v3';
const ASSETS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon.svg'
];
const DOC_TIMEOUT = 3500;

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      .then((cache) => cache.addAll(ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// ตัวแอปทั้งหมดอยู่ใน index.html ไฟล์เดียว จึงต้องเอาเน็ตนำสำหรับหน้าเอกสาร
// ไม่งั้นแคชจะค้างเวอร์ชันเก่าไว้ตลอดจนกว่าจะมีคนไปแก้ไฟล์นี้ (ซึ่งเคยเกิดขึ้นมาแล้ว)
// ไฟล์ประกอบอื่นแทบไม่เปลี่ยน ใช้แคชนำได้แล้วค่อยอัปเดตเบื้องหลัง
function isDocument(req, url) {
  return req.mode === 'navigate' ||
    url.pathname.endsWith('/') ||
    url.pathname.endsWith('/index.html');
}

function networkFirst(req) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (res) => { if (!settled) { settled = true; resolve(res); } };

    // เน็ตอืดกว่านี้ถือว่าช้าเกินรอ ใช้ของในแคชไปก่อน
    const timer = setTimeout(() => {
      caches.match('./index.html').then((hit) => { if (hit) done(hit); });
    }, DOC_TIMEOUT);

    fetch(req)
      .then((res) => {
        clearTimeout(timer);
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((cache) => cache.put('./index.html', copy));
        }
        done(res);
      })
      .catch(() => {
        clearTimeout(timer);
        caches.match('./index.html').then((hit) => {
          done(hit || new Response('ออฟไลน์และยังไม่มีข้อมูลในแคช', {
            status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' }
          }));
        });
      });
  });
}

function cacheFirst(req) {
  return caches.match(req).then((hit) => {
    if (hit) {
      // อัปเดตเบื้องหลังไว้ใช้รอบหน้า
      fetch(req).then((res) => {
        if (res && res.ok) caches.open(CACHE).then((cache) => cache.put(req, res.clone()));
      }).catch(() => {});
      return hit;
    }
    return fetch(req).then((res) => {
      if (res && res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then((cache) => cache.put(req, copy));
      }
      return res;
    });
  });
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  event.respondWith(isDocument(req, url) ? networkFirst(req) : cacheFirst(req));
});
