import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

// Exercise the asset that Android actually ships, including after asset syncs.
// The optional path is used to prove the fixtures fail against the old asset.
const assetPath = process.env.ROBIN_FILTER_ASSET || fileURLToPath(
  new URL('../android/app/src/main/assets/x-filter/filter-core.js', import.meta.url),
);
const source = readFileSync(assetPath, 'utf8');

// A small DOM fixture, not a browser emulator. It models tree membership,
// selectors, labels, click destinations and menu changes relevant to this
// boundary. Real WebView navigation/rendering still requires device testing.
function simpleMatch(element, selector) {
  const attributes = [...selector.matchAll(/\[([^\]\s~^*$|=]+)(?:([*^$]?=)["']?([^\]"']*)["']?)?\]/g)];
  let rest = selector.replace(/\[[^\]]*\]/g, '').trim();
  const tag = rest.match(/^[a-zA-Z][\w-]*/)?.[0];
  if (tag && element.tagName.toLowerCase() !== tag.toLowerCase()) return false;
  if (tag) rest = rest.slice(tag.length);
  if (rest.startsWith('#')) {
    if (element.id !== rest.slice(1)) return false;
    rest = '';
  }
  if (rest.startsWith('.')) {
    if (!element.classList.contains(rest.slice(1))) return false;
    rest = '';
  }
  if (rest && rest !== '*') throw new Error(`Unsupported fixture selector: ${selector}`);
  return attributes.every(([, name, operator, value]) => {
    const actual = element.getAttribute(name);
    if (!operator) return actual !== null;
    if (actual === null) return false;
    if (operator === '=') return actual === value;
    if (operator === '*=') return actual.includes(value);
    if (operator === '^=') return actual.startsWith(value);
    if (operator === '$=') return actual.endsWith(value);
    throw new Error(`Unsupported attribute operator: ${operator}`);
  });
}

function matches(element, selectors) {
  return selectors.split(',').some(selector => {
    // Split combinators only outside attribute values such as "For you".
    // All action selectors under test are simple or descendant selectors.
    const tokens = [];
    let token = '', bracket = 0;
    for (const ch of selector.trim()) {
      if (ch === '[') bracket++;
      if (ch === ']') bracket--;
      if (/\s/.test(ch) && bracket === 0) {
        if (token) tokens.push(token);
        token = '';
      } else token += ch;
    }
    if (token) tokens.push(token);
    if (!tokens.length || !simpleMatch(element, tokens.pop())) return false;
    let ancestor = element.parentElement;
    while (tokens.length) {
      const wanted = tokens.pop();
      while (ancestor && !simpleMatch(ancestor, wanted)) ancestor = ancestor.parentElement;
      if (!ancestor) return false;
      ancestor = ancestor.parentElement;
    }
    return true;
  });
}

