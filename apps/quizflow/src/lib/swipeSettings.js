export const SWIPE_DEFAULTS = Object.freeze({
  up: Object.freeze({ startX: 50, startY: 80, endX: 50, endY: 25, durationMs: 450 }),
  left: Object.freeze({ startX: 85, startY: 50, endX: 15, endY: 50, durationMs: 450 }),
  right: Object.freeze({ startX: 15, startY: 50, endX: 85, endY: 50, durationMs: 450 }),
});

export function swipeDirection(value) {
  return ['confirm', 'up', 'left', 'right'].includes(value) ? value : (value === 'down' ? 'up' : 'confirm');
}

export function sanitizeSwipeProfiles(raw = {}) {
  const finite = (value, fallback, min, max) => value !== null && value !== '' && Number.isFinite(Number(value))
    ? Math.min(max, Math.max(min, Number(value))) : fallback;
  return Object.fromEntries(Object.entries(SWIPE_DEFAULTS).map(([direction, fallback]) => {
    const v = raw?.[direction] || {};
    return [direction, {
      ...Object.fromEntries(['startX', 'startY', 'endX', 'endY'].map(k => [k, finite(v[k], fallback[k], 0, 100)])),
      durationMs: Math.round(finite(v.durationMs, fallback.durationMs, 50, 5000)),
    }];
  }));
}

export function swipeProfileError(settings) {
  const profiles = sanitizeSwipeProfiles(settings.swipeProfiles);
  for (const direction of Object.keys(SWIPE_DEFAULTS)) {
    const p = profiles[direction];
    const label = { up: '上滑', left: '左滑', right: '右滑' }[direction];
    if (Math.hypot(p.endX - p.startX, p.endY - p.startY) < 1) return `${label}：滑动起点与终点距离至少需要 1%。`;
    if ((direction === 'up' && p.endY >= p.startY) || (direction === 'left' && p.endX >= p.startX)
      || (direction === 'right' && p.endX <= p.startX)) return `${label}：起点和终点与滑动方向不一致，请调整范围。`;
  }
  return '';
}
