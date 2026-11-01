package dev.mirutime.app.features.webview.data

/**
 * Page-side half of the in-app browser translator.
 *
 * The IIFE installs `window.__miruPageTranslator`:
 *  - `start(generation)` translates the whole page and keeps translating newly
 *    added or changed text;
 *  - `restore()` swaps every translated span back for its original text node;
 *  - `applyResult(batchId, payload)` / `failResult(batchId)` are called by the
 *    Kotlin bridge and only exist so a batch can settle.
 *
 * Every eligible text node is enqueued after the first tap, not just the part
 * that currently intersects the viewport. A chunked TreeWalker queues the whole
 * document without waiting for a scroll; a debounced MutationObserver queues
 * text added later or changed in place.
 *
 * Contract with Kotlin:
 *  - identical strings are sent once per generation; the result is cached and
 *    reused for every later occurrence (`waiting` holds nodes that arrived
 *    while their text was already in flight);
 *  - one batch is built to at most 24000 characters / 300 unique strings, and
 *    up to MAX_INFLIGHT batches (matching the Kotlin semaphore) are in flight.
 *    Each batch is one bridge call; the provider splits it by its own character
 *    budget and runs those chunks with the configured concurrency. A stalled
 *    batch frees its slot after [BATCH_TIMEOUT_MS]; a late result still applies
 *    because the text-to-nodes mapping lives in `sent`, not in the slot;
 *  - a failed batch is dropped (its `sent` entry removed and its slot freed)
 *    without touching the rest of the queue or other in-flight batches. Only
 *    `restore()`, a new `start()` (new generation) or the Kotlin
 *    `deactivate()` abort the whole pipeline;
 *  - text nodes longer than [MAX_NODE_CHARS] are split at a sentence or
 *    whitespace boundary with `splitText()` and every part is translated, so a
 *    chapter body that is one huge text node is never silently skipped;
 *  - the Kotlin generation is part of every batch and every state callback.
 *    `restore()` and a new `start()` leave old results with no batch to attach
 *    to, and `applyResult` also ignores anything received while inactive;
 *  - translated text goes in through `textContent` only. The original lives in
 *    `data-miru-orig`, so restore works even after the page re-rendered nodes.
 *    A `characterData` change inside a translated span means the site owns the
 *    text again: the span is replaced with the new text node and translated
 *    again, so a later `restore()` cannot overwrite it;
 *  - no template literals and no `$` template sequences: this is a Kotlin raw
 *    string, and the only `$` characters are regex end anchors.
 */