function fixture(settings = {}) {
  const clicks = [];
  const timers = [];
  const clock = { now: 100_000 };
  let document;

  class Element {
    constructor(tag, attributes = {}, text = '') {
      this.tagName = tag.toUpperCase();
      this.nodeType = 1;
      this.attributes = { ...attributes };
      this.ownText = text;
      this.children = [];
      this.parentElement = null;
      this.listeners = new Map();
      this.scrollTop = 0;
      this.disabled = false;
      const classes = new Set();
      this.classList = {
        add: value => classes.add(value),
        remove: value => classes.delete(value),
        contains: value => classes.has(value),
        toggle(value, enabled) { if (enabled) classes.add(value); else classes.delete(value); },
      };
      const styles = new Map();
      this.style = {
        setProperty: (key, value) => styles.set(key, value),
        removeProperty: key => styles.delete(key),
        getPropertyValue: key => styles.get(key) || '',
        getPropertyPriority: () => '',
      };
    }
    get textContent() { return this.ownText + this.children.map(child => child.textContent).join(''); }
    set textContent(text) { this.ownText = text; this.children = []; }
    get innerText() { return this.textContent; }
    get id() { return this.attributes.id || ''; }
    set id(value) { this.attributes.id = value; }
    get href() { return this.attributes.href || ''; }
    set href(value) { this.attributes.href = value; }
    get childNodes() { return this.children; }
    get isConnected() {
      let node = this;
      while (node.parentElement) node = node.parentElement;
      return node === document;
    }
    getAttribute(name) { return this.attributes[name] ?? null; }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    removeAttribute(name) { delete this.attributes[name]; }
    appendChild(child) {
      child.remove();
      child.parentElement = this;
      this.children.push(child);
      return child;
    }
    remove() {
      if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(child => child !== this);
      this.parentElement = null;
    }
    matches(selector) { return matches(this, selector); }
    closest(selector) {
      for (let node = this; node; node = node.parentElement) if (node.matches(selector)) return node;
      return null;
    }
    querySelectorAll(selector) {
      const result = [];
      const visit = node => {
        for (const child of node.children) {
          if (child.matches(selector)) result.push(child);
          visit(child);
        }
      };
      visit(this);
      return result;
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    getBoundingClientRect() { return { top: 80, bottom: 104, left: 0, right: 100, width: 100, height: 24 }; }
    click() { clicks.push(this); this.onClick?.(); }
    addEventListener(name, callback) {
      if (!this.listeners.has(name)) this.listeners.set(name, []);
      this.listeners.get(name).push(callback);
    }
    dispatchEvent(event) { for (const callback of this.listeners.get(event.type) || []) callback(event); }
  }

  document = new Element('document');
  document.documentElement = document.appendChild(new Element('html'));
  document.head = document.documentElement.appendChild(new Element('head'));
  document.body = document.documentElement.appendChild(new Element('body'));
  document.scrollingElement = document.documentElement;
  document.readyState = 'loading';
  document.getElementById = id => document.querySelector(`#${id}`);
  document.createElement = tag => new Element(tag);
  const primary = document.body.appendChild(new Element('div', { 'data-testid': 'primaryColumn' }));
  const layers = document.body.appendChild(new Element('div', { id: 'layers' }));
  const storage = new Map();
  const window = {
    __ROBIN_SETTINGS__: { forceFollowing: true, preferLatest: true, ...settings },
    innerHeight: 800, innerWidth: 390, scrollY: 0,
    addEventListener() {},
    getComputedStyle: () => ({ display: 'block', visibility: 'visible', position: 'static' }),
  };
  const context = vm.createContext({
    window, document,
    location: { href: 'https://x.com/home', pathname: '/home', origin: 'https://x.com' },
    history: {},
    Date: class extends Date { static now() { return clock.now; } },
    performance: { now: () => clock.now, mark() {} },
    MutationObserver: class { observe() {} disconnect() {} },
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    setTimeout(callback, delay) { timers.push({ callback, time: clock.now + delay }); return timers.length; },
    clearTimeout() {}, setInterval() {}, requestAnimationFrame() {},
    console,
  });
  // Test-only access to existing closure functions; production has no test hook.
  const ending = source.lastIndexOf('})();');
  assert.ok(ending > 0, 'Expected the Android asset IIFE');
  const hooks = `window.testControls = { forceFollowing, forceForYou, preferLatest, findHomeTabs,
    receiveHome: function(ranked) { homeResponseSeen = true; window.__ROBIN_HOME_RANKED__ = ranked; } };`;
  vm.runInContext(source.slice(0, ending) + hooks + source.slice(ending), context, { filename: assetPath, timeout: 1000 });
  timers.length = 0; // Focus on action functions; don't run unrelated rendering timers.
  window.testControls.receiveHome(true);

  function add(parent, tag, attributes = {}, text = '') { return parent.appendChild(new Element(tag, attributes, text)); }
  function tabs(parent = primary, labels = ['For you', 'Following']) {
    const list = add(parent, 'div', { 'data-testid': 'ScrollSnap-List', role: 'tablist' });
    const forYou = add(list, 'div', { role: 'tab', 'aria-selected': 'true' }, labels[0]);
    const following = add(list, 'div', { role: 'tab', 'aria-selected': 'false', 'aria-haspopup': 'menu' }, labels[1]);
    return { list, forYou, following };
  }
  function menu(parent = layers) {
    const root = add(parent, 'div', { role: 'menu' });
    const popular = add(root, 'div', { role: 'menuitem' }, 'Popular');
    const recent = add(root, 'div', { role: 'menuitem' }, 'Recent');
    add(root, 'button', {}, 'Cancel');
    return { root, popular, recent };
  }
  function flushTimers(delay) {
    clock.now += delay;
    const ready = timers.filter(timer => timer.time <= clock.now);
    timers.splice(0, timers.length, ...timers.filter(timer => timer.time > clock.now));
    for (const timer of ready) timer.callback();
  }
  return { window, document, primary, layers, clicks, clock, add, tabs, menu, flushTimers, controls: window.testControls };
}

for (const [label, container, attrs, tag] of [
  ['New posts', 'article', { 'data-testid': 'tweet' }, 'a'],
  ['See new posts', 'div', { 'data-testid': 'card.wrapper' }, 'a'],
  ['Show 20 posts', 'div', { 'data-testid': 'quoteTweet' }, 'button'],
]) {
  test(`post-controlled label ${JSON.stringify(label)} never triggers refresh clicks`, () => {
    const f = fixture();
    const content = f.add(f.primary, container, attrs);
    f.add(content, tag, { role: tag === 'a' ? 'link' : 'button', href: '/attacker' }, label);
    assert.equal(f.window.__ROBIN_SOFT_REFRESH_FEED__(), false);
    assert.equal(f.clicks.length, 0);
  });
}

test('verified home tabs still select Following and For you', () => {
  const f = fixture();
  const t = f.tabs();
  f.controls.forceFollowing();
  assert.equal(f.clicks.at(-1), t.following);
  f.clock.now += 2000;
  f.window.__ROBIN_SETTINGS__ = { forceFollowing: false };
  t.forYou.setAttribute('aria-selected', 'false');
  f.controls.forceForYou();
  assert.equal(f.clicks.at(-1), t.forYou);
});

for (const [tag, attrs] of [
  ['article', {}], ['div', { 'data-testid': 'tweet' }],
  ['div', { 'data-testid': 'tweetText' }], ['div', { 'data-testid': 'quoteTweet' }],
  ['div', { 'data-testid': 'card.wrapper' }], ['div', { 'data-testid': 'User-Name' }],
  ['div', { 'data-testid': 'cellInnerDiv' }], ['div', { role: 'dialog' }],
]) {
  test(`matching tabs inside ${tag} ${JSON.stringify(attrs)} cannot become feed controls`, () => {
    const f = fixture();
    f.tabs(f.add(f.primary, tag, attrs));
    f.controls.forceFollowing();
    assert.equal(f.clicks.length, 0);
  });
}

test('cached tabs moved into a post are revalidated before clicking', () => {
  const f = fixture();
  const t = f.tabs();
  assert.equal(f.controls.findHomeTabs().following, t.following);
  f.add(f.primary, 'article').appendChild(t.list);
  f.controls.forceFollowing();
  assert.equal(f.clicks.length, 0);
});

test('partial tab labels and navigable post destinations fail closed', () => {
  const f = fixture();
  f.tabs(f.primary, ['For you', 'Following this attacker']);
  f.controls.forceFollowing();
  assert.equal(f.clicks.length, 0);
  const g = fixture();
  const t = g.tabs();
  t.following.remove();
  g.add(t.list, 'a', { role: 'tab', href: '/attacker/status/123' }, 'Following');
  g.controls.forceFollowing();
  assert.equal(g.clicks.length, 0);
});

function beginSort(f) {
  const t = f.tabs();
  t.following.setAttribute('aria-selected', 'true');
  t.forYou.setAttribute('aria-selected', 'false');
  let opened;
  t.following.onClick = () => { opened = f.menu(); };
  f.controls.preferLatest();
  assert.equal(f.clicks.at(-1), t.following, 'verified opener must work');
  return { t, opened };
}

test('a fresh verified sort menu still selects Recent', () => {
  const f = fixture();
  const { opened } = beginSort(f);
  f.controls.preferLatest();
  assert.equal(f.clicks.at(-1), opened.recent);
  assert.equal(f.clicks.length, 2);
});

test('a pre-existing menu is never adopted as our sort menu', () => {
  const f = fixture();
  f.menu();
  f.controls.preferLatest();
  assert.equal(f.clicks.length, 0);
});

test('trusted user input cancels a pending automatic menu choice', () => {
  const f = fixture();
  beginSort(f);
  f.document.dispatchEvent({ type: 'keydown', isTrusted: true, target: f.document.body });
  f.controls.preferLatest();
  assert.equal(f.clicks.length, 1);
});

test('trusted accessibility clicks cancel pending sort without pointer or key input', () => {
  const f = fixture();
  beginSort(f);
  f.document.dispatchEvent({ type: 'click', isTrusted: true, target: f.document.body });
  f.controls.preferLatest();
  assert.equal(f.clicks.length, 1);
});

test('synthetic clicks do not cancel a verified pending sort', () => {
  const f = fixture();
  const { t, opened } = beginSort(f);
  f.document.dispatchEvent({ type: 'click', isTrusted: false, target: t.following });
  f.controls.preferLatest();
  assert.equal(f.clicks.at(-1), opened.recent);
  assert.equal(f.clicks.length, 2);
});

for (const value of ['true', 'menu', 'listbox', 'dialog']) {
  test(`recognised aria-haspopup=${value} opens the verified sort control`, () => {
    const f = fixture();
    const t = f.tabs();
    t.following.setAttribute('aria-selected', 'true');
    t.following.setAttribute('aria-haspopup', value);
    f.controls.preferLatest();
    assert.deepEqual(f.clicks, [t.following]);
  });
}

for (const value of ['false', '', 'tree', 'grid', 'not-a-popup']) {
  test(`aria-haspopup=${JSON.stringify(value)} cannot identify a sort opener`, () => {
    const f = fixture();
    const t = f.tabs();
    t.following.setAttribute('aria-selected', 'true');
    t.following.setAttribute('aria-haspopup', value);
    f.add(t.following, 'span', { 'aria-haspopup': value });
    f.controls.preferLatest();
    assert.equal(f.clicks.length, 0);
  });
}

test('a recognised nested popup control is selected after false-valued candidates', () => {
  const f = fixture();
  const t = f.tabs();
  t.following.setAttribute('aria-selected', 'true');
  t.following.setAttribute('aria-haspopup', 'false');
  f.add(t.following, 'span', { 'aria-haspopup': 'false' });
  const opener = f.add(t.following, 'span', { 'aria-haspopup': 'menu' });
  f.controls.preferLatest();
  assert.deepEqual(f.clicks, [opener]);
});

test('expired menus and moved openers cannot receive a queued click', () => {
  const f = fixture();
  beginSort(f);
  f.clock.now += 2000;
  f.controls.preferLatest();
  assert.equal(f.clicks.length, 1);
  const g = fixture();
  const { t } = beginSort(g);
  g.add(g.primary, 'article').appendChild(t.list);
  g.controls.preferLatest();
  assert.equal(g.clicks.length, 1);
});

test('sort-looking menus containing quoted posts or links fail closed', () => {
  for (const content of ['quote', 'link']) {
    const f = fixture();
    const { opened } = beginSort(f);
    if (content === 'quote') f.add(opened.root, 'div', { 'data-testid': 'quoteTweet' }, 'A post');
    else f.add(opened.root, 'a', { href: '/attacker' }, 'A profile');
    f.controls.preferLatest();
    assert.equal(f.clicks.length, 1);
  }
});

test('legacy timeline options never click an arbitrary first menu item', () => {
  const f = fixture();
  const t = f.tabs();
  t.following.setAttribute('aria-selected', 'true');
  const legacy = f.add(f.primary, 'div', { 'aria-label': 'Timeline options' });
  legacy.onClick = () => {
    const root = f.add(f.layers, 'div', { role: 'menu' });
    f.add(root, 'div', { role: 'menuitem', tabindex: '0' }, 'Delete');
  };
  f.controls.preferLatest();
  f.flushTimers(120);
  f.controls.preferLatest();
  assert.deepEqual(f.clicks, [legacy]);
});

test('native compose ignores post links with prefilled text and uses the fixed route', () => {
  const f = fixture();
  const post = f.add(f.primary, 'article', { 'data-testid': 'tweet' });
  const supplied = f.add(post, 'a', { href: '/compose/post?text=attacker-chosen' }, 'Compose');
  assert.equal(f.window.__ROBIN_OPEN_COMPOSE__(), true);
  assert.equal(f.clicks.length, 1);
  assert.notEqual(f.clicks[0], supplied);
  assert.equal(f.clicks[0].href, '/compose/post');
});

test('explicit compose controls work but matching controls inside posts do not', () => {
  const f = fixture();
  const real = f.add(f.document.body, 'a', { 'data-testid': 'SideNav_NewTweet_Button', href: '/compose/post' });
  f.window.__ROBIN_OPEN_COMPOSE__();
  assert.equal(f.clicks[0], real);
  const g = fixture();
  const supplied = g.add(g.add(g.primary, 'article'), 'a', { 'data-testid': 'SideNav_NewTweet_Button', href: '/compose/post' });
  g.window.__ROBIN_OPEN_COMPOSE__();
  assert.notEqual(g.clicks[0], supplied);
  assert.equal(g.clicks[0].href, '/compose/post');
});
