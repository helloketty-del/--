import test from 'node:test';
import assert from 'node:assert/strict';
import { sanitizeCustomClicks, swipeDirection } from '../src/lib/swipeSettings.js';
import { sanitizeSettings, saveBrowserSettings, readBrowserSettings } from '../src/lib/settings.js';

test('custom click navigation preserves separate next and confirmation points', () => {
  const config = sanitizeSettings({ navigation: 'tap', customClicks: {
    next: { x: 89, y: 93, durationMs: 120 }, confirm: { x: 49, y: 93, durationMs: 90 }, confirmFirst: true,
  } });
  assert.equal(swipeDirection(config.navigation), 'tap');
  assert.deepEqual(config.customClicks, { next: { x: 89, y: 93, durationMs: 120 }, confirm: { x: 49, y: 93, durationMs: 90 }, confirmFirst: true });
});

test('custom click settings clamp invalid coordinates and long-press durations', () => {
  const points = sanitizeCustomClicks({ next: { x: 120, y: -1, durationMs: 5000 }, confirm: { x: NaN, y: '', durationMs: 1 } });
  assert.deepEqual(points.next, { x: 100, y: 0, durationMs: 500 });
  assert.deepEqual(points.confirm, { x: 50, y: 94, durationMs: 50 });
});

test('custom click points survive reload without persisting action-test flags or clearing API key', () => {
  const previous = globalThis.window, values = new Map();
  const storage = { getItem: k => values.get(k) || null, setItem: (k,v) => values.set(k,v), removeItem: k => values.delete(k) };
  globalThis.window = { localStorage: storage, sessionStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} } };
  try {
    saveBrowserSettings({ navigation: 'tap', apiKey: 'test-key', gestureTestOnly: true,
      customClicks: { next: { x: 88, y: 95, durationMs: 140 }, confirmFirst: true } });
    const restored = readBrowserSettings();
    assert.equal(restored.apiKey, 'test-key'); assert.equal(restored.gestureTestOnly, undefined);
    assert.equal(restored.customClicks.next.durationMs, 140); assert.equal(restored.customClicks.confirmFirst, true);
  } finally { globalThis.window = previous; }
});
