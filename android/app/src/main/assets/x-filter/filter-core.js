/**
 * Robin x-filter core — chrome.*-free inject for Android WebView.
 *
 * Adapted from Minimal Theme for Twitter / X (MIT):
 * https://github.com/typefully/minimal-twitter
 * Copyright (c) 2022 Mailbrew Inc.
 *
 * Settings: window.__ROBIN_SETTINGS__ (set by the host before this runs)
 *   forceFollowing: boolean (default true) — true = Following, false = For You
 *   hideForYouTab: boolean (default true) — usually mirrors forceFollowing
 *   hidePromoted: boolean (default true)
 *   preferLatest: boolean (default true) — Following only (Recent vs Popular)
 *   hideWhoToFollow: boolean (default true)
 *   hideLiveContent: boolean (default true) — hide live / Spaces / broadcast promos on home
 *   hideOpenAppNags: boolean (default true)
 *   hidePageHeader: boolean (default true) — hide home category tabs (avatar/logo/Subscribe always hidden)
 *   (X compose FAB is always hidden; hosts use a native New post control)
 *   hideKeywords: string[] (default []) — whole-word / phrase hides (cat ≠ category)
 *   fontScale: number (default 1) — page zoom for text size
 * iOS host sets window.__ROBIN_TEXT_SIZE_ADJUST__: WKWebView text ignores html
 * zoom, so scale is applied via -webkit-text-size-adjust instead.
 */
