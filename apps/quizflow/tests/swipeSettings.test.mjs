import test from 'node:test';
import assert from 'node:assert/strict';
import { SWIPE_DEFAULTS, sanitizeSwipeProfiles, swipeDirection, swipeProfileError } from '../src/lib/swipeSettings.js';
import { sanitizeSettings, saveBrowserSettings, readBrowserSettings } from '../src/lib/settings.js';

test('old automatic navigation preserves confirmation and legacy down migrates to up', () => {
  for (const old of ['auto', undefined]) assert.equal(sanitizeSettings({ navigation: old }).navigation, 'confirm');
  assert.equal(sanitizeSettings({ navigation: 'down' }).navigation, 'up');
  assert.equal(swipeDirection('left'), 'left');
  assert.equal(swipeDirection('right'), 'right');
});

test('each direction retains independent swipe coordinates and timing', () => {
  const settings = sanitizeSettings({ navigation: 'left', swipeProfiles: {
    left: { startX: 90, startY: 60, endX: 12, endY: 60, durationMs: 800 },
    up: { startX: 40, startY: 85, endX: 40, endY: 20, durationMs: 600 },
  } });
  assert.equal(settings.swipeProfiles.left.durationMs, 800);
  assert.equal(settings.swipeProfiles.up.startX, 40);
  assert.deepEqual(settings.swipeProfiles.right, SWIPE_DEFAULTS.right);
  assert.equal(swipeProfileError(settings), '');
});

test('invalid direction and zero length paths are reported before a run', () => {
  assert.match(swipeProfileError({ swipeProfiles: { up: { startY: 20, endY: 80 } } }), /方向/);
  assert.match(swipeProfileError({ swipeProfiles: { left: { startX: 20, endX: 90 } } }), /方向/);
  assert.match(swipeProfileError({ swipeProfiles: { right: { startX: 90, endX: 20 } } }), /方向/);
  assert.match(swipeProfileError({ swipeProfiles: { up: { startX: 50, startY: 50, endX: 50, endY: 50 } } }), /距离/);
});

test('coordinate and duration bounds are enforced without NaN', () => {
  const p = sanitizeSwipeProfiles({ up: { startX: -2, startY: 180, endX: NaN, endY: '', durationMs: 99999 } }).up;
  assert.deepEqual(p, { startX: 0, startY: 100, endX: 50, endY: 25, durationMs: 5000 });
  assert.equal(sanitizeSwipeProfiles({ left: { durationMs: 2 } }).left.durationMs, 50);
});

test('custom screen path survives save and app reload', () => {
  const previous = globalThis.window;
  const values = new Map();
  const storage = { getItem: k => values.get(k) || null, setItem: (k,v) => values.set(k,v), removeItem: k => values.delete(k) };
  globalThis.window = { localStorage: storage, sessionStorage: storage };
  try {
    const settings = { navigation: 'right', swipeProfiles: { right: { startX: 10, startY: 65, endX: 80, endY: 65, durationMs: 950 } } };
    saveBrowserSettings(settings);
    const restored = readBrowserSettings();
    assert.equal(restored.navigation, 'right');
    assert.deepEqual(restored.swipeProfiles.right, settings.swipeProfiles.right);
    assert.equal(swipeProfileError(restored), '');
  } finally { globalThis.window = previous; }
});
