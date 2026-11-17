/* ============================================================
   deck.js — presentation engine
   nav (keys / swipe / hash), EN⇄RU switch, counters, notes,
   overview, print, capture mode. No dependencies.
   ============================================================ */
(function () {
  'use strict';

  var root = document.documentElement;
  var slides = Array.prototype.slice.call(document.querySelectorAll('.slide'));
  if (!slides.length) return;

  var fill = document.querySelector('.rail .fill');
  var curEl = document.querySelector('.count b');
  var totEl = document.querySelector('.count span');
  var notesbar = document.querySelector('.notesbar .nb');
  var overview = document.querySelector('.overview');
  var idx = 0;
  var counters = [];

  /* ---------- language ---------- */
  var lang = null;
  try { lang = localStorage.getItem('deck-lang'); } catch (e) { /* private mode */ }
  if (!lang) {
    var nav = (navigator.language || 'en').toLowerCase();
    lang = nav.indexOf('ru') === 0 ? 'ru' : 'en';
  }

  function applyLang(l) {
    lang = l;
    try { localStorage.setItem('deck-lang', l); } catch (e) { /* ignore */ }
    root.setAttribute('data-lang', l);
    Array.prototype.forEach.call(document.querySelectorAll('[data-en]'), function (el) {
      var v = (l === 'ru' ? el.getAttribute('data-ru') : null) || el.getAttribute('data-en');
      if (el.hasAttribute('data-html')) el.innerHTML = v;
      else el.textContent = v;
    });
    Array.prototype.forEach.call(document.querySelectorAll('.lang button'), function (b) {
      b.setAttribute('aria-pressed', b.getAttribute('data-lang') === l ? 'true' : 'false');
    });
    buildOverview();
    syncNotes();
  }

  /* ---------- counters ---------- */
  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function fmt(v, dec) {
    var s = v.toFixed(dec);
    if (dec === 0) s = s.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    return s;
  }

  function runCounters(slide) {
    Array.prototype.forEach.call(slide.querySelectorAll('[data-count]'), function (el, k) {
      var to = parseFloat(el.getAttribute('data-count')) || 0;
      var dec = parseInt(el.getAttribute('data-dec') || '0', 10);
      var pre = el.getAttribute('data-prefix') || '';
      var suf = el.getAttribute('data-suffix') || '';
      var dur = parseInt(el.getAttribute('data-dur') || '1300', 10);
      var t0 = null;
      var delay = 180 + k * 110;
      el.textContent = pre + fmt(0, dec) + suf;

      function frame(t) {
        if (t0 === null) t0 = t + delay;
        var p = Math.min(1, Math.max(0, (t - t0) / dur));
        var e = 1 - Math.pow(1 - p, 3);
        el.textContent = pre + fmt(to * e, dec) + suf;
        if (p < 1) counters.push(requestAnimationFrame(frame));
      }
      counters.push(requestAnimationFrame(frame));
    });
  }

  function stopCounters() {
    counters.forEach(function (id) { cancelAnimationFrame(id); });
    counters = [];
  }

  /* ---------- navigation ---------- */
  function syncNotes() {
    if (!notesbar) return;
    var n = slides[idx] && slides[idx].querySelector('.notes');
    notesbar.textContent = n ? n.textContent : '';
  }

  function go(n) {
    n = Math.max(0, Math.min(slides.length - 1, n));
    if (n === idx && slides[n].classList.contains('on')) return;

    stopCounters();
    idx = n;

    slides.forEach(function (s, k) {
      s.classList.toggle('on', k === n);
      s.classList.toggle('past', k < n);
      s.setAttribute('aria-hidden', k === n ? 'false' : 'true');
    });

    if (fill) fill.style.width = ((n + 1) / slides.length * 100).toFixed(3) + '%';
    if (curEl) curEl.textContent = pad(n + 1);
    if (totEl) totEl.textContent = '/ ' + pad(slides.length);

    try { history.replaceState(null, '', '#' + (n + 1)); } catch (e) { /* file:// */ }

    Array.prototype.forEach.call(overview ? overview.children : [], function (b, k) {
      b.classList.toggle('on', k === n);
    });

    syncNotes();
    runCounters(slides[n]);
  }

  /* ---------- overview ---------- */
  function buildOverview() {
    if (!overview) return;
    overview.innerHTML = '';
    slides.forEach(function (s, k) {
      var b = document.createElement('button');
      b.className = 'ov' + (k === idx ? ' on' : '');
      b.type = 'button';
      var title = s.getAttribute(lang === 'ru' ? 'data-title-ru' : 'data-title-en')
        || s.getAttribute('data-title-en') || 'Slide ' + (k + 1);
      b.innerHTML = '<i>' + pad(k + 1) + '</i><span></span>';
      b.lastChild.textContent = title;
      b.addEventListener('click', function () {
        document.body.classList.remove('overview-on');
        go(k);
      });
      overview.appendChild(b);
    });
  }

  /* ---------- input ---------- */
  function toggle(cls, force) {
    document.body.classList.toggle(cls, force);
  }

  document.addEventListener('keydown', function (e) {
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    var k = e.key;
    switch (k) {
      case 'ArrowRight': case 'ArrowDown': case 'PageDown': case ' ':
        e.preventDefault(); go(idx + 1); break;
      case 'ArrowLeft': case 'ArrowUp': case 'PageUp':
        e.preventDefault(); go(idx - 1); break;
      case 'Home': e.preventDefault(); go(0); break;
      case 'End': e.preventDefault(); go(slides.length - 1); break;
      case 'n': case 'N': toggle('notes'); break;
      case 'o': case 'O': toggle('overview-on'); break;
      case 'Escape': document.body.classList.remove('overview-on'); break;
      case 'l': case 'L': applyLang(lang === 'en' ? 'ru' : 'en'); break;
      case 'p': case 'P': window.print(); break;
      case 'f': case 'F':
        if (!document.fullscreenElement) {
          if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen();
        } else if (document.exitFullscreen) document.exitFullscreen();
        break;
      default: return;
    }
  });

  Array.prototype.forEach.call(document.querySelectorAll('.lang button'), function (b) {
    b.addEventListener('click', function () { applyLang(b.getAttribute('data-lang')); });
  });

  /* swipe */
  var sx = 0, sy = 0, st = 0;
  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) return;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = Date.now();
  }, { passive: true });

  document.addEventListener('touchend', function (e) {
    if (!sx || !e.changedTouches.length) return;
    var dx = e.changedTouches[0].clientX - sx;
    var dy = e.changedTouches[0].clientY - sy;
    var dt = Date.now() - st;
    sx = 0;
    if (dt > 700) return;
    if (Math.abs(dx) > 46 && Math.abs(dx) > Math.abs(dy) * 1.4) {
      go(idx + (dx < 0 ? 1 : -1));
    }
  }, { passive: true });

  window.addEventListener('hashchange', function () {
    var n = parseInt(location.hash.slice(1), 10) - 1;
    if (!isNaN(n) && n !== idx) go(n);
  });

  /* ---------- boot ---------- */
  var start = parseInt((location.hash || '').slice(1), 10) - 1;
  if (isNaN(start) || start < 0) start = 0;

  if (/[?&]capture\b/.test(location.search)) document.body.classList.add('capture');

  applyLang(lang);
  if (totEl) totEl.textContent = '/ ' + pad(slides.length);
  slides.forEach(function (s, k) {
    if (k !== start) { s.classList.toggle('past', k < start); }
  });
  idx = start - 1;
  go(start);
  document.body.classList.add('ready');

  /* hook for CI renderers and the presenter */
  window.__deck = {
    go: go,
    lang: applyLang,
    count: slides.length,
    get index() { return idx; }
  };
})();