(function () {
  "use strict";
  if (window.__ROBIN_FILTER_INSTALLED__) return;
  window.__ROBIN_FILTER_INSTALLED__ = true;

  // Whether the home feed X last requested is ranked. Following → Popular and
  // Following → Recent both call HomeLatestTimeline; only the enableRanking
  // variable tells them apart. HomeTimeline (For you) is always ranked.
  // null until the first home feed response. Ground truth for preferLatest.
  window.__ROBIN_HOME_OP__ = window.__ROBIN_HOME_OP__ || "";
  if (window.__ROBIN_HOME_RANKED__ === undefined) {
    window.__ROBIN_HOME_RANKED__ = null;
  }
  // Set when a home-timeline response arrives, not when the request is sent.
  // Tab / sort clicks before that abort the in-flight fetch and X can commit
  // the "Welcome to X!" empty state until the next full document load.
  var homeResponseSeen = false;
  var HOME_OP_RE = /\/graphql\/[^/]+\/(HomeLatestTimeline|HomeTimeline)\b/;
  function homeOpOf(url) {
    var m = HOME_OP_RE.exec(String(url || ""));
    return m ? m[1] : "";
  }
  function noteHomeRequest(op, url, body) {
    var vars = null;
    try {
      if (typeof body === "string" && body) vars = JSON.parse(body).variables;
    } catch (e) {}
    if (!vars) {
      try {
        var q = /[?&]variables=([^&]*)/.exec(String(url || ""));
        if (q) vars = JSON.parse(decodeURIComponent(q[1]));
      } catch (e2) {}
    }
    window.__ROBIN_HOME_OP__ = op;
    window.__ROBIN_HOME_RANKED__ =
      vars && typeof vars.enableRanking === "boolean"
        ? vars.enableRanking
        : op === "HomeTimeline";
  }

  // Original post id -> repost time (ms). The DOM only shows the original
  // post's age on reposts; the feed response carries when it was reposted.
  var repostTimes = {};
  var hasRepostTimes = false;
  function unwrapTweet(r) {
    return r && r.tweet ? r.tweet : r;
  }
  function collectRepost(itemContent) {
    var r = unwrapTweet(
      itemContent && itemContent.tweet_results && itemContent.tweet_results.result
    );
    var lg = r && r.legacy;
    var rt = unwrapTweet(
      lg && lg.retweeted_status_result && lg.retweeted_status_result.result
    );
    if (!rt || !rt.rest_id || !lg.created_at) return;
    var t = Date.parse(lg.created_at);
    if (!isNaN(t) && !(repostTimes[rt.rest_id] > t)) {
      repostTimes[rt.rest_id] = t;
      hasRepostTimes = true;
    }
  }
  function collectRepostTimes(text) {
    try {
      var ins = JSON.parse(text).data.home.home_timeline_urt.instructions || [];
      for (var i = 0; i < ins.length; i++) {
        var es = ins[i].entries || [];
        for (var k = 0; k < es.length; k++) {
          var c = es[k].content || {};
          if (c.itemContent) collectRepost(c.itemContent);
          var items = c.items || [];
          for (var n = 0; n < items.length; n++) {
            collectRepost(items[n].item && items[n].item.itemContent);
          }
        }
      }
    } catch (e) {}
  }

  try {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      var op = homeOpOf(url);
      this.__mtHomeOp = op;
      this.__mtHomeUrl = url;
      if (op) {
        var xhr = this;
        xhr.addEventListener("load", function () {
          noteHomeRequest(xhr.__mtHomeOp, xhr.__mtHomeUrl, xhr.__mtHomeBody);
          homeResponseSeen = true;
          if (typeof xhr.responseText === "string") collectRepostTimes(xhr.responseText);
        });
      }
      return origOpen.apply(this, arguments);
    };
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function (body) {
      if (this.__mtHomeOp) this.__mtHomeBody = body;
      return origSend.apply(this, arguments);
    };
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function (input, init) {
        var p = origFetch.apply(this, arguments);
        var url = typeof input === "string" ? input : input && input.url;
        var op = homeOpOf(url);
        if (op) {
          var body = init && init.body;
          p.then(function (res) {
            noteHomeRequest(op, url, body);
            homeResponseSeen = true;
            return res.clone().text().then(collectRepostTimes);
          }).catch(function () {});
        }
        return p;
      };
    }
  } catch (e) {}

  var defaults = {
    forceFollowing: true,
    hideForYouTab: true,
    hidePromoted: true,
    preferLatest: true,
    hideWhoToFollow: true,
    hideLiveContent: true,
    hideOpenAppNags: true,
    hidePageHeader: true,
    hideKeywords: [],
    fontScale: 1,
  };

  var lastRawSettings = null;
  var cachedSettings = Object.assign({}, defaults);

  function currentSettings() {
    var raw = window.__ROBIN_SETTINGS__;
    if (raw !== lastRawSettings) {
      lastRawSettings = raw;
      cachedSettings = Object.assign({}, defaults, raw || {});
    }
    return cachedSettings;
  }

  var KEYWORD_MAX = 40;
  var KEYWORD_MAX_LEN = 80;
  var keywordSource = null;
  var keywordPatterns = [];

  function escapeRegExp(s) {
    return String(s).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function compileKeyword(phrase) {
    var parts = phrase.split(/\s+/);
    var body = "";
    for (var i = 0; i < parts.length; i++) {
      if (!parts[i]) continue;
      if (body) body += "\\s+";
      body += escapeRegExp(parts[i]);
    }
    if (!body) return null;
    try {
      // Letter/digit boundaries: "cat" matches "the cat." and not "category".
      return new RegExp(
        "(?<![\\p{L}\\p{N}])(?:" + body + ")(?![\\p{L}\\p{N}])",
        "iu"
      );
    } catch (e) {
      return null;
    }
  }

  function keywordPatternsFor(settings) {
    var raw = settings && settings.hideKeywords;
    if (raw === keywordSource && keywordSource !== null) return keywordPatterns;
    keywordSource = raw || null;
    keywordPatterns = [];
    if (!raw || !raw.length) return keywordPatterns;
    var seen = {};
    var limit = Math.min(raw.length, KEYWORD_MAX);
    for (var i = 0; i < limit; i++) {
      if (typeof raw[i] !== "string") continue;
      var phrase = raw[i].replace(/\s+/g, " ").trim();
      if (!phrase || phrase.length > KEYWORD_MAX_LEN) continue;
      var key = phrase.toLowerCase();
      if (seen[key]) continue;
      seen[key] = true;
      var re = compileKeyword(phrase);
      if (re) keywordPatterns.push(re);
    }
    return keywordPatterns;
  }

  function isHomePath() {
    var p = location.pathname || "";
    return p === "/" || p === "/home" || p.indexOf("/home") === 0;
  }

  /** Track home so leave-home can undo sticky inline display:none. */
  var wasOnHome = isHomePath();

  function markRobinHide(el) {
    if (!el) return;
    el.setAttribute("data-mt-robin-hide", "1");
    el.style.setProperty("display", "none", "important");
  }

  function setImportant(el, prop, value) {
    if (!el) return;
    if (
      el.style.getPropertyValue(prop) === value &&
      el.style.getPropertyPriority(prop) === "important"
    ) {
      return;
    }
    el.style.setProperty(prop, value, "important");
  }

  /**
   * Short visible-label sample that does not lay out the subtree.
   * innerText on a timeline wrapper stringifies every mounted tweet and
   * gets slower the further down the feed you are.
   */
  function boundedText(el, maxLen) {
    if (!el) return "";
    var limit = maxLen || 120;
    var out = "";
    function walk(node, depth) {
      if (!node || out.length >= limit || depth > 8) return;
      if (node.nodeType === 3) {
        if (node.nodeValue) out += " " + node.nodeValue;
        return;
      }
      if (node.nodeType !== 1) return;
      if (node.getAttribute && node.getAttribute("data-testid") === "tweet") return;
      var kids = node.childNodes;
      for (var i = 0; i < kids.length; i++) {
        walk(kids[i], depth + 1);
        if (out.length >= limit) return;
      }
    }
    walk(el, 0);
    return out.replace(/\s+/g, " ").trim().slice(0, limit).toLowerCase();
  }

  function clearRobinHides() {
    var nodes = document.querySelectorAll("[data-mt-robin-hide]");
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      el.style.removeProperty("display");
      el.removeAttribute("data-mt-robin-hide");
    }
  }

  function tabLabel(el) {
    return (el && (el.getAttribute("aria-label") || el.textContent || "") || "")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase();
  }

  // --- Following / Latest (Minimal Twitter patterns + mobile Sort by) ---
  // Ports changeFollowingTimeline / changeLatestTweets from:
  // https://github.com/typefully/minimal-twitter (timeline.js)
  // Mobile Following is Grok-ranked; chronological needs Sort by → Recent.

  // Android security boundary: post text, author names and cards are data,
  // never evidence that a node is an application control. Keep these checks
  // in the shipped asset; the iOS copy has its own host and release lifecycle.
  var POST_CONTENT =
    "article, [data-testid='tweet'], [data-testid='tweetText'], " +
    "[data-testid='quoteTweet'], [data-testid='card.wrapper'], " +
    "[data-testid='User-Name'], [data-testid='cellInnerDiv']";
  var OVERLAY_ROOTS =
    "[role='menu'], [role='listbox'], [role='dialog'], [data-testid='Dropdown']";
  var TIMELINE_TABLIST = "div[data-testid='ScrollSnap-List'][role='tablist']";

  function outsidePostContent(el) {
    return !!(el && el.isConnected && el.closest &&
      !el.closest(POST_CONTENT) && !el.querySelector(POST_CONTENT));
  }

  function isFeedChromeControl(el) {
    return outsidePostContent(el) && !el.closest(OVERLAY_ROOTS) &&
      !!el.closest('[data-testid="primaryColumn"]');
  }

  function isTimelineTab(el) {
    if (!isFeedChromeControl(el) || el.getAttribute("role") !== "tab") return false;
    var list = el.closest(TIMELINE_TABLIST);
    if (!list || !isFeedChromeControl(list)) return false;
    // A tab may be an anchor, but must not navigate to a post-supplied route.
    var link = el.closest("a[href]");
    if (link) {
      var href = link.getAttribute("href");
      if (href !== "/home" && href !== "/home/") return false;
    }
    return true;
  }

  function getTimelineTablist() {
    var lists = document.querySelectorAll(TIMELINE_TABLIST);
    for (var i = 0; i < lists.length; i++) {
      if (isFeedChromeControl(lists[i])) return lists[i];
    }
    return null;
  }

  function isFollowingLabel(label) {
    if (!label) return false;
    var l = label.toLowerCase().trim();
    return (
      l === "following" || l === "フォロー中" || l === "seguindo" ||
      l === "abonnements" || l === "siguiendo"
    );
  }

  function isForYouLabel(label) {
    return (
      label === "for you" ||
      label === "おすすめ" ||
      label === "para ti" ||
      label === "pour vous"
    );
  }

  function tabNodeLabel(t) {
    var label = tabLabel(t);
    var shortTxt = (t.textContent || "").replace(/\s+/g, " ").trim().toLowerCase();
    if (shortTxt && shortTxt.length <= 20) label = shortTxt;
    return label;
  }

  var cachedHomeTabs = { following: null, forYou: null };

  function findHomeTabs() {
    if (
      cachedHomeTabs.following &&
      isTimelineTab(cachedHomeTabs.following) &&
      cachedHomeTabs.forYou &&
      isTimelineTab(cachedHomeTabs.forYou)
    ) {
      return cachedHomeTabs;
    }

    var following = null;
    var forYou = null;
    var tablist = getTimelineTablist();
    var nodes = tablist ? tablist.querySelectorAll("[role='tab']") : [];
    for (var i = 0; i < nodes.length; i++) {
      var t = nodes[i];
      if (!isTimelineTab(t)) continue;
      var label = tabNodeLabel(t);
      var matchFollow = !following && isFollowingLabel(label);
      var matchForYou = !forYou && isForYouLabel(label);
      if (!matchFollow && !matchForYou) continue;

      var r = t.getBoundingClientRect();
      if (r.top > 280 || r.height === 0) continue;
      if (matchFollow) following = t;
      if (matchForYou) forYou = t;
      if (following && forYou) break;
    }
    if (following && forYou) {
      cachedHomeTabs.following = following;
      cachedHomeTabs.forYou = forYou;
      return cachedHomeTabs;
    }

    cachedHomeTabs.following = following;
    cachedHomeTabs.forYou = forYou;
    return cachedHomeTabs;
  }

  function isTabSelected(el) {
    if (!el) return false;
    if (el.getAttribute("aria-selected") === "true") return true;
    var tab = el.querySelector && el.querySelector("[aria-selected='true']");
    return !!tab;
  }

  function isFollowingFeedActive() {
    if (!isHomePath()) return false;

    var tablist = getTimelineTablist();
    var selectedTabs = tablist ? tablist.querySelectorAll("[role='tab'][aria-selected='true']") : [];
    if (selectedTabs && selectedTabs.length > 0) {
      for (var i = 0; i < selectedTabs.length; i++) {
        if (!isTimelineTab(selectedTabs[i])) continue;
        var label = tabNodeLabel(selectedTabs[i]);
        if (isFollowingLabel(label)) {
          return true;
        }
      }
      return false;
    }

    var tabs = findHomeTabs();
    if (tabs.following) {
      return isTabSelected(tabs.following);
    }

    return true;
  }

  var lastFollowClick = 0;
  function forceFollowing() {
    // Port of changeFollowingTimeline (minimal-twitter).
    var settings = currentSettings();
    if (!homeResponseSeen) return;
    if (isWelcomeInterstitial()) return;
    if (!settings.forceFollowing || !isHomePath()) return;
    if (window.__ROBIN_USER_PICKED_TAB__) return;
    var now = Date.now();
    if (now - lastFollowClick < 1500) return;

    var tabs = findHomeTabs();
    if (!isTimelineTab(tabs.following) || isTabSelected(tabs.following)) return;
    // Require a verified Following control — never click a guessed nth tab.
    if (!isFollowingLabel(tabNodeLabel(tabs.following))) return;
    lastFollowClick = now;
    try {
      tabs.following.click();
    } catch (e) {}
  }

  function forceForYou() {
    var settings = currentSettings();
    if (!homeResponseSeen) return;
    if (isWelcomeInterstitial()) return;
    if (settings.forceFollowing || !isHomePath()) return;
    if (window.__ROBIN_USER_PICKED_TAB__) return;
    var now = Date.now();
    if (now - lastFollowClick < 1500) return;

    var tabs = findHomeTabs();
    if (!isTimelineTab(tabs.forYou) || isTabSelected(tabs.forYou)) return;
    if (!isForYouLabel(tabNodeLabel(tabs.forYou))) return;
    lastFollowClick = now;
    try {
      tabs.forYou.click();
    } catch (e) {}
  }

  function hideForYouTab() {
    var settings = currentSettings();
    var tabs = findHomeTabs();
    if (!tabs.forYou) return;
    if (settings.hideForYouTab) {
      tabs.forYou.style.setProperty("display", "none", "important");
    } else {
      tabs.forYou.style.removeProperty("display");
    }
  }

  function textOf(el) {
    return ((el && (el.innerText || el.textContent)) || "")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase();
  }

  var RECENT_NAMES = ["recent", "most recent", "latest", "最新", "新着", "時系列"];
  var POPULAR_NAMES = ["popular", "人気"];
  var SORT_CANCEL_NAMES = ["cancel", "close", "done", "閉じる"];
  var SORT_CHOICES =
    "[role='menuitem'], [role='menuitemradio'], [role='option'], [role='button'], button";
  var pendingSort = null;
  var lastSortClick = 0;

  function cancelPendingSort() {
    pendingSort = null;
    window.__ROBIN_SHEET_BY_US__ = false;
  }

  // Trusted input, including accessibility clicks, cancels the pending action.
  // Never select an item from a menu that the user opened in the meantime.
  ["pointerdown", "keydown", "click"].forEach(function (eventName) {
    document.addEventListener(eventName, function (event) {
      if (event.isTrusted) cancelPendingSort();
    }, true);
  });

  function findChoiceRow(root, names) {
    if (!outsidePostContent(root)) return null;
    var nodes = root.querySelectorAll(SORT_CHOICES);
    var match = null;
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (!outsidePostContent(n) || n.closest("a[href]")) continue;
      if (n.getAttribute("aria-disabled") === "true" || n.disabled) continue;
      if (names.indexOf(textOf(n)) === -1) continue;
      // Ambiguous controls fail closed, including nested clickable wrappers.
      if (match) return null;
      match = n;
    }
    return match;
  }

  function findSortSheetRoot() {
    if (!pendingSort) return null;
    if (!isHomePath() || Date.now() - pendingSort.started > 1500 ||
        !isFeedChromeControl(pendingSort.opener)) {
      cancelPendingSort();
      return null;
    }
    var layers = document.getElementById("layers");
    if (!layers) return null;
    var roots = layers.querySelectorAll(OVERLAY_ROOTS);
    for (var r = 0; r < roots.length; r++) {
      var root = roots[r];
      if (pendingSort.existing.indexOf(root) !== -1 || !outsidePostContent(root)) continue;
      // A sort popup contains only recognised sort actions, not user content,
      // profile links or some unrelated account-action menu.
      if (root.querySelector("a[href], input, textarea, [contenteditable='true']")) continue;
      var rows = root.querySelectorAll(SORT_CHOICES);
      if (rows.length < 2 || rows.length > 3) continue;
      var allowed = true;
      for (var i = 0; i < rows.length; i++) {
        var label = textOf(rows[i]);
        if (POPULAR_NAMES.indexOf(label) === -1 && RECENT_NAMES.indexOf(label) === -1 &&
            SORT_CANCEL_NAMES.indexOf(label) === -1) allowed = false;
      }
      if (allowed && findChoiceRow(root, POPULAR_NAMES) && findChoiceRow(root, RECENT_NAMES)) return root;
    }
    return null;
  }

  function openVerifiedSortControl(opener) {
    if (!isFeedChromeControl(opener) || document.querySelector(OVERLAY_ROOTS)) return false;
    var now = Date.now();
    if (now - lastSortClick < 3000) return false;
    pendingSort = {
      opener: opener,
      started: now,
      existing: Array.prototype.slice.call(document.querySelectorAll(OVERLAY_ROOTS)),
    };
    window.__ROBIN_SHEET_BY_US__ = true;
    lastSortClick = now;
    try {
      opener.click();
      return true;
    } catch (e) {
      cancelPendingSort();
      return false;
    }
  }

  function preferLatest() {
    // Port of changeLatestTweets, gated on whether the requested feed is ranked.
    var settings = currentSettings();
    if (!homeResponseSeen) return;
    if (isWelcomeInterstitial()) return;
    if (!settings.preferLatest || !isHomePath()) return;
    // Reverse-chron sort only applies to Following, not For You.
    if (!settings.forceFollowing) {
      window.__ROBIN_LATEST_OK__ = true;
      return;
    }

    var ranked = window.__ROBIN_HOME_RANKED__;
    var sheet = findSortSheetRoot();

    if (ranked === false) {
      window.__ROBIN_LATEST_OK__ = true;
      cancelPendingSort();
      return;
    }
    window.__ROBIN_LATEST_OK__ = false;
    if (ranked !== true) return; // feed not requested yet

    var tabs = findHomeTabs();
    if (tabs.following && !isTabSelected(tabs.following)) return;

    if (sheet) {
      var recent = findChoiceRow(sheet, RECENT_NAMES);
      if (recent) {
        cancelPendingSort();
        try {
          recent.click();
        } catch (e) {}
      }
      return;
    }
    if (pendingSort) return;

    // Desktop variants must open the same verified sort menu. Never select
    // an arbitrary first menu item after a timer.
    var legacy = document.querySelectorAll(
      "div[aria-label='Timeline options'], div[aria-label='Top Tweets on']"
    );
    for (var i = 0; i < legacy.length; i++) {
      if (isFeedChromeControl(legacy[i])) {
        openVerifiedSortControl(legacy[i]);
        return;
      }
    }

    // Mobile: tapping the selected Following tab (aria-haspopup) opens Sort by.
    var following = tabs.following;
    if (!isTimelineTab(following) || !isTabSelected(following)) return;
    var popupValues = ["true", "menu", "listbox", "dialog"];
    var opener = popupValues.indexOf(following.getAttribute("aria-haspopup")) !== -1 ? following : null;
    if (!opener) {
      var candidates = following.querySelectorAll("[aria-haspopup]");
      for (var c = 0; c < candidates.length; c++) {
        if (popupValues.indexOf(candidates[c].getAttribute("aria-haspopup")) !== -1) {
          opener = candidates[c];
          break;
        }
      }
    }
    if (opener) openVerifiedSortControl(opener);
  }

  function shortAge(ms) {
    var s = Math.max(0, Math.floor(ms / 1000));
    if (s < 60) return s + "s";
    if (s < 3600) return Math.floor(s / 60) + "m";
    if (s < 86400) return Math.floor(s / 3600) + "h";
    return Math.floor(s / 86400) + "d";
  }

  function labelRepostTimes(root) {
    if (!currentSettings().preferLatest || !isHomePath() || !hasRepostTimes) return;
    var scope = root && root.querySelectorAll ? root : document;
    var ctxs;
    if (
      scope !== document &&
      scope.matches &&
      scope.matches('[data-testid="socialContext"]')
    ) {
      ctxs = [scope];
    } else {
      ctxs = scope.querySelectorAll('[data-testid="socialContext"]');
    }
    var now = Date.now();
    for (var i = 0; i < ctxs.length; i++) {
      var ctx = ctxs[i];
      var art = ctx.closest("article");
      var link = art && art.querySelector('a[href*="/status/"] time');
      link = link && link.closest("a");
      var m = link && /\/status\/(\d+)/.exec(link.getAttribute("href") || "");
      var t = m && repostTimes[m[1]];
      var tag = ctx.querySelector(".mt-repost-time");
      if (!t) {
        if (tag) tag.remove();
        continue;
      }
      var label = " · " + shortAge(now - t);
      if (!tag) {
        tag = document.createElement("span");
        tag.className = "mt-repost-time";
        ctx.appendChild(tag);
      }
      if (tag.textContent !== label) tag.textContent = label;
    }
  }

  function hideOpenAppNags() {
    if (!currentSettings().hideOpenAppNags) return;
    var nodes = document.querySelectorAll(
      'a[href*="twitter://"], a[href*="apps.apple.com"], a[href*="play.google.com"][href*="com.twitter"]'
    );
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var text = (n.textContent || "").toLowerCase();
      if (
        text.indexOf("app") !== -1 ||
        text.indexOf("open") !== -1 ||
        text.indexOf("install") !== -1 ||
        text.indexOf("get") !== -1
      ) {
        var sheet = n.closest('[role="dialog"]') || n.closest("div[data-testid]");
        if (sheet) sheet.style.setProperty("display", "none", "important");
      }
    }
  }

  function hideWhoToFollowBlocks(root) {
    if (!currentSettings().hideWhoToFollow) return;
    var scope = root && root.querySelectorAll ? root : document;
    var isWide =
      scope === document ||
      scope === document.documentElement ||
      scope === document.body;
    if (!isWide) {
      if (scope.querySelector && scope.querySelector('article[data-testid="tweet"]')) {
        return;
      }
      var scopeText = (scope.textContent || "").toLowerCase();
      if (scopeText.indexOf("follow") === -1 && scopeText.indexOf("premium") === -1) {
        return;
      }
    }

    var seen = typeof WeakSet === "function" ? new WeakSet() : null;
    function consider(h) {
      if (!h || (seen && seen.has(h))) return;
      if (seen) seen.add(h);
      var t = (h.textContent || "").trim().toLowerCase();
      if (
        t === "who to follow" ||
        t === "topics to follow" ||
        t === "subscribe to premium"
      ) {
        var block =
          h.closest('[data-testid="cellInnerDiv"]') ||
          h.closest("section") ||
          h.parentElement;
        if (block) block.style.setProperty("display", "none", "important");
      }
    }
    if (scope.matches && scope.matches("h2, span")) consider(scope);
    if (isWide) {
      var heads = scope.querySelectorAll("h2, [role='heading']");
      for (var hi = 0; hi < heads.length; hi++) consider(heads[hi]);
      var spans = scope.querySelectorAll(
        "aside span, section[aria-labelledby] span, [role='region'] span"
      );
      for (var si = 0; si < spans.length; si++) consider(spans[si]);
      return;
    }
    var local = scope.querySelectorAll("h2, span");
    for (var li = 0; li < local.length; li++) consider(local[li]);
  }

  var LIVE_LINK_RE =
    /\/i\/broadcast\/|\/i\/spaces\/|\/i\/events\/|\/live(?:\/|$|\?)/i;

  function testIdLooksLive(tid) {
    if (!tid) return false;
    var t = tid.toLowerCase();
    return (
      t.indexOf("spacecard") !== -1 ||
      t.indexOf("spacelive") !== -1 ||
      t === "space" ||
      t.indexOf("broadcast") !== -1 ||
      t.indexOf("liveevent") !== -1
    );
  }

  function cellHasNormalTweetText(cell) {
    return !!cell.querySelector('[data-testid="tweetText"]');
  }

  function cellLooksLikeLivePromo(cell) {
    if (!cell) return false;
    var hasTweetText = cellHasNormalTweetText(cell);
    if (hasTweetText && cell.querySelector && cell.querySelector('article[data-testid="tweet"]')) {
      return false;
    }
    var cellTxt = (cell.textContent || "").toLowerCase();
    if (
      cellTxt.indexOf("live") === -1 &&
      cellTxt.indexOf("space") === -1 &&
      cellTxt.indexOf("broadcast") === -1 &&
      cellTxt.indexOf("event") === -1
    ) {
      return false;
    }
    var links = cell.querySelectorAll("a[href]");
    var hasLiveLink = false;
    for (var li = 0; li < links.length; li++) {
      var href = links[li].getAttribute("href") || "";
      if (LIVE_LINK_RE.test(href)) {
        hasLiveLink = true;
        break;
      }
    }
    var liveTestId = false;
    var tidNodes = cell.querySelectorAll("[data-testid]");
    for (var ti = 0; ti < tidNodes.length; ti++) {
      if (testIdLooksLive(tidNodes[ti].getAttribute("data-testid"))) {
        liveTestId = true;
        break;
      }
    }
    if (hasLiveLink || liveTestId) {
      if (!cellHasNormalTweetText(cell)) return true;
      if (liveTestId && !cell.querySelector('article[data-testid="tweet"]')) {
        return true;
      }
    }
    if (cellHasNormalTweetText(cell)) return false;
    if (
      cell.querySelector('[data-testid="liveBadge"], [data-testid="LiveBadge"]')
    ) {
      return true;
    }
    var labelled = cell.querySelectorAll("[aria-label]");
    for (var ai = 0; ai < labelled.length; ai++) {
      var al = (labelled[ai].getAttribute("aria-label") || "").toLowerCase();
      if (al.indexOf("live") !== -1 && al.indexOf("live photo") === -1) return true;
    }
    // textContent avoids the layout innerText forces on every cell.
    var compact = (cell.textContent || "").replace(/\s+/g, " ").trim();
    if (compact.length > 280) return false;
    if (/\blive\b/i.test(compact) && (hasLiveLink || liveTestId)) return true;
    if (
      /\+\d[\d,.]*\s*[·•]\s*.+/i.test(compact) &&
      /\b(live|space|broadcast|event|listening)\b/i.test(compact)
    ) {
      return true;
    }
    return false;
  }

  function hideLiveChipNodes(root) {
    var scope = root || document;
    var chips = scope.querySelectorAll(
      [
        '[data-testid="SpaceCard"]',
        '[data-testid="spaceLive"]',
        '[data-testid="space"]',
        '[data-testid="liveBadge"]',
        '[data-testid="LiveBadge"]',
        'a[href*="/i/broadcast/"]',
        'a[href*="/i/spaces/"]',
        'a[href*="/i/events/"]',
        'a[href*="/live"]',
      ].join(",")
    );
    for (var i = 0; i < chips.length; i++) {
      var el = chips[i];
      // Prefer the compact card/chip wrapper over a raw status link ancestor.
      var wrap =
        el.closest('[data-testid="card.wrapper"]') ||
        el.closest('[data-testid="card.layoutLarge.media"]') ||
        el.closest('[data-testid="card.layoutSmall.media"]') ||
        el;
      // Skip ordinary in-status permalinks that only mention /live in query noise.
      if (wrap.tagName === "A") {
        var href = wrap.getAttribute("href") || "";
        if (!LIVE_LINK_RE.test(href)) continue;
      }
      markRobinHide(wrap);
    }
  }

  function hideLiveContentBlocks(root) {
    if (!currentSettings().hideLiveContent || !isHomePath()) return;
    var scope = root && root.querySelectorAll ? root : document;
    // Embedded magenta “+N · Event” chips inside tweets.
    hideLiveChipNodes(scope);
    // Dedicated live/broadcast timeline modules (no normal tweet body).
    var cells;
    if (scope.getAttribute && scope.getAttribute("data-testid") === "cellInnerDiv") {
      cells = [scope];
    } else {
      cells = scope.querySelectorAll('[data-testid="cellInnerDiv"]');
    }
    for (var i = 0; i < cells.length; i++) {
      if (cells[i].getAttribute("data-mt-robin-hide") === "1") continue;
      if (cellLooksLikeLivePromo(cells[i])) markRobinHide(cells[i]);
    }
  }

  function focalStatusId() {
    var m = /\/status\/(\d+)/.exec(location.pathname || "");
    return m ? m[1] : "";
  }

  function unmarkKeywordHide(el) {
    if (!el || el.getAttribute("data-mt-keyword-hide") !== "1") return;
    el.removeAttribute("data-mt-keyword-hide");
    if (el.getAttribute("data-mt-robin-hide") === "1") return;
    el.style.removeProperty("display");
  }

  function markKeywordHide(el) {
    if (!el) return;
    el.setAttribute("data-mt-keyword-hide", "1");
    el.style.setProperty("display", "none", "important");
  }

  function cellTweetBlob(cell) {
    if (!cell || !cell.querySelectorAll) return "";
    var nodes = cell.querySelectorAll('[data-testid="tweetText"]');
    if (!nodes.length) return "";
    var out = "";
    for (var i = 0; i < nodes.length; i++) {
      out += "\n" + (nodes[i].textContent || "");
    }
    return out;
  }

  function cellIsFocalStatus(cell) {
    var focal = focalStatusId();
    if (!focal || !cell || !cell.querySelector) return false;
    var article =
      cell.matches && cell.matches('article[data-testid="tweet"]')
        ? cell
        : cell.querySelector('article[data-testid="tweet"]');
    if (!article) return false;
    return statusIdFromArticle(article) === focal;
  }

  function textHitsKeyword(text, patterns) {
    for (var i = 0; i < patterns.length; i++) {
      var re = patterns[i];
      re.lastIndex = 0;
      try {
        if (re.test(text)) return true;
      } catch (e) {}
    }
    return false;
  }

  function hideKeywordPosts(root) {
    var patterns = keywordPatternsFor(currentSettings());
    var scope = root && root.querySelectorAll ? root : document;
    var cells;
    if (scope.getAttribute && scope.getAttribute("data-testid") === "cellInnerDiv") {
      cells = [scope];
    } else {
      cells = scope.querySelectorAll('[data-testid="cellInnerDiv"]');
    }
    if (!patterns.length) {
      if (scope.getAttribute && scope.getAttribute("data-mt-keyword-hide") === "1") {
        unmarkKeywordHide(scope);
      }
      var hidden = scope.querySelectorAll
        ? scope.querySelectorAll("[data-mt-keyword-hide]")
        : [];
      for (var h = 0; h < hidden.length; h++) unmarkKeywordHide(hidden[h]);
      return;
    }
    for (var i = 0; i < cells.length; i++) {
      var cell = cells[i];
      if (cellIsFocalStatus(cell)) {
        unmarkKeywordHide(cell);
        continue;
      }
      var text = cellTweetBlob(cell);
      if (text && textHitsKeyword(text, patterns)) markKeywordHide(cell);
      else unmarkKeywordHide(cell);
    }
  }

  function syncPageHeaderFlags() {
    var settings = currentSettings();
    var root = document.documentElement;
    if (!root) return;
    var home = isHomePath();
    // SPA leave-home: undo sticky inline hides so status / back-to-feed stay visible.
    if (wasOnHome && !home) clearRobinHides();
    wasOnHome = home;
    if (home) root.setAttribute("data-mt-home", "1");
    else root.removeAttribute("data-mt-home");
    root.classList.toggle("mt-hide-page-header", !!settings.hidePageHeader);
    root.classList.add("mt-hide-compose");
    root.classList.toggle("mt-hide-live", !!settings.hideLiveContent);
    // Show Feed Tab: restore category tabs only. TopNavBar / banner stay hidden.
    if (!settings.hidePageHeader) {
      var headerHides = document.querySelectorAll(
        '[data-testid="ScrollSnap-List"][data-mt-robin-hide], [role="tablist"][data-mt-robin-hide]'
      );
      for (var hi = 0; hi < headerHides.length; hi++) {
        var hEl = headerHides[hi];
        while (hEl && hEl !== root) {
          if (hEl.getAttribute && hEl.getAttribute("data-mt-robin-hide") === "1") {
            hEl.style.removeProperty("display");
            hEl.removeAttribute("data-mt-robin-hide");
          }
          hEl = hEl.parentElement;
        }
      }
    }
    // Keep TopNavBar visible until X is confirmed loading HomeLatestTimeline.
    var deferChrome =
      home &&
      !!settings.forceFollowing &&
      !!settings.preferLatest &&
      !window.__ROBIN_LATEST_OK__;
    if (deferChrome) root.setAttribute("data-mt-defer-feed-chrome", "1");
    else root.removeAttribute("data-mt-defer-feed-chrome");
  }

  function hideComposeButtonFallback() {
    var selectors = [
      'a[data-testid="SideNav_NewTweet_Button"]',
      'a[href="/compose/post"]',
      'a[href="/compose/tweet"]',
      'a[href="/compose/post/"]',
    ];
    for (var s = 0; s < selectors.length; s++) {
      var nodes = document.querySelectorAll(selectors[s]);
      for (var i = 0; i < nodes.length; i++) {
        markRobinHide(nodes[i]);
      }
    }
    // Mobile FAB: fixed/absolute round button near bottom-right linking to compose
    var links = document.querySelectorAll('a[href*="/compose/"]');
    for (var li = 0; li < links.length; li++) {
      var a = links[li];
      if (a.getAttribute("data-mt-robin-hide") === "1") continue;
      var st = window.getComputedStyle(a);
      if (st.position !== "fixed" && st.position !== "absolute") continue;
      var r = a.getBoundingClientRect();
      if (r.width >= 40 && r.width <= 72 && r.height >= 40 && r.height <= 72 && r.bottom > window.innerHeight - 120) {
        markRobinHide(a);
      }
    }
  }

  function openComposeModal() {
    var selectors = [
      'a[data-testid="FloatingActionButton_compose"]',
      'a[data-testid="SideNav_NewTweet_Button"]',
    ];
    for (var i = 0; i < selectors.length; i++) {
      var controls = document.querySelectorAll(selectors[i]);
      for (var j = 0; j < controls.length; j++) {
        var el = controls[j];
        if (!outsidePostContent(el) || el.closest(OVERLAY_ROOTS)) continue;
        var href = el.getAttribute("href");
        if (href !== "/compose/post" && href !== "/compose/tweet" && href !== "/compose/post/") continue;
        el.click();
        return true;
      }
    }
    // A post may link to a prefilled composer. Native New post must use a
    // fixed route if the actual application control cannot be identified.
    if (!document.body) return false;
    var a = document.createElement("a");
    a.href = "/compose/post";
    a.style.display = "none";
    document.body.appendChild(a);
    a.click();
    a.remove();
    return true;
  }
  window.__ROBIN_OPEN_COMPOSE__ = openComposeModal;

  var chromePassHref = "";
  var TOP_CHROME_TABLIST =
    '[data-testid="ScrollSnap-List"], [data-testid="ScrollSnap-SwipeableList"], [role="tablist"]';

  function rootContainsTablist(root) {
    return !!(root && root.querySelector && root.querySelector(TOP_CHROME_TABLIST));
  }

  function unhideRobin(el) {
    if (!el || el.getAttribute("data-mt-robin-hide") !== "1") return;
    el.style.removeProperty("display");
    el.removeAttribute("data-mt-robin-hide");
  }

  /**
   * Hide avatar / logo / Subscribe. The tab strip mounts inside this node
   * after first paint — never display:none the whole node once it does.
   * Peel off sibling branches instead so the strip stays.
   */
  function hideTopChromeRoot(root) {
    if (!root || (root.querySelector && root.querySelector('article[data-testid="tweet"]'))) {
      return;
    }
    var tablist = root.querySelector(TOP_CHROME_TABLIST);
    if (!tablist) {
      markRobinHide(root);
      return;
    }
    unhideRobin(root);
    var node = tablist;
    while (node && node !== root) {
      var parent = node.parentElement;
      if (!parent) break;
      var sibs = parent.children;
      for (var i = 0; i < sibs.length; i++) {
        var sib = sibs[i];
        if (sib === node) continue;
        if (sib.contains && sib.contains(tablist)) continue;
        if (sib.querySelector && sib.querySelector('article[data-testid="tweet"]')) continue;
        markRobinHide(sib);
      }
      node = parent;
    }
  }

  function topChromeSettled() {
    var roots = document.querySelectorAll(
      '[data-testid="TopNavBar"], header[role="banner"]'
    );
    if (!roots.length) return true;
    for (var i = 0; i < roots.length; i++) {
      var root = roots[i];
      if (root.querySelector && root.querySelector('article[data-testid="tweet"]')) continue;
      if (rootContainsTablist(root)) {
        if (root.getAttribute("data-mt-robin-hide") === "1") return false;
        var tablist = root.querySelector(TOP_CHROME_TABLIST);
        var node = tablist;
        while (node && node !== root) {
          var parent = node.parentElement;
          if (!parent) break;
          var sibs = parent.children;
          for (var s = 0; s < sibs.length; s++) {
            var sib = sibs[s];
            if (sib === node) continue;
            if (sib.getAttribute("data-mt-robin-hide") === "1") continue;
            if (sib.contains && sib.contains(tablist)) continue;
            var r = sib.getBoundingClientRect();
            if (r.height > 8 && r.width > 8) return false;
          }
          node = parent;
        }
      } else if (root.getAttribute("data-mt-robin-hide") !== "1" && root.offsetHeight > 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * After the avatar row is removed, X's sticky tab strip can sit out of flow
   * and cover the first post. Pad the timeline once so posts start below it.
   */
  function offsetTimelineBelowTabs() {
    var tabs = document.querySelector(TOP_CHROME_TABLIST);
    var tweet = document.querySelector('article[data-testid="tweet"]');
    if (!tabs || !tweet || tabs.contains(tweet)) return;
    var tr = tabs.getBoundingClientRect();
    var wr = tweet.getBoundingClientRect();
    if (tr.height < 8 || wr.top < -2 || wr.top >= tr.bottom - 2) return;
    var overlap = Math.round(tr.bottom - wr.top);
    if (overlap < 8 || overlap > 160) return;
    var node = tweet;
    var target = null;
    while (node && node !== document.body) {
      if (node.contains(tabs)) break;
      target = node;
      node = node.parentElement;
    }
    if (!target) return;
    var already = parseInt(target.getAttribute("data-mt-tab-pad") || "0", 10);
    if (isNaN(already)) already = 0;
    var next = already + overlap;
    target.setAttribute("data-mt-tab-pad", String(next));
    target.style.setProperty("padding-top", next + "px", "important");
  }

  function hidePageHeaderFallback() {
    if (!isHomePath()) return;

    var side = document.querySelector('[data-testid="sidebarColumn"]');
    if (side) markRobinHide(side);

    // Desktop / Mac: Following switcher + inline composer at top of feed
    var col = document.querySelector('[data-testid="primaryColumn"]');
    if (col) {
      setImportant(col, "max-width", "min(100%, 840px)");
      setImportant(col, "width", "100%");
      setImportant(col, "margin-left", "auto");
      setImportant(col, "margin-right", "auto");

      var kids = col.children;
      for (var ci = 0; ci < Math.min(kids.length, 3); ci++) {
        setImportant(kids[ci], "max-width", "none");
        setImportant(kids[ci], "width", "100%");
      }
      hideInlineHomeComposer(col);
    }

    var hideTabs = !!currentSettings().hidePageHeader;
    var deferFeedChrome =
      !!currentSettings().forceFollowing &&
      !!currentSettings().preferLatest &&
      !window.__ROBIN_LATEST_OK__;

    // Steady-state scrolling used to re-run the layout walk below on every
    // mutation. Once chrome is hidden for this URL, a couple of selectors
    // are enough to notice a remount.
    if (!deferFeedChrome && chromePassHref === location.href) {
      var composer = document.querySelector(
        '[data-testid="tweetTextarea_0"], [data-testid^="tweetTextarea_"]'
      );
      var composerOk =
        !composer ||
        (composer.closest && composer.closest("[data-mt-robin-hide]"));
      if (topChromeSettled() && composerOk) {
        if (!hideTabs) {
          hideInlineHomeComposer(col);
          offsetTimelineBelowTabs();
          return;
        }
        var tablistQuick = col && col.querySelector(
          '[data-testid="ScrollSnap-List"], [data-testid="ScrollSnap-SwipeableList"], [role="tablist"]'
        );
        var tabsOk =
          !tablistQuick || tablistQuick.getAttribute("data-mt-robin-hide") === "1" ||
          (tablistQuick.closest && tablistQuick.closest("[data-mt-robin-hide]"));
        if (tabsOk) return;
      }
    }

    // Avatar / logo / Subscribe. Skip while Latest still needs that control.
    // Do not blank a container that holds the category tab strip.
    if (!deferFeedChrome) {
      var bar = document.querySelector('[data-testid="TopNavBar"]');
      if (bar) hideTopChromeRoot(bar);
      var banners = document.querySelectorAll('header[role="banner"]');
      for (var bi = 0; bi < banners.length; bi++) {
        if (banners[bi] === bar) continue;
        hideTopChromeRoot(banners[bi]);
      }
    }

    if (!col) return;

    var kids = col.children;
    for (var ci = 0; ci < Math.min(kids.length, 3); ci++) {
      setImportant(kids[ci], "max-width", "none");
      setImportant(kids[ci], "width", "100%");
    }

    // Category / Following tabs: only when "Show Feed Tab" is off.
    if (hideTabs) {
      for (var ti = 0; ti < Math.min(kids.length, 8); ti++) {
        var block = kids[ti];
        if (block.getAttribute("data-mt-robin-hide") === "1") continue;
        var br = block.getBoundingClientRect();
        if (br.height === 0) continue;
        if (br.top > 240) break;
        var bt = boundedText(block, 120);
        var hasCompose = !!block.querySelector(
          '[data-testid="tweetTextarea_0"], [data-testid^="tweetTextarea_"], [data-testid="toolBar"], [data-testid="tweetButtonInline"]'
        );
        var hasFeedSwitch =
          bt.indexOf("following") !== -1 ||
          bt.indexOf("for you") !== -1 ||
          !!block.querySelector(
            '[role="tablist"], [role="tab"], [data-testid="ScrollSnap-List"]'
          );
        if (hasCompose && br.height < 480) {
          // Don't hide a top block that also owns the timeline.
          if (!block.querySelector('article[data-testid="tweet"]')) {
            markRobinHide(block);
          }
          continue;
        }
        if (hasFeedSwitch && br.height < 100 && !deferFeedChrome) {
          markRobinHide(block);
        }
      }

      if (!deferFeedChrome) {
        // Walk up from the tablist only. querySelectorAll("div") + getComputedStyle
        // on the whole column was the multi-second hitch deep in the feed.
        var tablist = col.querySelector(
          '[data-testid="ScrollSnap-List"], [data-testid="ScrollSnap-SwipeableList"], [role="tablist"]'
        );
        var el = tablist;
        for (var depth = 0; el && el !== col && depth < 8; depth++) {
          if (el.getAttribute("data-mt-robin-hide") === "1") {
            el = el.parentElement;
            continue;
          }
          var st = window.getComputedStyle(el);
          if (
            (st.position === "sticky" || st.position === "fixed") &&
            !el.querySelector('article[data-testid="tweet"]')
          ) {
            markRobinHide(el);
          }
          el = el.parentElement;
        }
      }
    } else {
      // Still hide the inline composer block when tabs are shown.
      for (var ci2 = 0; ci2 < Math.min(kids.length, 8); ci2++) {
        var block2 = kids[ci2];
        if (block2.getAttribute("data-mt-robin-hide") === "1") continue;
        var br2 = block2.getBoundingClientRect();
        if (br2.height === 0) continue;
        if (br2.top > 240) break;
        var hasCompose2 = !!block2.querySelector(
          '[data-testid="tweetTextarea_0"], [data-testid^="tweetTextarea_"], [data-testid="toolBar"], [data-testid="tweetButtonInline"]'
        );
        if (hasCompose2 && br2.height < 480) {
          if (!block2.querySelector('article[data-testid="tweet"]')) {
            markRobinHide(block2);
          }
        }
      }
    }

    hideInlineHomeComposer(col);
    if (!hideTabs && !deferFeedChrome) offsetTimelineBelowTabs();
    if (!deferFeedChrome) chromePassHref = location.href;
  }

  /** Hide the home “What’s happening?” draft box; never touch reply composers. */
  function hideInlineHomeComposer(col) {
    if (!col) return;
    var hooks = col.querySelectorAll(
      '[data-testid="tweetTextarea_0"], [data-testid^="tweetTextarea_"], [data-testid="toolBar"], [data-testid="tweetButtonInline"], div[role="textbox"][contenteditable="true"]'
    );
    if (hooks.length === 0) return;
    var hidden = {};
    for (var hi = 0; hi < hooks.length; hi++) {
      var hook = hooks[hi];
      if (
        hook.closest &&
        (hook.closest('article[data-testid="tweet"]') ||
          hook.closest('[data-mt-robin-hide="1"]'))
      ) {
        continue;
      }
      var hr = hook.getBoundingClientRect();
      // Top-of-feed composer only (reply boxes sit lower in the column).
      if (hr.top > 420) continue;
      var p = hook;
      var best = null;
      for (var k = 0; k < 16 && p && p !== col; k++) {
        // Never hide a wrapper that also contains the timeline.
        if (p.querySelector && p.querySelector('article[data-testid="tweet"]')) {
          break;
        }
        var r = p.getBoundingClientRect();
        if (r.height >= 48 && r.height <= 520 && r.top < 480) {
          var t = boundedText(p, 200);
          var looksCompose =
            t.indexOf("what") !== -1 ||
            t.indexOf("happening") !== -1 ||
            !!p.querySelector(
              '[data-testid="toolBar"], [data-testid="tweetButtonInline"], [data-testid="fileInput"]'
            );
          if (looksCompose) best = p;
        }
        p = p.parentElement;
      }
      if (!best) {
        // Fallback: hide a mid-sized ancestor near the top even without copy match.
        p = hook;
        for (var k2 = 0; k2 < 10 && p && p !== col; k2++) {
          if (p.querySelector && p.querySelector('article[data-testid="tweet"]')) {
            break;
          }
          var r2 = p.getBoundingClientRect();
          if (r2.height >= 72 && r2.height <= 420 && r2.top < 360) {
            best = p;
            break;
          }
          p = p.parentElement;
        }
      }
      if (best && !hidden[best]) {
        // Final guard: do not blank the feed column.
        if (best.querySelector && best.querySelector('article[data-testid="tweet"]')) {
          continue;
        }
        hidden[best] = true;
        markRobinHide(best);
      }
    }

    // Trends module label (sidebar or misplaced in main on wide layouts)
    var regions = document.querySelectorAll(
      '[aria-label="What\'s happening"], [aria-label="What’s happening"], [aria-label="Timeline: Trending now"]'
    );
    for (var ri = 0; ri < regions.length; ri++) {
      var region = regions[ri];
      if (region.querySelector && region.querySelector('article[data-testid="tweet"]')) {
        continue;
      }
      markRobinHide(region);
    }
  }

  function applyFontScale() {
    var scale = currentSettings().fontScale;
    if (typeof scale !== "number" || !(scale > 0)) scale = 1;
    var root = document.documentElement;
    if (!root) return;
    if (window.__ROBIN_TEXT_SIZE_ADJUST__) {
      var pct = Math.round(scale * 100) + "%";
      if (root.style.getPropertyValue("-webkit-text-size-adjust") !== pct) {
        root.style.setProperty("-webkit-text-size-adjust", pct, "important");
      }
      if (root.style.zoom) root.style.removeProperty("zoom");
      if (window.__ROBIN_SCALE_LINE_HEIGHT__) scaleFixedLineHeights(scale);
      return;
    }
    // zoom:1 still puts the document on Android WebView's zoom path, which
    // rasterizes slower as the feed gets taller. Only zoom when asked.
    if (scale === 1) {
      if (root.style.zoom) root.style.removeProperty("zoom");
      return;
    }
    var zoom = String(scale);
    if (root.style.zoom !== zoom) root.style.zoom = zoom;
  }

  window.__ROBIN_APPLY_FONT_SCALE__ = applyFontScale;

  // Mac WebKit's text-size-adjust grows fonts but not fixed px line-heights,
  // so mirror X's `line-height: Npx` rules scaled by the same factor.
  var lhState = { scale: 1, ruleCount: -1 };
  function scaleFixedLineHeights(scale) {
    var sheets = document.styleSheets;
    var count = 0;
    for (var i = 0; i < sheets.length; i++) {
      if (sheets[i].ownerNode && sheets[i].ownerNode.id === "robin-lh-scale") continue;
      try {
        count += sheets[i].cssRules.length;
      } catch (e) {}
    }
    if (count === lhState.ruleCount && scale === lhState.scale) return;
    lhState.ruleCount = count;
    lhState.scale = scale;

    var style = document.getElementById("robin-lh-scale");
    if (!style) {
      style = document.createElement("style");
      style.id = "robin-lh-scale";
      (document.head || document.documentElement).appendChild(style);
    }
    if (scale === 1) {
      style.textContent = "";
      return;
    }
    var out = [];
    for (var s = 0; s < sheets.length; s++) {
      if (sheets[s].ownerNode === style) continue;
      var rules;
      try {
        rules = sheets[s].cssRules;
      } catch (e) {
        continue;
      }
      for (var r = 0; r < rules.length; r++) {
        var rule = rules[r];
        if (!rule.selectorText || !rule.style) continue;
        var m = /^([\d.]+)px$/.exec(rule.style.lineHeight || "");
        if (!m) continue;
        var px = Math.round(parseFloat(m[1]) * scale * 10) / 10;
        out.push(rule.selectorText + "{line-height:" + px + "px !important}");
      }
    }
    style.textContent = out.join("\n");
  }

  function onUserTabClick(ev) {
    if (!ev.isTrusted) return;
    var t = ev.target && ev.target.closest && ev.target.closest('[role="tab"]');
    if (!isTimelineTab(t) || !isHomePath()) return;
    window.__ROBIN_USER_PICKED_TAB__ = Date.now();
    var label = tabNodeLabel(t);
    if (!isFollowingLabel(label)) {
      removeCaughtUpMarker();
    }
  }

  // Android deliberately leaves text-labelled "new posts" controls alone.
  // Author names, card titles and links can contain those same words. Native
  // refresh already loads the fixed /home route under its normal idle gates.
  window.__ROBIN_SOFT_REFRESH_FEED__ = function () {
    return false;
  };

  // --- Boot gate: hide until Following / Latest / chrome work settles ---
  var bootStart = Date.now();
  var bootLatestOkAt = 0;
  var bootReadyAt = 0;
  var bootRevealed = false;
  var bootNonce = String(Date.now()) + Math.random().toString(36).slice(2);
  window.__ROBIN_BOOT_NONCE__ = bootNonce;
  var BOOT_HARD_MS = 5000;
  var BOOT_AFTER_LATEST_MS = 1200;
  var BOOT_FOLLOWING_SETTLE_MS = 800;

  if (typeof performance !== "undefined" && performance.mark) {
    try {
      performance.mark("robin:boot:start");
    } catch (e) {}
  }

  // --- DOM Diagnostic & Sentry Telemetry Bridge ---
  var reportedSentryKeys = {};
  function reportToSentry(message, level, extra) {
    try {
      var dedupeKey = message + ":" + (extra && extra.tag ? extra.tag : "");
      if (reportedSentryKeys[dedupeKey]) return;
      reportedSentryKeys[dedupeKey] = true;

      var payload = {
        message: message,
        level: level || "warning",
        extra: Object.assign(
          {
            url: location.href,
            pathname: location.pathname,
            bootElapsedMs: Date.now() - (bootStart || 0),
            hasFeedContent: typeof hasFeedContent === "function" ? hasFeedContent() : false,
            hasTweets: !!document.querySelector('article[data-testid="tweet"]'),
          },
          extra || {}
        ),
      };

      if (
        window.webkit &&
        window.webkit.messageHandlers &&
        window.webkit.messageHandlers.robinSentry
      ) {
        window.webkit.messageHandlers.robinSentry.postMessage(payload);
      }
      if (window.RobinSentry && window.RobinSentry.postMessage) {
        window.RobinSentry.postMessage(JSON.stringify(payload));
      }
    } catch (e) {}
  }

  function notifyBootReady() {
    try {
      if (
        window.webkit &&
        window.webkit.messageHandlers &&
        window.webkit.messageHandlers.mtBoot
      ) {
        window.webkit.messageHandlers.mtBoot.postMessage(bootNonce);
      }
    } catch (e) {}
    try {
      if (window.RobinBoot && window.RobinBoot.ready) {
        window.RobinBoot.ready(bootNonce);
      }
    } catch (e2) {}
  }

  function notifyWelcomeRetry() {
    try {
      if (
        window.webkit &&
        window.webkit.messageHandlers &&
        window.webkit.messageHandlers.mtRetry
      ) {
        window.webkit.messageHandlers.mtRetry.postMessage(bootNonce);
      }
    } catch (e) {}
    try {
      if (window.RobinBoot && window.RobinBoot.retry) {
        window.RobinBoot.retry(bootNonce);
      }
    } catch (e2) {}
  }

  function revealBoot() {
    if (bootRevealed) return;
    bootRevealed = true;
    bootReadyAt = Date.now();
    var root = document.documentElement;
    if (root) root.setAttribute("data-mt-boot-ready", "1");
    if (typeof performance !== "undefined" && performance.mark) {
      try {
        performance.mark("robin:boot:ready");
        performance.measure(
          "robin:boot",
          "robin:boot:start",
          "robin:boot:ready"
        );
      } catch (e) {}
    }
    notifyBootReady();
  }

  function hasLoginCta() {
    return !!(
      document.querySelector('[data-testid="loginButton"]') ||
      document.querySelector('a[href*="/i/flow/login"]') ||
      document.querySelector('a[href*="/i/flow/signup"]')
    );
  }

  /**
   * Logged-in empty home X paints after some cache-bypassing /home loads:
   * "Welcome to X!" / "Let's go!". A second document load returns the timeline.
   * Other empty states (protected account, errors) are not this interstitial.
   */
  function isWelcomeInterstitial() {
    if (!isHomePath()) return false;
    if (document.querySelector('article[data-testid="tweet"]')) return false;
    var nodes = document.querySelectorAll(
      '[data-testid="emptyState"], [data-testid="empty_state_header_text"], [data-testid="empty_state_body_text"]'
    );
    for (var i = 0; i < nodes.length; i++) {
      var t = (nodes[i].textContent || "").replace(/\s+/g, " ").toLowerCase();
      if (t.indexOf("welcome to x") !== -1 || t.indexOf("welcome to twitter") !== -1) {
        return true;
      }
      if (t.indexOf("people and topics to follow") !== -1) return true;
    }
    var heads = document.querySelectorAll("h1, h2, [role='heading']");
    for (var h = 0; h < heads.length && h < 8; h++) {
      var ht = (heads[h].textContent || "").replace(/\s+/g, " ").trim().toLowerCase();
      if (
        ht === "welcome to x!" ||
        ht === "welcome to x" ||
        ht === "welcome to twitter!" ||
        ht === "welcome to twitter"
      ) {
        return true;
      }
    }
    return false;
  }

  var welcomeSeenAt = 0;
  var welcomeRetrySent = false;
  var WELCOME_RETRY_AFTER_MS = 1500;

  function maybeRetryWelcome() {
    if (welcomeRetrySent || !isHomePath()) return;
    if (!isWelcomeInterstitial()) {
      welcomeSeenAt = 0;
      return;
    }
    if (!welcomeSeenAt) welcomeSeenAt = Date.now();
    if (Date.now() - welcomeSeenAt < WELCOME_RETRY_AFTER_MS) return;
    welcomeRetrySent = true;
    notifyWelcomeRetry();
  }

  function hasFeedContent() {
    if (document.querySelector('article[data-testid="tweet"]')) return true;
    if (document.querySelector('[data-testid="error-detail"]')) return true;
    if (isWelcomeInterstitial()) return false;
    return !!document.querySelector('[data-testid="emptyState"]');
  }

  function isHomeTabSettled() {
    var settings = currentSettings();
    var tabs = findHomeTabs();
    if (settings.forceFollowing) {
      if (!tabs.following) {
        return Date.now() - bootStart >= BOOT_FOLLOWING_SETTLE_MS;
      }
      return isTabSelected(tabs.following);
    }
    if (!tabs.forYou) {
      return Date.now() - bootStart >= BOOT_FOLLOWING_SETTLE_MS;
    }
    return isTabSelected(tabs.forYou);
  }

  function isBootReady() {
    var elapsed = Date.now() - bootStart;
    // Hard timeout still reveals a real empty account. A welcome retry's
    // stale mtBoot is ignored by the host via bootNonce.
    if (elapsed >= BOOT_HARD_MS) {
      if (
        isHomePath() &&
        !hasFeedContent() &&
        !hasLoginCta() &&
        !isWelcomeInterstitial()
      ) {
        var tabs = typeof findHomeTabs === "function" ? findHomeTabs() : {};
        reportToSentry(
          "X DOM: Home timeline failed to settle feed content within boot timeout",
          "warning",
          {
            tag: "boot_timeout_empty_feed",
            tabsFound: !!(tabs.following || tabs.forYou),
          }
        );
      }
      return true;
    }
    if (welcomeRetrySent || isWelcomeInterstitial()) return false;
    if (!isHomePath()) return true;
    if (hasLoginCta()) return true;

    var settings = currentSettings();
    if (window.__ROBIN_SHEET_BY_US__ && findSortSheetRoot()) return false;

    if (
      settings.forceFollowing &&
      settings.preferLatest &&
      !window.__ROBIN_LATEST_OK__
    ) {
      return false;
    }
    if (window.__ROBIN_LATEST_OK__ && !bootLatestOkAt) {
      bootLatestOkAt = Date.now();
    }

    if (!isHomeTabSettled()) return false;

    if (hasFeedContent()) return true;
    if (
      window.__ROBIN_LATEST_OK__ &&
      Date.now() - bootLatestOkAt >= BOOT_AFTER_LATEST_MS
    ) {
      return true;
    }
    if (!settings.preferLatest && elapsed >= BOOT_FOLLOWING_SETTLE_MS) {
      return hasFeedContent() || elapsed >= 2500;
    }
    return false;
  }

  function maybeRevealBoot() {
    if (bootRevealed) return;
    if (isBootReady()) revealBoot();
  }

  // --- Caught-Up Marker (Instagram-style feed marker for last-viewed post) ---
  var SESSION_IDLE_TIMEOUT_MS = 15 * 60 * 1000; // 15 minutes of inactivity/backgrounding
  var sessionResolvedMarkerId = null;

  function initCaughtUpSession() {
    sessionResolvedMarkerId = null;
    try {
      var now = Date.now();
      var lastActive = parseInt(
        localStorage.getItem("robin_last_activity_time") || "0",
        10
      );
      var pendingCurrentId = localStorage.getItem(
        "robin_current_viewed_status_id"
      );
      var pendingCurrentTime = localStorage.getItem(
        "robin_current_viewed_status_time"
      );
      var isNewSession =
        !lastActive || now - lastActive > SESSION_IDLE_TIMEOUT_MS;

      if (pendingCurrentId) {
        localStorage.setItem("robin_last_viewed_status_id", pendingCurrentId);
        if (pendingCurrentTime) {
          localStorage.setItem(
            "robin_last_viewed_status_time",
            pendingCurrentTime
          );
        }
        localStorage.setItem("robin_target_marker_id", pendingCurrentId);
        if (pendingCurrentTime) {
          localStorage.setItem(
            "robin_target_marker_time",
            pendingCurrentTime
          );
        }
        localStorage.removeItem("robin_current_viewed_status_id");
        localStorage.removeItem("robin_current_viewed_status_time");
      } else if (isNewSession) {
        var lastId = localStorage.getItem("robin_last_viewed_status_id");
        var lastTime = localStorage.getItem("robin_last_viewed_status_time");
        if (lastId) {
          localStorage.setItem("robin_target_marker_id", lastId);
          if (lastTime) localStorage.setItem("robin_target_marker_time", lastTime);
        }
      }
      localStorage.setItem("robin_last_activity_time", String(now));
    } catch (e) {}
  }

  function compareStatusIds(a, b) {
    if (!a || !b) return 0;
    if (a === b) return 0;
    if (typeof BigInt === "function") {
      try {
        var diff = BigInt(a) - BigInt(b);
        return diff > 0n ? 1 : diff < 0n ? -1 : 0;
      } catch (e) {}
    }
    if (a.length !== b.length) return a.length > b.length ? 1 : -1;
    return a > b ? 1 : -1;
  }

  function isPostNewer(idA, timeA, idB, timeB) {
    if (!idB) return true;
    if (idA === idB) return false;
    if (timeA && timeB && Math.abs(timeA - timeB) > 1000) {
      return timeA > timeB;
    }
    return compareStatusIds(idA, idB) > 0;
  }

  function isPostOlderOrEqual(idA, timeA, idB, timeB) {
    if (!idB) return false;
    if (idA === idB) return true;
    if (timeA && timeB && Math.abs(timeA - timeB) > 1000) {
      return timeA <= timeB;
    }
    return compareStatusIds(idA, idB) <= 0;
  }

  function timestampFromArticle(article) {
    if (!article || !article.querySelector) return 0;
    var id = statusIdFromArticle(article);
    if (id && repostTimes && repostTimes[id]) {
      return repostTimes[id];
    }
    var timeEl = article.querySelector("time[datetime]");
    if (!timeEl) return 0;
    var dt = timeEl.getAttribute("datetime");
    if (!dt) return 0;
    var parsed = Date.parse(dt);
    return isNaN(parsed) ? 0 : parsed;
  }

  function isArticleRepost(article) {
    if (!article || !article.querySelector) return false;
    var sc = article.querySelector('[data-testid="socialContext"]');
    if (sc && (sc.textContent || "").toLowerCase().indexOf("repost") !== -1) {
      return true;
    }
    var id = statusIdFromArticle(article);
    if (id && repostTimes && repostTimes[id]) return true;
    return false;
  }

  function isArticleHidden(article) {
    if (!article) return true;
    if (
      article.getAttribute &&
      article.getAttribute("data-mt-robin-hide") === "1"
    ) {
      return true;
    }
    if (article.style && article.style.display === "none") return true;
    var cell = article.closest
      ? article.closest('[data-testid="cellInnerDiv"]')
      : article.parentElement;
    if (cell) {
      if (
        cell.getAttribute &&
        cell.getAttribute("data-mt-robin-hide") === "1"
      ) {
        return true;
      }
      if (cell.style && cell.style.display === "none") return true;
      if (
        cell.querySelector &&
        cell.querySelector('[data-testid="placementTracking"]')
      ) {
        return true;
      }
    }
    return false;
  }

  function isArticleInViewport(art) {
    if (!art || !art.getBoundingClientRect) return false;
    var rect = art.getBoundingClientRect();
    var windowHeight =
      window.innerHeight ||
      (document.documentElement && document.documentElement.clientHeight) ||
      800;
    return rect.bottom > 40 && rect.top < windowHeight - 40;
  }

  function trackViewedPosts() {
    if (!isHomePath() || !isFollowingFeedActive()) return;
    var settings = currentSettings();
    if (!settings.forceFollowing) return;

    var articles = document.querySelectorAll(
      '[data-testid="cellInnerDiv"] article[data-testid="tweet"]'
    );
    if (!articles || !articles.length) return;

    var topVisibleArticle = null;
    for (var i = 0; i < articles.length; i++) {
      var art = articles[i];
      if (isArticleHidden(art)) continue;
      if (isArticleInViewport(art)) {
        topVisibleArticle = art;
        break;
      }
    }

    if (!topVisibleArticle) return;

    var id = statusIdFromArticle(topVisibleArticle);
    if (!id) return;
    var time = timestampFromArticle(topVisibleArticle);

    try {
      var curId = localStorage.getItem("robin_current_viewed_status_id");
      var curTime = parseInt(
        localStorage.getItem("robin_current_viewed_status_time") || "0",
        10
      );
      if (!curId || isPostNewer(id, time, curId, curTime)) {
        localStorage.setItem("robin_current_viewed_status_id", id);
        if (time) {
          localStorage.setItem(
            "robin_current_viewed_status_time",
            String(time)
          );
        } else {
          localStorage.removeItem("robin_current_viewed_status_time");
        }
      }
      localStorage.setItem("robin_last_activity_time", String(Date.now()));
    } catch (e) {}
  }

  function removeCaughtUpMarker() {
    var markers = document.querySelectorAll(
      ".robin-caught-up-marker, #robin-caught-up-marker"
    );
    for (var i = 0; i < markers.length; i++) {
      markers[i].remove();
    }
  }

  function createCaughtUpMarkerElement() {
    var el = document.createElement("div");
    el.id = "robin-caught-up-marker";
    el.className = "robin-caught-up-marker";
    el.setAttribute("data-testid", "robin-caught-up-marker");
    el.setAttribute("role", "status");
    el.setAttribute(
      "aria-label",
      "You're all caught up. You've seen all new posts from last time."
    );
    el.innerHTML =
      '<div class="robin-caught-up-content">' +
      '  <div class="robin-caught-up-icon" aria-hidden="true">' +
      '    <svg width="32" height="32" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">' +
      '      <circle cx="16" cy="16" r="14" stroke="#00ba7c" stroke-width="2.5" fill="none"/>' +
      '      <path d="M10 16.5L14 20.5L22 12" stroke="#00ba7c" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>' +
      '    </svg>' +
      '  </div>' +
      '  <div class="robin-caught-up-title">You\'re all caught up</div>' +
      '  <div class="robin-caught-up-subtitle">You\'ve seen all new posts from last time</div>' +
      '</div>';
    return el;
  }

  function firstVisibleFeedArticle(list) {
    if (!list) return null;
    for (var i = 0; i < list.length; i++) {
      var art = list[i];
      if (isArticleHidden(art)) continue;
      if (art.offsetHeight < 1) continue;
      return art;
    }
    return null;
  }

  function updateCaughtUpMarker() {
    if (!isHomePath() || !isFollowingFeedActive()) {
      removeCaughtUpMarker();
      return;
    }
    var settings = currentSettings();
    if (!settings.forceFollowing) {
      removeCaughtUpMarker();
      return;
    }

    var targetId = null;
    var targetTime = 0;
    try {
      targetId = localStorage.getItem("robin_target_marker_id");
      targetTime = parseInt(
        localStorage.getItem("robin_target_marker_time") || "0",
        10
      );
    } catch (e) {}

    if (!targetId) {
      removeCaughtUpMarker();
      return;
    }

    var articles = document.querySelectorAll(
      '[data-testid="cellInnerDiv"] article[data-testid="tweet"]'
    );
    if (!articles || !articles.length) {
      removeCaughtUpMarker();
      return;
    }

    var effectiveTargetId = sessionResolvedMarkerId || targetId;

    // 1. Look for exact matching status ID (or already resolved boundary ID)
    var targetArticle = null;
    for (var i = 0; i < articles.length; i++) {
      var art = articles[i];
      if (isArticleHidden(art)) continue;
      if (art.offsetHeight < 1) continue;
      var aId = statusIdFromArticle(art);
      if (aId && (aId === targetId || aId === effectiveTargetId)) {
        targetArticle = art;
        sessionResolvedMarkerId = aId;
        break;
      }
    }

    // 2. Fallback: ONLY if we haven't locked onto a resolved boundary ID yet,
    // look for a valid chronological boundary (a newer post followed by an older post).
    // NEVER match arbitrary older posts if the user simply scrolled past the marker.
    if (!targetArticle && !sessionResolvedMarkerId) {
      for (var j = 0; j < articles.length; j++) {
        var a = articles[j];
        if (isArticleHidden(a)) continue;
        if (isArticleRepost(a)) continue;
        var curId = statusIdFromArticle(a);
        var curTime = timestampFromArticle(a);
        if (!curId) continue;

        if (isPostOlderOrEqual(curId, curTime, targetId, targetTime)) {
          var isBoundary = false;
          if (j === 0) {
            // Nothing newer than last visit. The banner belongs between new
            // posts and old ones, not above the first post.
            isBoundary = false;
          } else {
            // Verify that a previous visible post was newer than targetId
            for (var k = j - 1; k >= 0; k--) {
              var prev = articles[k];
              if (isArticleHidden(prev)) continue;
              var prevId = statusIdFromArticle(prev);
              var prevTime = timestampFromArticle(prev);
              if (prevId && isPostNewer(prevId, prevTime, targetId, targetTime)) {
                isBoundary = true;
                break;
              }
            }
          }

          if (isBoundary) {
            targetArticle = a;
            sessionResolvedMarkerId = curId;
            break;
          }
        }
      }
    }

    // If target post is not currently mounted (user is scrolled above or below it),
    // remove any markers so stale/recycled cells don't display a rogue marker.
    if (!targetArticle) {
      removeCaughtUpMarker();
      return;
    }

    // Already caught up on open: the last-seen post is still the first one.
    // Do not show the banner at the top of the feed.
    if (feedScrollNearTop() && targetArticle === firstVisibleFeedArticle(articles)) {
      removeCaughtUpMarker();
      return;
    }

    var parent = targetArticle.parentElement;
    if (!parent) return;

    // Remove any duplicate or misplaced markers in the document
    var allMarkers = document.querySelectorAll(
      ".robin-caught-up-marker, #robin-caught-up-marker"
    );
    var marker = null;
    for (var m = 0; m < allMarkers.length; m++) {
      var curMarker = allMarkers[m];
      if (
        !marker &&
        curMarker.parentElement === parent &&
        curMarker.nextElementSibling === targetArticle
      ) {
        marker = curMarker;
      } else {
        curMarker.remove();
      }
    }

    if (!marker) {
      marker = createCaughtUpMarkerElement();
      parent.insertBefore(marker, targetArticle);
    }
  }

  initCaughtUpSession();

  document.addEventListener("visibilitychange", function () {
    try {
      if (document.visibilityState === "hidden") {
        localStorage.setItem("robin_last_activity_time", String(Date.now()));
      } else if (document.visibilityState === "visible") {
        var last = parseInt(
          localStorage.getItem("robin_last_activity_time") || "0",
          10
        );
        if (Date.now() - last > SESSION_IDLE_TIMEOUT_MS) {
          initCaughtUpSession();
          requestFullPass();
        } else {
          localStorage.setItem("robin_last_activity_time", String(Date.now()));
        }
      }
    } catch (e) {}
  });

  function persistSessionOnUnload() {
    try {
      localStorage.setItem("robin_last_activity_time", String(Date.now()));
      var curId = localStorage.getItem("robin_current_viewed_status_id");
      if (curId) {
        localStorage.setItem("robin_last_viewed_status_id", curId);
        var curTime = localStorage.getItem(
          "robin_current_viewed_status_time"
        );
        if (curTime) {
          localStorage.setItem("robin_last_viewed_status_time", curTime);
        }
      }
    } catch (e) {}
  }
  window.addEventListener("pagehide", persistSessionOnUnload);
  window.addEventListener("beforeunload", persistSessionOnUnload);

  var DRAIN_BUDGET = 24;
  var processedCells = typeof WeakSet === "function" ? new WeakSet() : null;
  var needsFullPass = true;
  var pendingNodes = [];
  var pendingSeen = typeof WeakSet === "function" ? new WeakSet() : null;
  var scrolling = false;
  var scrollIdleTimer = 0;

  function rememberPending(n) {
    if (!n || n.nodeType !== 1) return;
    if (pendingSeen) {
      if (pendingSeen.has(n)) return;
      pendingSeen.add(n);
    }
    pendingNodes.push(n);
  }

  function queueMutationNode(n) {
    if (!n) return;
    if (n.nodeType === 3) n = n.parentElement;
    if (!n || n.nodeType !== 1) return;
    if (
      n.id === "robin-caught-up-marker" ||
      (n.classList && n.classList.contains("robin-caught-up-marker"))
    ) {
      return;
    }
    if (n.classList && n.classList.contains("mt-repost-time")) return;
    var cell = n.closest && n.closest('[data-testid="cellInnerDiv"]');
    rememberPending(cell || n);
  }

  function expandWorkItem(n) {
    if (!n || !n.isConnected || !n.querySelectorAll) return [];
    if (n.getAttribute && n.getAttribute("data-testid") === "cellInnerDiv") return [n];
    var cells = n.querySelectorAll('[data-testid="cellInnerDiv"]');
    if (cells.length > 1) {
      var out = [];
      for (var i = 0; i < cells.length; i++) out.push(cells[i]);
      return out;
    }
    return [n];
  }

  function tick() {
    syncPageHeaderFlags();
    applyFontScale();
    forceFollowing();
    forceForYou();
    hideForYouTab();
    preferLatest();

    if (needsFullPass) {
      needsFullPass = false;
      pendingNodes = [];
      pendingSeen = typeof WeakSet === "function" ? new WeakSet() : null;
      labelRepostTimes(document);
      hideOpenAppNags();
      hideWhoToFollowBlocks(document);
      hideLiveContentBlocks(document);
      hideKeywordPosts(document);
      hidePageHeaderFallback();
      hideComposeButtonFallback();
      var allCells = document.querySelectorAll('[data-testid="cellInnerDiv"]');
      if (processedCells) {
        for (var ac = 0; ac < allCells.length; ac++) processedCells.add(allCells[ac]);
      }
    } else {
      var batch = pendingNodes;
      pendingNodes = [];
      pendingSeen = typeof WeakSet === "function" ? new WeakSet() : null;
      var work = [];
      for (var i = 0; i < batch.length; i++) {
        var parts = expandWorkItem(batch[i]);
        for (var j = 0; j < parts.length; j++) work.push(parts[j]);
      }
      var limit = Math.min(work.length, DRAIN_BUDGET);
      for (var w = 0; w < limit; w++) {
        var n = work[w];
        if (processedCells) processedCells.add(n);
        labelRepostTimes(n);
        hideWhoToFollowBlocks(n);
        hideLiveContentBlocks(n);
        hideKeywordPosts(n);
      }
      if (work.length > limit) {
        for (var k = limit; k < work.length; k++) rememberPending(work[k]);
        schedule();
      }
      // Header walk early-returns once settled. Compose / nag selectors stay
      // narrow (href and testid), so they can run on the incremental path.
      hidePageHeaderFallback();
      hideComposeButtonFallback();
      hideOpenAppNags();
    }

    trackViewedPosts();
    updateCaughtUpMarker();

    maybeRetryWelcome();
    maybeRevealBoot();
    notePinChrome();
    checkDOMHealth();
  }

  var reportedTabsMissing = false;
  var reportedCellsMissing = false;
  function checkDOMHealth() {
    if (!isHomePath()) return;
    if (hasLoginCta() || isWelcomeInterstitial()) return;

    // Check tabs after settle period (4 seconds)
    if (!reportedTabsMissing && Date.now() - bootStart >= 4000) {
      var tabs = typeof findHomeTabs === "function" ? findHomeTabs() : {};
      if (!tabs.following && !tabs.forYou) {
        reportedTabsMissing = true;
        reportToSentry(
          "X DOM: Following/For You tabs not found on home feed",
          "warning",
          { tag: "home_tabs_missing" }
        );
      }
    }

    // Check cells/tweets after boot revealed + 3 seconds
    if (!reportedCellsMissing && bootRevealed && Date.now() - bootReadyAt >= 3000) {
      var tweets = document.querySelectorAll('article[data-testid="tweet"]');
      var cells = document.querySelectorAll('[data-testid="cellInnerDiv"]');
      if (tweets.length === 0 && cells.length === 0) {
        reportedCellsMissing = true;
        reportToSentry(
          "X DOM: No tweets or cellInnerDiv containers found on home feed after boot",
          "warning",
          { tag: "timeline_empty_cells" }
        );
      }
    }
  }

  document.addEventListener("click", onUserTabClick, true);

  var scheduled = false;
  var observer = new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var added = records[i].addedNodes;
      for (var j = 0; j < added.length; j++) queueMutationNode(added[j]);
    }
    schedule();
  });

  function observeFeed() {
    try {
      observer.observe(document.documentElement || document, {
        childList: true,
        subtree: true,
      });
    } catch (e) {}
  }

  function runScheduled() {
    scheduled = false;
    // Virtualizer commits are the scroll-critical work. Filter ticks wait
    // until the fling ends so newly scrolled-in cells can mount in time.
    if (bootRevealed && scrolling) return;
    observer.disconnect();
    try {
      tick();
    } finally {
      if (!scrolling) observeFeed();
    }
  }

  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(runScheduled, bootRevealed && scrolling ? 160 : 50);
  }

  function queueMountedCells() {
    var cells = document.querySelectorAll('[data-testid="cellInnerDiv"]');
    for (var i = 0; i < cells.length; i++) {
      if (processedCells && processedCells.has(cells[i])) continue;
      rememberPending(cells[i]);
    }
  }

  // X updates the timeline on a 100ms throttle and, while a fling is in
  // progress, shrinks the mounted window to half a viewport. On Android
  // WebView that update is also queued through requestIdleCallback, which
  // does not run until the fling slows down, so the viewport is white for
  // the whole gap. Run the update on the next frame, keep the idle window,
  // and mount several viewports ahead. The stock 2.5-viewport window is
  // left behind by one fast fling; about eight viewports stays filled.
  // React replaces props on each commit, so the ratios have to be forced
  // inside the candidate pass or the next update snaps the window back.
  function patchAndroidScroller(s) {
    if (!s || s.__robinFast) return;
    var root = document.documentElement;
    if (!root || !root.classList.contains("mt-android-webview")) return;
    if (typeof s._update !== "function" || typeof s._handleScroll !== "function") return;
    s.__robinFast = true;
    if (typeof s._getRenderCandidates === "function") {
      var origCandidates = s._getRenderCandidates;
      s._getRenderCandidates = function () {
        if (this.props) {
          this.props.preferredOffscreenToViewportRatio = 8;
          this.props.minimumOffscreenToViewportRatio = 6;
        }
        return origCandidates.apply(this, arguments);
      };
    }
    var update = s._update.bind(s);
    var queued = false;
    function pump() {
      if (queued) return;
      queued = true;
      requestAnimationFrame(function () {
        queued = false;
        try {
          update();
        } catch (e) {}
      });
    }
    s._scheduleCriticalUpdate = pump;
    s._scheduleCriticalUpdateThrottled = pump;
    var origScroll = s._handleScroll;
    s._handleScroll = function () {
      var result = origScroll.apply(this, arguments);
      this._isIdle = true;
      return result;
    };
    s._isIdle = true;
    try {
      update();
    } catch (e) {}
  }

  function onFeedScroll() {
    patchAndroidScroller(window.scroller);
    if (!bootRevealed) return;
    if (!scrolling) {
      scrolling = true;
      // Drop the document observer for the whole gesture. Its callback runs
      // on every cell X mounts and was holding the virtualizer a full second
      // behind a fast fling (white viewport until the scroll slowed).
      try {
        observer.disconnect();
      } catch (e) {}
    }
    clearTimeout(scrollIdleTimer);
    scrollIdleTimer = setTimeout(function () {
      scrolling = false;
      // The tap snapshot has to survive the open post (its scroller is not
      // the feed) and the pin itself. Replacing it with scrollY here is what
      // left the feed short by the home-tab row: that row is removed on the
      // tick this idle schedules, after the anchor was already discarded.
      var pinning = pinUntil && Date.now() < pinUntil;
      if (
        !pinning &&
        isHomePath() &&
        lastFeedScroller &&
        Date.now() >= freezeUntil
      ) {
        savedScrollY = readScrollerY(lastFeedScroller);
        savedAnchor = null;
      }
      queueMountedCells();
      schedule();
    }, 120);
  }
  window.addEventListener("scroll", onFeedScroll, { capture: true, passive: true });

  function requestFullPass() {
    needsFullPass = true;
    chromePassHref = "";
    schedule();
  }

  window.__ROBIN_ON_SETTINGS__ = function () {
    keywordSource = null;
    needsFullPass = true;
    chromePassHref = "";
    schedule();
  };

  observeFeed();

  // Tapping a post scrolls that row toward the top before X records history.
  // Back then restores the shifted position, so the feed lands further down.
  // Snapshot the viewport at pointerdown and pin it again when /home returns.
  // Home chrome (Following / For You) is shown again on the status page and
  // removed only after scroll goes idle. That removal is about two lines
  // tall; pinning must still be active when it happens or the posts sit
  // that much higher than where the tap was.
  var savedScrollY = null;
  var savedAnchor = null;
  var lastFeedScroller = null;
  var pinArticle = null;
  var freezeUntil = 0;
  var pinUntil = 0;
  var pinLoopOn = false;
  var pinChromePending = false;
  var pinStableSince = 0;
  var applyingPin = false;
  var lastPinTop = null;
  var lastNudgeMoved = false;
  var tapX = 0;
  var tapY = 0;

  function pathnameOf(href) {
    var path = String(href || "");
    var hash = path.indexOf("#");
    if (hash !== -1) path = path.slice(0, hash);
    var q = path.indexOf("?");
    if (q !== -1) path = path.slice(0, q);
    var scheme = path.indexOf("://");
    if (scheme !== -1) {
      var slash = path.indexOf("/", scheme + 3);
      path = slash === -1 ? "/" : path.slice(slash);
    }
    return path || "/";
  }

  function hrefIsHome(href) {
    var p = pathnameOf(href);
    return p === "/" || p === "/home" || p.indexOf("/home") === 0;
  }

  function asElement(node) {
    if (!node) return null;
    if (node.nodeType === 1) return node;
    return node.parentElement || null;
  }

  function statusIdFromArticle(article) {
    if (!article || !article.querySelector) return "";
    var link = article.querySelector('a[href*="/status/"] time');
    link = link && link.closest ? link.closest("a") : null;
    if (!link) link = article.querySelector('a[href*="/status/"]');
    var m = link && /\/status\/(\d+)/.exec(link.getAttribute("href") || "");
    return m ? m[1] : "";
  }

  function captureAnchor(el) {
    if (!el || !el.closest) return null;
    var article = el.closest('article[data-testid="tweet"]');
    if (!article) {
      var link = el.closest('a[href*="/status/"]');
      article = link && link.closest ? link.closest("article") : null;
    }
    if (!article) return null;
    var id = statusIdFromArticle(article);
    if (!id) return null;
    return { id: id, top: article.getBoundingClientRect().top };
  }

  function isDocumentScroller(el) {
    return (
      el === document.scrollingElement ||
      el === document.documentElement ||
      el === document.body
    );
  }

  function readScrollerY(el) {
    if (!el) return 0;
    if (isDocumentScroller(el)) return window.scrollY || el.scrollTop || 0;
    return el.scrollTop || 0;
  }

  function primaryFeedScroller() {
    var best = null;
    var bestExtra = 0;
    var docBest = null;
    var docExtra = 0;
    function consider(el) {
      if (!el || el.nodeType !== 1) return;
      var extra = (el.scrollHeight || 0) - (el.clientHeight || 0);
      if (extra <= 80) return;
      if (isDocumentScroller(el)) {
        if (extra > docExtra) {
          docExtra = extra;
          docBest = el;
        }
        return;
      }
      if (extra > bestExtra) {
        bestExtra = extra;
        best = el;
      }
    }
    consider(document.scrollingElement);
    consider(document.documentElement);
    consider(document.body);
    var col = document.querySelector('[data-testid="primaryColumn"]');
    var node = col;
    for (var i = 0; node && i < 10; i++) {
      consider(node);
      node = node.parentElement;
    }
    if (col) {
      var stack = [col];
      var seen = 0;
      while (stack.length && seen < 40) {
        var cur = stack.pop();
        seen++;
        consider(cur);
        if (cur.getAttribute && cur.getAttribute("data-testid") === "tweet") continue;
        var kids = cur.children;
        for (var k = 0; k < kids.length && k < 4; k++) stack.push(kids[k]);
      }
    }
    return best || docBest;
  }

  function feedScrollerEl() {
    if (
      lastFeedScroller &&
      lastFeedScroller.isConnected &&
      lastFeedScroller.scrollHeight > (lastFeedScroller.clientHeight || 0) + 80
    ) {
      return lastFeedScroller;
    }
    return primaryFeedScroller();
  }

  function currentFeedScrollY() {
    return readScrollerY(feedScrollerEl());
  }

  function writeFeedScroll(y) {
    var el = feedScrollerEl();
    if (!el) return;
    if (isDocumentScroller(el)) {
      if (Math.abs((window.scrollY || 0) - y) > 1) window.scrollTo(0, y);
      return;
    }
    // Window scroll on top of an inner timeline shifts the whole feed.
    if ((window.scrollY || 0) > 1) window.scrollTo(0, 0);
    if (Math.abs((el.scrollTop || 0) - y) > 1) el.scrollTop = y;
  }

  function nudgeFeedBy(delta) {
    var el = feedScrollerEl();
    if (!el) return false;
    var before = readScrollerY(el);
    if (isDocumentScroller(el)) {
      window.scrollTo(0, before + delta);
    } else {
      if ((window.scrollY || 0) > 1) window.scrollTo(0, 0);
      el.scrollTop = before + delta;
    }
    return Math.abs(readScrollerY(el) - before) > 0.5;
  }

  function findFeedArticle(statusId) {
    if (!isHomePath() || !statusId) return null;
    if (
      pinArticle &&
      pinArticle.isConnected &&
      statusIdFromArticle(pinArticle) === statusId
    ) {
      return pinArticle;
    }
    var nodes = document.querySelectorAll(
      '[data-testid="cellInnerDiv"] article[data-testid="tweet"]'
    );
    // Status back-navigation can leave a second copy of the same post.
    // Prefer the visible one closest to where the tap was.
    var best = null;
    var bestDist = 1e15;
    var wantTop = savedAnchor ? savedAnchor.top : 0;
    for (var i = 0; i < nodes.length; i++) {
      if (statusIdFromArticle(nodes[i]) !== statusId) continue;
      if (nodes[i].offsetHeight < 1) continue;
      var dist = Math.abs(nodes[i].getBoundingClientRect().top - wantTop);
      if (dist < bestDist) {
        bestDist = dist;
        best = nodes[i];
      }
    }
    if (best) {
      pinArticle = best;
      return pinArticle;
    }
    return null;
  }

  function rememberFeedTap(target) {
    if (!isHomePath()) return;
    var el = asElement(target);
    savedScrollY = currentFeedScrollY();
    savedAnchor = captureAnchor(el);
    var link = el && el.closest && el.closest("a[href]");
    if (savedAnchor || link) freezeUntil = Date.now() + 900;
  }

  function pinFeedStep() {
    if (applyingPin) return false;
    applyingPin = true;
    try {
      if (savedAnchor) {
        var article = findFeedArticle(savedAnchor.id);
        if (article) {
          var top = article.getBoundingClientRect().top;
          var delta = top - savedAnchor.top;
          if (Math.abs(delta) < 2) return true;
          // Row didn't move after the last nudge. One stale frame is normal;
          // a second means this scroller isn't the feed, so use the saved offset.
          if (lastPinTop != null && Math.abs(top - lastPinTop) < 1) {
            if (!lastNudgeMoved && savedScrollY != null) writeFeedScroll(savedScrollY);
            lastNudgeMoved = false;
            return false;
          }
          lastPinTop = top;
          lastNudgeMoved = nudgeFeedBy(delta);
          return false;
        }
      }
      if (savedScrollY == null) return true;
      var el = feedScrollerEl();
      if (!el) return false;
      if (Math.abs(readScrollerY(el) - savedScrollY) < 2) return !savedAnchor;
      writeFeedScroll(savedScrollY);
      return false;
    } finally {
      applyingPin = false;
    }
  }

  function feedChromeReadyForPin() {
    if (!isHomePath()) return false;
    if (
      !document.querySelector(
        '[data-testid="cellInnerDiv"] article[data-testid="tweet"]'
      )
    ) {
      return false;
    }
    var root = document.documentElement;
    if (root && root.getAttribute("data-mt-defer-feed-chrome") === "1") return true;
    var bar = document.querySelector('[data-testid="TopNavBar"]');
    if (bar && bar.offsetHeight > 0) {
      var tabsStay = !currentSettings().hidePageHeader && rootContainsTablist(bar);
      if (!tabsStay) return false;
    }
    // When Show Feed Tab is on, category tabs stay visible — that is fine.
    if (!currentSettings().hidePageHeader) return true;
    var tablist = document.querySelector(
      "div[data-testid='ScrollSnap-List'][role='tablist']"
    );
    if (tablist && tablist.offsetHeight > 0) return false;
    return true;
  }

  // Called at the end of a filter tick. The tick is what removes the home
  // tab row; the pin has to re-measure after that, not before.
  function notePinChrome() {
    if (!pinChromePending || !feedChromeReadyForPin()) return;
    pinChromePending = false;
    pinStableSince = 0;
    lastPinTop = null;
    if (pinUntil && Date.now() < pinUntil) {
      pinUntil = Math.max(pinUntil, Date.now() + 800);
    }
  }

  function beginFeedPin() {
    if (savedScrollY == null && !savedAnchor) return;
    pinUntil = Date.now() + 2000;
    pinArticle = null;
    lastPinTop = null;
    lastNudgeMoved = false;
    pinStableSince = 0;
    if (pinLoopOn) return;
    pinLoopOn = true;
    var started = Date.now();
    function step() {
      if (!pinUntil || Date.now() >= pinUntil || !isHomePath()) {
        pinLoopOn = false;
        pinUntil = 0;
        pinChromePending = false;
        return;
      }
      if (pinFeedStep()) {
        if (!pinStableSince) pinStableSince = Date.now();
      } else {
        pinStableSince = 0;
      }
      // X restores history a moment later, then the idle tick strips the
      // home tab row. Hold until that strip is gone and the row has stayed
      // put, otherwise the feed settles a tab-row too high.
      var held = pinStableSince && Date.now() - pinStableSince > 400;
      if (held && !pinChromePending && Date.now() - started > 700) {
        pinLoopOn = false;
        pinUntil = 0;
        return;
      }
      requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  function cancelFeedPin() {
    pinUntil = 0;
  }

  function onFeedPathChange(prevHref, nextHref) {
    if (!hrefIsHome(nextHref)) {
      removeCaughtUpMarker();
    }
    // Only when a post (or other non-home route) is popped back to the feed.
    // The snapshot was taken on the feed itself; re-reading here would sample
    // the status page that just unmounted.
    if (hrefIsHome(prevHref) || !hrefIsHome(nextHref)) return;
    freezeUntil = 0;
    // CSS hides TopNavBar / the home tab list as soon as these flags are set.
    // Doing it before the pin measures, instead of on the deferred tick.
    syncPageHeaderFlags();
    if (savedScrollY == null && !savedAnchor) return;
    pinChromePending = true;
    beginFeedPin();
  }

  // --- Long-press to Fact-Check ---
  var factCheckTimer = null;
  var factCheckTargetArticle = null;
  var factCheckStartX = 0;
  var factCheckStartY = 0;
  var suppressNextContextMenu = false;

  function cancelFactCheck() {
    if (factCheckTimer) {
      clearTimeout(factCheckTimer);
      factCheckTimer = null;
    }
    factCheckTargetArticle = null;
  }

  function extractTweetData(article) {
    if (!article || !article.querySelector) return null;
    var tweetTextEl = article.querySelector('[data-testid="tweetText"]');
    var text = tweetTextEl ? (tweetTextEl.innerText || tweetTextEl.textContent || "").trim() : "";

    var quoteEl = article.querySelector('[data-testid="quoteTweet"]');
    if (quoteEl) {
      var quoteTextEl = quoteEl.querySelector('[data-testid="tweetText"]');
      var qText = quoteTextEl ? (quoteTextEl.innerText || quoteTextEl.textContent || "").trim() : "";
      if (qText) {
        text = text ? text + "\n\n[Quoted Tweet]:\n" + qText : qText;
      }
    }

    if (!text) return null;

    var userEl = article.querySelector('[data-testid="User-Name"]');
    var author = userEl ? (userEl.innerText || userEl.textContent || "").trim().replace(/\s+/g, " ") : "";

    var statusId = statusIdFromArticle(article);
    var tweetURL = statusId ? "https://x.com/i/status/" + statusId : "";

    var links = [];
    var linkEls = article.querySelectorAll('a[href*="t.co"], [data-testid="card.wrapper"] a[href]');
    for (var i = 0; i < linkEls.length; i++) {
      var href = linkEls[i].getAttribute("href");
      if (href && href.indexOf("http") === 0 && links.indexOf(href) === -1) {
        links.push(href);
      }
    }

    return {
      text: text,
      author: author,
      url: tweetURL,
      links: links
    };
  }

  function triggerFactCheck(article) {
    var payload = extractTweetData(article);
    if (!payload || !payload.text) return;

    article.classList.add("robin-factcheck-pulse");
    setTimeout(function () {
      article.classList.remove("robin-factcheck-pulse");
    }, 600);

    suppressNextContextMenu = true;
    setTimeout(function () {
      suppressNextContextMenu = false;
    }, 800);

    try {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.robinFactCheck) {
        window.webkit.messageHandlers.robinFactCheck.postMessage(payload);
      }
      if (window.RobinFactCheck && window.RobinFactCheck.factCheck) {
        window.RobinFactCheck.factCheck(JSON.stringify(payload));
      }
    } catch (e) {}
  }

  document.addEventListener(
    "pointerdown",
    function (e) {
      tapX = e.clientX;
      tapY = e.clientY;

      var art = e.target && e.target.closest ? e.target.closest('article[data-testid="tweet"]') : null;
      if (art && (e.button === undefined || e.button === 0)) {
        var tag = (e.target.tagName || "").toLowerCase();
        if (tag !== "input" && tag !== "textarea" && tag !== "video") {
          factCheckTargetArticle = art;
          factCheckStartX = e.clientX;
          factCheckStartY = e.clientY;
          factCheckTimer = setTimeout(function () {
            triggerFactCheck(art);
            cancelFactCheck();
          }, 400);
        }
      }

      if (!isHomePath()) return;
      if (pinUntil && Date.now() < pinUntil) cancelFeedPin();
      rememberFeedTap(e.target);
    },
    true
  );
  document.addEventListener(
    "pointermove",
    function (e) {
      if (factCheckTimer) {
        if (Math.abs(e.clientX - factCheckStartX) > 18 || Math.abs(e.clientY - factCheckStartY) > 18) {
          cancelFactCheck();
        }
      }
      if (!freezeUntil || Date.now() >= freezeUntil) return;
      if (Math.abs(e.clientX - tapX) + Math.abs(e.clientY - tapY) > 16) {
        freezeUntil = 0;
      }
    },
    true
  );
  document.addEventListener("pointerup", cancelFactCheck, true);
  document.addEventListener("pointercancel", cancelFactCheck, true);
  document.addEventListener(
    "contextmenu",
    function (e) {
      if (factCheckTimer && factCheckTargetArticle) {
        var art = factCheckTargetArticle;
        cancelFactCheck();
        triggerFactCheck(art);
        e.preventDefault();
        e.stopPropagation();
        return false;
      }
      if (suppressNextContextMenu) {
        e.preventDefault();
        e.stopPropagation();
        suppressNextContextMenu = false;
        return false;
      }
    },
    true
  );
  document.addEventListener(
    "wheel",
    function () {
      cancelFactCheck();
      freezeUntil = 0;
      if (pinUntil && Date.now() < pinUntil) cancelFeedPin();
    },
    { capture: true, passive: true }
  );
  document.addEventListener(
    "keydown",
    function (e) {
      if (pinUntil && Date.now() < pinUntil) cancelFeedPin();
      if ((e.key === "Enter" || e.key === " ") && isHomePath()) {
        rememberFeedTap(e.target);
      }
    },
    true
  );
  document.addEventListener(
    "click",
    function (e) {
      if (!isHomePath() || Date.now() < freezeUntil) return;
      var el = asElement(e.target);
      var anchor = captureAnchor(el);
      var link = el && el.closest && el.closest('a[href*="/status/"]');
      if (!anchor && !link) return;
      savedScrollY = currentFeedScrollY();
      if (anchor) savedAnchor = anchor;
      freezeUntil = Date.now() + 900;
    },
    true
  );
  window.addEventListener(
    "scroll",
    function (e) {
      cancelFactCheck();
      var t = e.target;
      var el =
        t && t !== window && t !== document && typeof t.scrollTop === "number"
          ? t
          : document.scrollingElement || document.documentElement;
      if (el) lastFeedScroller = el;
      if (pinUntil && Date.now() < pinUntil && isHomePath() && !applyingPin) {
        pinFeedStep();
      }
    },
    { capture: true, passive: true }
  );

  var lastHref = location.href;
  function noteLocation(href) {
    if (href === lastHref) return;
    var prev = lastHref;
    lastHref = href;
    onFeedPathChange(prev, href);
    requestFullPass();
  }

  var origPushState = history.pushState;
  var origReplaceState = history.replaceState;
  if (origPushState) {
    history.pushState = function () {
      var before = location.href;
      var ret = origPushState.apply(this, arguments);
      if (location.href !== before) noteLocation(location.href);
      return ret;
    };
  }
  if (origReplaceState) {
    history.replaceState = function () {
      var before = location.href;
      var ret = origReplaceState.apply(this, arguments);
      if (location.href !== before) noteLocation(location.href);
      return ret;
    };
  }
  window.addEventListener("popstate", function () {
    noteLocation(location.href);
  });

  // SPA path changes (fallback if navigation skips history.pushState)
  setInterval(function () {
    patchAndroidScroller(window.scroller);
    noteLocation(location.href);
  }, 500);

  // --- In-App Automated Performance Benchmark Harness ---
  window.__ROBIN_RUN_BENCHMARK__ = function () {
    var initialLoadMs =
      bootReadyAt > 0 ? bootReadyAt - bootStart : Date.now() - bootStart;
    var scroller =
      lastFeedScroller ||
      document.scrollingElement ||
      document.documentElement ||
      window;

    var scrollStart = performance.now();
    var stepCount = 20;
    var stepIndex = 0;
    var stepDistance = 75;

    function doScrollStep() {
      if (stepIndex < stepCount) {
        stepIndex++;
        if (scroller === window) {
          window.scrollBy(0, stepDistance);
        } else {
          scroller.scrollTop += stepDistance;
        }
        window.dispatchEvent(new Event("scroll"));
        setTimeout(doScrollStep, 16);
      } else {
        // Wait for scroll debounce + queue drain + double RAF render settle
        setTimeout(function () {
          requestAnimationFrame(function () {
            requestAnimationFrame(function () {
              var scrollEnd = performance.now();
              var scrollRenderCompletionMs = Math.round(
                scrollEnd - scrollStart
              );
              var result = {
                initialLoadMs: initialLoadMs,
                scrollRenderCompletionMs: scrollRenderCompletionMs,
                bootReady: !!bootRevealed,
                timestamp: Date.now(),
              };
              var json = JSON.stringify(result);
              try {
                if (window.RobinBenchmark && window.RobinBenchmark.post) {
                  window.RobinBenchmark.post(json);
                }
              } catch (e) {}
              try {
                if (
                  window.webkit &&
                  window.webkit.messageHandlers &&
                  window.webkit.messageHandlers.robinBenchmark
                ) {
                  window.webkit.messageHandlers.robinBenchmark.postMessage(json);
                }
              } catch (e2) {}
            });
          });
        }, 160);
      }
    }

    doScrollStep();
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", requestFullPass);
  } else {
    requestFullPass();
  }
  setTimeout(requestFullPass, 400);
  setTimeout(requestFullPass, 1200);
  setTimeout(maybeRevealBoot, BOOT_HARD_MS);
})();
