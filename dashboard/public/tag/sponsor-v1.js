/*!
 * PrimeTech sponsor tag v1: a muted "Sponsored" video card for publisher sites.
 *
 *   <script async src="https://prime-tech.app/tag/sponsor-v1.js"></script>
 *   <div data-pt-sponsor="home"></div>
 *
 * Privacy: no cookies, no storage, no device identifiers. Each card sends anonymous playback events
 * (impression, quartiles, complete, click) with the device class and a country estimated from the
 * browser's time zone or language. Nothing loads until the card is near the screen, and if there is
 * no live sponsor the element stays empty (no layout shift).
 */
(function () {
  'use strict'
  if (window.PrimeTechSponsor) return
  var script = document.currentScript
  var BASE = script && script.src ? new URL(script.src).origin : 'https://prime-tech.app'
  var AD_URL = BASE + '/ads/v1/ad-requests'
  var EVENT_URL = BASE + '/ads/v1/events'
  var MANIFEST_URL = BASE + '/media/creatives.json'
  var VIEWABLE = 0.5 // share of the card on screen
  var IMPRESSION_AFTER_MS = 2000 // continuous play while viewable (video viewability standard)

  var ZONES = {
    'Africa/Lagos': 'NG', 'Africa/Accra': 'GH', 'Africa/Nairobi': 'KE', 'Africa/Johannesburg': 'ZA',
    'Africa/Porto-Novo': 'BJ', 'Africa/Lome': 'TG', 'Africa/Cairo': 'EG', 'Africa/Kampala': 'UG',
    'Africa/Dar_es_Salaam': 'TZ', 'Africa/Kigali': 'RW', 'Africa/Addis_Ababa': 'ET', 'Africa/Abidjan': 'CI',
    'Africa/Dakar': 'SN', 'Europe/London': 'GB', 'Europe/Dublin': 'IE', 'Europe/Paris': 'FR', 'Europe/Berlin': 'DE',
    'Asia/Dubai': 'AE', 'Asia/Qatar': 'QA', 'Asia/Riyadh': 'SA', 'Australia/Sydney': 'AU', 'Australia/Melbourne': 'AU',
    'America/Toronto': 'CA', 'America/Vancouver': 'CA', 'America/New_York': 'US', 'America/Chicago': 'US',
    'America/Denver': 'US', 'America/Los_Angeles': 'US', 'America/Phoenix': 'US', 'America/Anchorage': 'US',
    'Pacific/Honolulu': 'US', 'America/Detroit': 'US', 'America/Indiana/Indianapolis': 'US',
  }

  function country() {
    try {
      var tz = Intl.DateTimeFormat().resolvedOptions().timeZone
      if (ZONES[tz]) return ZONES[tz]
    } catch (e) { /* older browsers */ }
    var langs = navigator.languages || [navigator.language || '']
    for (var i = 0; i < langs.length; i++) {
      var m = /[-_]([A-Za-z]{2})$/.exec(langs[i] || '')
      if (m) return m[1].toUpperCase()
    }
    return 'US'
  }

  function deviceType() {
    return /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent) ? 'MOBILE' : 'WEB'
  }

  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID()
    var b = new Uint8Array(16)
    crypto.getRandomValues(b)
    b[6] = (b[6] & 15) | 64
    b[8] = (b[8] & 63) | 128
    var h = Array.prototype.map.call(b, function (x) { return (x + 256).toString(16).slice(1) }).join('')
    return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20)
  }

  function post(url, body) {
    return fetch(url, {
      method: 'POST',
      mode: 'cors',
      credentials: 'omit',
      keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  }

  var manifest = null
  function getManifest(force) {
    if (!manifest || force) {
      manifest = fetch(MANIFEST_URL, { credentials: 'omit', cache: force ? 'reload' : 'default' })
        .then(function (r) { return r.ok ? r.json() : { creatives: {} } })
        .catch(function () { return { creatives: {} } })
    }
    return manifest
  }

  var STYLE = '' +
    '.pt-sp{--pt-sp-bg:#0b1220;--pt-sp-fg:#f4f7fb;--pt-sp-muted:#a9b4c4;--pt-sp-border:rgba(255,255,255,.12);--pt-sp-accent:#f5b301;' +
    'margin:24px auto;max-width:720px;border:1px solid var(--pt-sp-border);border-radius:14px;overflow:hidden;background:var(--pt-sp-bg);color:var(--pt-sp-fg);' +
    'font:14px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}' +
    '.pt-sp-top{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:8px 12px;font-size:12px;color:var(--pt-sp-muted)}' +
    '.pt-sp-top b{color:var(--pt-sp-fg);font-weight:600}' +
    '.pt-sp-box{position:relative;aspect-ratio:16/9;background:#000}' +
    '.pt-sp-box video{display:block;width:100%;height:100%;object-fit:cover;cursor:pointer}' +
    '.pt-sp-btn{position:absolute;bottom:10px;width:36px;height:36px;border:0;border-radius:50%;background:rgba(0,0,0,.6);color:#fff;font-size:16px;cursor:pointer}' +
    '.pt-sp-sound{right:10px}.pt-sp-play{left:10px}' +
    '.pt-sp-btn:focus-visible,.pt-sp-cta:focus-visible{outline:2px solid var(--pt-sp-accent);outline-offset:2px}' +
    '.pt-sp-bottom{display:flex;justify-content:flex-end;padding:10px 12px}' +
    '.pt-sp-cta{display:inline-block;padding:8px 14px;border-radius:8px;background:var(--pt-sp-accent);color:#111;font-weight:700;text-decoration:none}'

  function injectStyle() {
    if (document.getElementById('pt-sponsor-style')) return
    var s = document.createElement('style')
    s.id = 'pt-sponsor-style'
    s.textContent = STYLE
    document.head.appendChild(s)
  }

  function el(tag, attrs, text) {
    var e = document.createElement(tag)
    for (var k in attrs) e.setAttribute(k, attrs[k])
    if (text != null) e.textContent = text
    return e
  }

  function mount(host) {
    if (host.getAttribute('data-pt-ready')) return
    host.setAttribute('data-pt-ready', 'pending')
    var near = new IntersectionObserver(function (entries) {
      if (entries.some(function (e) { return e.isIntersecting })) {
        near.disconnect()
        load(host).catch(function () { /* never break the page */ })
      }
    }, { rootMargin: '300px 0px' })
    near.observe(host)
  }

  function load(host) {
    var session = uuid()
    var where = { country: country(), deviceType: deviceType() }
    return post(AD_URL, {
      sessionId: session, consent: false, deviceType: where.deviceType, country: where.country,
      slots: 1, maxAdSeconds: 60, podSeconds: 60,
    })
      .then(function (r) { return r.ok ? r.json() : { ads: [] } })
      .then(function (res) {
        var ad = res && res.ads && res.ads[0]
        if (!ad) { host.setAttribute('data-pt-ready', 'empty'); return }
        return getManifest(false).then(function (m) {
          var cr = m.creatives && m.creatives[ad.creativeId]
          return cr ? cr : getManifest(true).then(function (m2) { return m2.creatives && m2.creatives[ad.creativeId] })
        }).then(function (cr) {
          if (!cr) { host.setAttribute('data-pt-ready', 'empty'); return }
          render(host, ad, cr, session, where)
        })
      })
  }

  function render(host, ad, cr, session, where) {
    injectStyle()
    var reduceMotion = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches
    var card = el('figure', { class: 'pt-sp', 'aria-label': 'Sponsored video from ' + cr.sponsor })
    var top = el('div', { class: 'pt-sp-top' })
    var label = el('span', {}, 'Sponsored · ')
    label.appendChild(el('b', {}, cr.sponsor))
    top.appendChild(label)
    var box = el('div', { class: 'pt-sp-box' })
    var video = el('video', { playsinline: '', muted: '', preload: 'metadata', src: BASE + cr.src, title: 'Sponsored video from ' + cr.sponsor })
    video.muted = true
    var sound = el('button', { type: 'button', class: 'pt-sp-btn pt-sp-sound', 'aria-label': 'Turn sound on' }, '🔇')
    var play = el('button', { type: 'button', class: 'pt-sp-btn pt-sp-play', 'aria-label': 'Play' }, '▶')
    box.appendChild(video)
    box.appendChild(sound)
    box.appendChild(play)
    var bottom = el('div', { class: 'pt-sp-bottom' })
    var cta = el('a', { class: 'pt-sp-cta', href: cr.clickUrl, target: '_blank', rel: 'sponsored noopener' }, 'Learn more')
    cta.setAttribute('aria-label', 'Learn more about ' + cr.sponsor + ' (opens in a new tab)')
    bottom.appendChild(cta)
    card.appendChild(top)
    card.appendChild(box)
    card.appendChild(bottom)
    host.textContent = ''
    host.appendChild(card)
    host.setAttribute('data-pt-ready', 'shown')

    var fired = {}
    var visible = false
    var watchedMs = 0
    var lastTick = 0

    function send(type) {
      if (fired[type]) return
      fired[type] = true
      var e = {
        eventId: uuid(), type: type, eventTimeMs: Date.now(), sessionId: session,
        adId: ad.adId, creativeId: ad.creativeId, campaignId: ad.campaignId, lineItemId: ad.lineItemId,
        deviceType: where.deviceType, country: where.country, consent: false, auctionId: ad.auctionId,
      }
      if (type === 'IMPRESSION') e.priceMicros = ad.priceMicros
      post(EVENT_URL, { events: [e] }).catch(function () {})
    }

    function setPlayIcon() {
      play.textContent = video.paused ? '▶' : '❚❚'
      play.setAttribute('aria-label', video.paused ? 'Play' : 'Pause')
    }

    video.addEventListener('play', setPlayIcon)
    video.addEventListener('pause', setPlayIcon)
    video.addEventListener('timeupdate', function () {
      var now = Date.now()
      if (!video.paused && visible && !document.hidden && lastTick) watchedMs += now - lastTick
      lastTick = video.paused ? 0 : now
      if (!fired.IMPRESSION && watchedMs >= IMPRESSION_AFTER_MS) send('IMPRESSION')
      if (!fired.IMPRESSION || !video.duration) return
      var p = video.currentTime / video.duration
      if (p >= 0.25) send('FIRST_QUARTILE')
      if (p >= 0.5) send('MIDPOINT')
      if (p >= 0.75) send('THIRD_QUARTILE')
    })
    video.addEventListener('ended', function () {
      if (fired.IMPRESSION) {
        send('FIRST_QUARTILE'); send('MIDPOINT'); send('THIRD_QUARTILE'); send('COMPLETE')
      }
      lastTick = 0
    })

    var userPaused = false
    play.addEventListener('click', function () {
      if (video.paused) { userPaused = false; video.play().catch(function () {}) } else { userPaused = true; video.pause() }
    })
    sound.addEventListener('click', function () {
      video.muted = !video.muted
      sound.textContent = video.muted ? '🔇' : '🔊'
      sound.setAttribute('aria-label', video.muted ? 'Turn sound on' : 'Turn sound off')
    })
    function click() {
      if (!fired.IMPRESSION && watchedMs > 0) send('IMPRESSION')
      send('CLICK')
    }
    cta.addEventListener('click', click)
    video.addEventListener('click', function () {
      click()
      window.open(cr.clickUrl, '_blank', 'noopener')
    })

    new IntersectionObserver(function (entries) {
      var e = entries[entries.length - 1]
      visible = e.intersectionRatio >= VIEWABLE
      lastTick = 0
      if (visible && video.paused && !video.ended && !userPaused && !reduceMotion) video.play().catch(function () {})
      if (!visible && !video.paused) video.pause()
    }, { threshold: [0, VIEWABLE, 1] }).observe(card)
    document.addEventListener('visibilitychange', function () {
      lastTick = 0
      if (document.hidden && !video.paused) video.pause()
      else if (!document.hidden && visible && video.paused && !video.ended && !userPaused && !reduceMotion) video.play().catch(function () {})
    })
  }

  function scan() {
    var hosts = document.querySelectorAll('[data-pt-sponsor]:not([data-pt-ready])')
    for (var i = 0; i < hosts.length; i++) mount(hosts[i])
  }

  window.PrimeTechSponsor = { scan: scan, version: 1 }
  if (!('IntersectionObserver' in window) || !window.fetch) return
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan)
  else scan()
  // Single-page sites add new placements as visitors move between pages (at most one scan per frame).
  var queued = false
  new MutationObserver(function () {
    if (queued) return
    queued = true
    requestAnimationFrame(function () { queued = false; scan() })
  }).observe(document.documentElement, { childList: true, subtree: true })
})()