val PAGE_TRANSLATOR_JS: String = """(function () {
  'use strict';

  if (window.__miruPageTranslator) { return; }

  var MAX_BATCH_CHARS = 24000;
  var MAX_BATCH_UNITS = 300;
  var MAX_INFLIGHT = 3;
  // Safe chunk size. Longer text nodes are split with splitText(), never
  // dropped: a chapter body can be a single text node of tens of thousands of
  // characters, and skipping it would silently leave the page untranslated.
  var MAX_NODE_CHARS = 4000;
  var BATCH_TIMEOUT_MS = 60000;
  var MUTATION_DEBOUNCE_MS = 350;
  var IDLE_SETTLE_MS = 200;
  var WALK_CHUNK = 400;

  var SKIP_TAGS = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, CODE: 1, PRE: 1,
    TEXTAREA: 1, INPUT: 1, SELECT: 1, OPTION: 1,
    TITLE: 1, SVG: 1, HEAD: 1
  };

  // Unicode property escapes need WebView >= 64. When the constructor throws
  // the punctuation filter is simply off; the rest of the script keeps working.
  var SIGN_ONLY_RE = null;
  try {
    SIGN_ONLY_RE = new RegExp('^[\\d\\s\\p{P}\\p{S}]+$', 'u');
  } catch (e) {
    SIGN_ONLY_RE = null;
  }

  var LEAD_WS_RE = /^\s*/;
  var TRAIL_WS_RE = /\s*$/;

  var active = false;
  var gen = 0;
  var busy = true;
  var sentAny = false;

  var mo = null;
  var restWalk = null;
  var restToken = 0;

  var queue = [];
  var current = null;
  var inflight = 0;
  var sent = {};
  var batchTimers = {};
  var nextBatchId = 1;

  var cached = {};
  var known = {};
  var waiting = {};

  var flushTimer = null;
  var idleTimer = null;
  var mutationTimer = null;
  var pendingRoots = [];
  var pendingGen = 0;

  function isInsideTranslated(el) {
    return !!(el && el.closest && el.closest('span[data-miru-tr]'));
  }

  function skipNode(node) {
    var parent = node.parentElement;
    if (!parent) { return true; }
    if (parent.closest && parent.closest('script,style,noscript,code,pre,textarea,input,select,option,title,svg,head')) { return true; }
    if (parent.isContentEditable) { return true; }
    if (isInsideTranslated(parent)) { return true; }
    var text = node.nodeValue;
    if (text === null) { return true; }
    var trimmed = text.trim();
    if (trimmed.length < 2) { return true; }
    if (SIGN_ONLY_RE && SIGN_ONLY_RE.test(trimmed)) { return true; }
    return false;
  }

  // Splits at a sentence end (punctuation followed by whitespace) when one is
  // available in the second half of the chunk, otherwise at whitespace, and
  // only cuts mid-word when the text has no whitespace at all. The index is
  // always in (0, length), so splitText() can always make progress.
  function findSplitIndex(text, limit) {
    if (text.length <= limit) { return text.length; }
    var floor = Math.floor(limit / 2);
    var i;
    for (i = limit; i > floor; i--) {
      var prev = text.charAt(i - 1);
      var next = text.charAt(i);
      if ((prev === '.' || prev === '!' || prev === '?' || prev === ';' ||
           prev === '\u3002' || prev === '\uFF01' || prev === '\uFF1F' ||
           prev === '\uFF1B') && /\s/.test(next)) {
        // Split before the whitespace: the next chunk keeps it, so no
        // character of the original text is lost.
        return i;
      }
    }
    for (i = limit; i > floor; i--) {
      if (/\s/.test(text.charAt(i))) { return i; }
    }
    return limit;
  }

  function splitAndEnqueue(node) {
    var parts = [];
    var current = node;
    while (current && current.nodeValue !== null &&
           current.nodeValue.length > MAX_NODE_CHARS) {
      var length = current.nodeValue.length;
      var at = findSplitIndex(current.nodeValue, MAX_NODE_CHARS);
      if (at <= 0 || at >= length) { at = Math.floor(length / 2); }
      parts.push(current);
      current = current.splitText(at);
    }
    if (current) { parts.push(current); }
    for (var i = 0; i < parts.length; i++) {
      enqueueNode(parts[i]);
    }
  }

  function notify(state) {
    try {
      if (window.MiruTranslate && window.MiruTranslate.onState) {
        window.MiruTranslate.onState(state, gen);
      }
    } catch (e) { }
  }

  function setBusy(value) {
    if (busy === value) { return; }
    busy = value;
    notify(value ? 'working' : 'idle');
  }

  function newBatch() {
    return {
      id: nextBatchId++,
      gen: gen,
      texts: [],
      nodes: [],
      chars: 0,
      sent: false,
      inflight: false
    };
  }

  function enqueueNode(node) {
    if (!active || node.nodeType !== 3) { return; }
    if (node.__miruSeen === gen) { return; }
    if (skipNode(node)) { return; }
    // Mark the tail parts too: splitText() makes the observer and the
    // TreeWalker see them, and they must not be translated twice.
    var text = node.nodeValue;
    if (text.length > MAX_NODE_CHARS) {
      splitAndEnqueue(node);
      return;
    }
    node.__miruSeen = gen;
    if (Object.prototype.hasOwnProperty.call(cached, text)) {
      // Do not replace the node while a TreeWalker is on it: replacing the
      // current node makes walker.nextNode() return null (Chromium/DOM spec).
      // Defer cached paints until the current synchronous walk has finished.
      var cachedNode = node;
      var cachedText = text;
      var cachedGen = gen;
      setTimeout(function () {
        if (active && gen === cachedGen) {
          applyTranslation(cachedNode, cachedText, cached[cachedText]);
        }
      }, 0);
      return;
    }
    if (known[text]) {
      if (!waiting[text]) { waiting[text] = []; }
      waiting[text].push(node);
      return;
    }
    known[text] = 1;
    if (current !== null && current.texts.length > 0 &&
        (current.chars + text.length > MAX_BATCH_CHARS || current.texts.length >= MAX_BATCH_UNITS)) {
      queue.push(current);
      current = null;
    }
    if (current === null) { current = newBatch(); }
    current.texts.push(text);
    current.nodes.push({ node: node, text: text });
    current.chars += text.length;
    if (current.texts.length >= MAX_BATCH_UNITS) {
      queue.push(current);
      current = null;
    }
    scheduleFlush();
  }

  // Mutation roots are usually small; a single synchronous walk is cheapest.
  function enqueueTextTree(root) {
    if (!active || !root) { return; }
    if (root.nodeType === 3) {
      enqueueNode(root);
      return;
    }
    if (root.nodeType !== 1) { return; }
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    var node = walker.nextNode();
    while (active && node) {
      enqueueNode(node);
      node = walker.nextNode();
    }
  }

  // Chunked so a large document cannot block the UI and the first visible
  // batches can leave while the walk is still running.
  function enqueueRest(token) {
    if (!active || token !== restToken || !document.body) {
      restWalk = null;
      return;
    }
    if (restWalk === null) {
      restWalk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    }
    var budget = WALK_CHUNK;
    while (budget > 0 && active && token === restToken) {
      var node = restWalk.nextNode();
      if (!node) {
        restWalk = null;
        return;
      }
      enqueueNode(node);
      budget--;
    }
    setTimeout(function () { enqueueRest(token); }, 0);
  }

  function scheduleFlush() {
    if (flushTimer !== null) { return; }
    flushTimer = setTimeout(function () {
      flushTimer = null;
      flush();
    }, 0);
  }

  function flush() {
    if (!active) { return; }
    if (current !== null) {
      if (current.texts.length > 0) { queue.push(current); }
      current = null;
    }
    pump();
  }

  function pump() {
    if (!active) { return; }
    while (active && inflight < MAX_INFLIGHT && queue.length > 0) {
      var batch = queue.shift();
      if (batch.gen !== gen || batch.sent) { continue; }
      send(batch);
    }
    if (active && inflight === 0 && queue.length === 0 && current === null && sentAny) {
      markIdle();
    }
  }

  function settleBatch(batch) {
    if (batch.inflight) {
      batch.inflight = false;
      if (inflight > 0) { inflight--; }
    }
    var timer = batchTimers[batch.id];
    if (timer) {
      clearTimeout(timer);
      delete batchTimers[batch.id];
    }
  }

  function send(batch) {
    batch.sent = true;
    batch.inflight = true;
    inflight++;
    sent[batch.id] = batch;
    sentAny = true;
    if (idleTimer !== null) { clearTimeout(idleTimer); idleTimer = null; }
    setBusy(true);
    try {
      if (!window.MiruTranslate || !window.MiruTranslate.translateAsync) {
        abortPipeline();
        return;
      }
      window.MiruTranslate.translateAsync(batch.id, JSON.stringify(batch.texts));
    } catch (e) {
      abortPipeline();
      return;
    }
    batchTimers[batch.id] = setTimeout(function () {
      delete batchTimers[batch.id];
      if (!active || batch.gen !== gen) { return; }
      // Free the slot for the next batch. The late result still applies: the
      // batch stays in `sent` until a result or a failure settles it.
      settleBatch(batch);
      pump();
    }, BATCH_TIMEOUT_MS);
  }

  function markIdle() {
    if (idleTimer !== null) { clearTimeout(idleTimer); }
    idleTimer = setTimeout(function () {
      idleTimer = null;
      if (active && inflight === 0 && queue.length === 0 && current === null) {
        setBusy(false);
      }
    }, IDLE_SETTLE_MS);
  }

  function applyTranslation(node, originalText, translated) {
    if (!active || !node || !node.parentNode) { return; }
    if (node.nodeValue !== originalText) { return; }
    if (isInsideTranslated(node.parentElement)) { return; }
    var clean = translated.trim();
    if (clean.length === 0 || clean === originalText.trim()) { return; }
    var lead = LEAD_WS_RE.exec(originalText);
    var trail = TRAIL_WS_RE.exec(originalText);
    var span = document.createElement('span');
    span.setAttribute('data-miru-tr', String(gen));
    span.setAttribute('data-miru-orig', originalText);
    span.textContent = (lead ? lead[0] : '') + clean + (trail ? trail[0] : '');
    node.parentNode.replaceChild(span, node);
  }

  function applyResult(batchId, payload) {
    if (!active) { return; }
    var batch = sent[batchId];
    if (!batch || batch.gen !== gen) { return; }
    var result = payload;
    if (typeof result === 'string') {
      try { result = JSON.parse(result); } catch (e) { return; }
    }
    if (!result) { return; }

    // Keep the observer attached: an SPA route can replace half the page while
    // this batch is being painted, and those nodes must still be queued.
    for (var i = 0; i < batch.nodes.length; i++) {
      var entry = batch.nodes[i];
      var translated = result[entry.text];
      var nodes = waiting[entry.text] || [];
      if (typeof translated === 'string' && translated.trim().length > 0) {
        cached[entry.text] = translated;
        applyTranslation(entry.node, entry.text, translated);
        for (var j = 0; j < nodes.length; j++) {
          if (nodes[j].parentNode) { applyTranslation(nodes[j], entry.text, translated); }
        }
      }
      delete waiting[entry.text];
    }

    settleBatch(batch);
    delete sent[batchId];
    pump();
  }

  function failResult(batchId) {
    if (!active) { return; }
    var batch = sent[batchId];
    if (!batch || batch.gen !== gen) { return; }
    // Drop only this batch and free its slot. The rest of the queue and the
    // other in-flight batches keep going; only restore()/start()/deactivate()
    // abort the pipeline. Forgetting known/waiting is what lets a later
    // occurrence of the same text be sent in a fresh batch instead of hanging
    // forever behind the failed one.
    for (var i = 0; i < batch.texts.length; i++) {
      var text = batch.texts[i];
      delete waiting[text];
      delete known[text];
    }
    settleBatch(batch);
    delete sent[batchId];
    pump();
  }

  function abortPipeline() {
    active = false;
    busy = false;
    notify('idle');
    clearBatchTimers();
    inflight = 0;
    if (flushTimer !== null) { clearTimeout(flushTimer); flushTimer = null; }
    if (idleTimer !== null) { clearTimeout(idleTimer); idleTimer = null; }
    if (mutationTimer !== null) { clearTimeout(mutationTimer); mutationTimer = null; }
    if (mo) { mo.disconnect(); mo = null; }
    restToken++;
    restWalk = null;
    queue = [];
    current = null;
    pendingRoots = [];
  }

  function clearBatchTimers() {
    for (var id in batchTimers) {
      if (Object.prototype.hasOwnProperty.call(batchTimers, id)) {
        clearTimeout(batchTimers[id]);
      }
    }
    batchTimers = {};
  }

  function restore() {
    active = false;
    busy = false;
    sentAny = false;
    clearBatchTimers();
    inflight = 0;
    if (flushTimer !== null) { clearTimeout(flushTimer); flushTimer = null; }
    if (idleTimer !== null) { clearTimeout(idleTimer); idleTimer = null; }
    if (mutationTimer !== null) { clearTimeout(mutationTimer); mutationTimer = null; }
    if (mo) { mo.disconnect(); mo = null; }
    restToken++;
    restWalk = null;
    queue = [];
    current = null;
    sent = {};
    cached = {};
    known = {};
    waiting = {};
    pendingRoots = [];

    var spans = document.querySelectorAll('span[data-miru-tr]');
    for (var i = spans.length - 1; i >= 0; i--) {
      var span = spans[i];
      var parent = span.parentNode;
      if (!parent) { continue; }
      var original = span.getAttribute('data-miru-orig');
      if (original === null) { continue; }
      parent.replaceChild(document.createTextNode(original), span);
    }
  }

  function observeMutations() {
    if (!active) { return; }
    if (mo === null) { mo = new MutationObserver(onMutations); }
    mo.observe(document.documentElement, {
      childList: true,
      subtree: true,
      characterData: true
    });
  }

  function onMutations(mutations) {
    if (!active) { return; }
    var roots = [];
    for (var i = 0; i < mutations.length; i++) {
      var mutation = mutations[i];
      if (mutation.type === 'characterData') {
        var changed = mutation.target;
        if (!changed || changed.nodeType !== 3) { continue; }
        var host = changed.parentElement;
        var span = host && host.closest
          ? host.closest('span[data-miru-tr]')
          : null;
        if (span) {
          // The site wrote into a translated span: it owns the text again.
          // Drop the stale span (and its stale data-miru-orig), keep the new
          // text as the original and translate it afresh. Our own
          // applyTranslation never mutates an existing text child, so this
          // cannot feed itself.
          var fresh = document.createTextNode(span.textContent);
          var spanParent = span.parentNode;
          if (spanParent) {
            spanParent.replaceChild(fresh, span);
            fresh.__miruSeen = 0;
            roots.push(fresh);
          }
          continue;
        }
        if (!isInsideTranslated(host)) {
          // Same node, new text: allow enqueueNode to look at it again.
          changed.__miruSeen = 0;
          roots.push(changed);
        }
        continue;
      }
      if (isInsideTranslated(mutation.target)) { continue; }
      var added = mutation.addedNodes;
      for (var j = 0; j < added.length; j++) {
        var node = added[j];
        if (node.nodeType === 3) {
          if (!isInsideTranslated(node.parentElement)) { roots.push(node); }
        } else if (node.nodeType === 1) {
          if (node.getAttribute && node.getAttribute('data-miru-tr')) { continue; }
          roots.push(node);
        }
      }
    }
    if (roots.length === 0) { return; }
    pendingRoots = pendingRoots.concat(roots);
    pendingGen = gen;
    if (mutationTimer !== null) { clearTimeout(mutationTimer); }
    mutationTimer = setTimeout(flushMutations, MUTATION_DEBOUNCE_MS);
  }

  function flushMutations() {
    mutationTimer = null;
    if (!active || pendingGen !== gen) {
      pendingRoots = [];
      return;
    }
    var roots = pendingRoots;
    pendingRoots = [];
    for (var i = 0; i < roots.length; i++) {
      var root = roots[i];
      if (root.nodeType === 3) {
        if (root.parentNode) { enqueueNode(root); }
      } else if (root.parentNode) {
        enqueueTextTree(root);
      }
    }
  }

  function start(genId) {
    if (!document.body) { return; }
    if (typeof genId !== 'number' || genId <= gen) { return; }

    clearBatchTimers();
    inflight = 0;
    if (flushTimer !== null) { clearTimeout(flushTimer); flushTimer = null; }
    if (idleTimer !== null) { clearTimeout(idleTimer); idleTimer = null; }
    if (mutationTimer !== null) { clearTimeout(mutationTimer); mutationTimer = null; }

    gen = genId;
    active = true;
    busy = true;
    sentAny = false;
    queue = [];
    current = null;
    // sent is cleared, but [nextBatchId] keeps counting: a late result from an
    // old generation must never resolve a fresh batch that reused the same id.
    sent = {};
    cached = {};
    known = {};
    waiting = {};
    pendingRoots = [];
    restWalk = null;
    restToken++;

    observeMutations();
    // The chunked walk queues the whole document; no scrolling is required.
    enqueueRest(restToken);
    // A page with nothing to translate never sends a batch; settle the spinner
    // instead of leaving the toolbar busy forever.
    idleTimer = setTimeout(function () {
      idleTimer = null;
      if (active && !sentAny) { setBusy(false); }
    }, 1200);
    scheduleFlush();
  }

  window.__miruPageTranslator = {
    start: start,
    restore: restore,
    applyResult: applyResult,
    failResult: failResult
  };
})();"""
