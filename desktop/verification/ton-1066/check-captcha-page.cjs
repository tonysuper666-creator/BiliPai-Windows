// Executes the production HTML's inline script with controlled DOM/Geetest/fetch.
// This is not a browser render or proof that a real Geetest challenge can be solved.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../src/main/kotlin/com/bilipai/desktop/data/DesktopCaptchaBridge.kt'), 'utf8');
const script = source.match(/<script>\n([\s\S]*?)<\/script><script src=/)[1]
  .replace('$safeJson', JSON.stringify({ gt: 'test-gt', challenge: 'test-challenge' }))
  .replace('$resultPath', '/11111111-1111-1111-1111-111111111111/result');

function page({ sdk = true, rejected = false, throws = false } = {}) {
  const elements = Object.fromEntries(['status', 'retry', 'cancel'].map(id => [id, { disabled: id === 'retry' }]));
  const posts = [], timers = [], hooks = {};
  let cleared = false;
  const api = {
    onReady(fn) { hooks.ready = fn; return this; },
    onSuccess(fn) { hooks.success = fn; return this; },
    onError(fn) { hooks.error = fn; return this; },
    onClose(fn) { hooks.close = fn; return this; },
    verify() {},
    getValidate() { return { geetest_challenge: 'test-challenge', geetest_validate: 'test-validate', geetest_seccode: 'test-seccode' }; },
  };
  const context = vm.createContext({
    document: { getElementById: id => elements[id] },
    window: sdk ? { Geetest() { if (throws) throw new Error('SDK initialization'); return api; } } : {},
    setTimeout(fn) { timers.push(fn); return timers.length; },
    clearTimeout() { cleared = true; },
    fetch(route, options) {
      assert.equal(route, '/11111111-1111-1111-1111-111111111111/result');
      assert.equal(options.credentials, 'omit');
      assert.equal(options.headers['Content-Type'], 'application/json');
      posts.push(JSON.parse(options.body));
      return Promise.resolve({ ok: !rejected });
    },
  });
  vm.runInContext(script, context);
  return { context, elements, posts, timers, hooks, api, cleared: () => cleared };
}

async function run(name, action) {
  await action();
  console.log(`PASS ${name}`);
}

(async () => {
  await run('script-load failure reports promptly', () => {
    const p = page(); p.context.failCaptcha();
    assert.deepEqual(p.posts, [{ failed: true }]);
    assert.match(p.elements.status.textContent, /扫码登录/);
  });
  for (const options of [{ sdk: false }, { throws: true }]) {
    await run('missing or throwing SDK reports failure', () => {
      const p = page(options); p.context.startCaptcha();
      assert.deepEqual(p.posts, [{ failed: true }]);
    });
  }
  await run('SDK that never becomes ready hits loading deadline', () => {
    const p = page(); p.context.startCaptcha(); p.timers[0]();
    assert.deepEqual(p.posts, [{ failed: true }]);
    assert.equal(p.elements.retry.disabled, true);
  });
  await run('explicit cancel is terminal', () => {
    const p = page(); p.elements.cancel.onclick(); p.context.startCaptcha();
    assert.deepEqual(p.posts, [{ cancel: true }]);
  });
  await run('success returns challenge once even when SDK closes', () => {
    const p = page(); p.context.startCaptcha(); p.hooks.ready();
    assert.equal(p.cleared(), true);
    assert.equal(p.elements.retry.disabled, false);
    p.hooks.success(); p.hooks.close(); p.hooks.error();
    assert.deepEqual(p.posts, [{ challenge: 'test-challenge', validate: 'test-validate', seccode: 'test-seccode' }]);
  });
  await run('rejected callback shows delivery failure', async () => {
    const p = page({ rejected: true }); p.context.startCaptcha(); p.hooks.success();
    await new Promise(setImmediate);
    assert.match(p.elements.status.textContent, /无法回传/);
  });
  await run('missing validation and SDK errors report failure', () => {
    const p = page(); p.context.startCaptcha(); p.api.getValidate = () => null; p.hooks.success();
    assert.deepEqual(p.posts, [{ failed: true }]);
    const q = page(); q.context.startCaptcha(); q.hooks.error();
    assert.deepEqual(q.posts, [{ failed: true }]);
  });
  await run('retry exceptions report failure', () => {
    const p = page(); p.context.startCaptcha(); p.hooks.ready();
    p.api.verify = () => { throw new Error('SDK retry'); }; p.elements.retry.onclick();
    assert.deepEqual(p.posts, [{ failed: true }]);
  });
  await run('asynchronous SDK callback exceptions report failure', () => {
    const p = page(); p.context.startCaptcha();
    p.api.verify = () => { throw new Error('SDK ready'); }; p.hooks.ready();
    assert.deepEqual(p.posts, [{ failed: true }]);
    const q = page(); q.context.startCaptcha();
    q.api.getValidate = () => { throw new Error('SDK validation'); }; q.hooks.success();
    assert.deepEqual(q.posts, [{ failed: true }]);
  });
})().catch(error => { console.error(error); process.exitCode = 1; });
