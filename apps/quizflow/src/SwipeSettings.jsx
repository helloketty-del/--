import CustomClickSettings from './CustomClickSettings.jsx';
import React, { useRef, useState } from 'react';
import { SWIPE_DEFAULTS, sanitizeSwipeProfiles, swipeDirection, swipeProfileError } from './lib/swipeSettings.js';

export default function SwipeSettings({ settings, onChange, onTestGesture, disabled }) {
  const svgRef = useRef(null);
  const [dragging, setDragging] = useState(null);
  const mode = swipeDirection(settings.navigation);
  const direction = ['confirm', 'tap'].includes(mode) ? 'up' : mode;
  const profiles = sanitizeSwipeProfiles(settings.swipeProfiles);
  const profile = profiles[direction];
  const error = swipeProfileError(settings);
  const update = patch => onChange('swipeProfiles', { ...profiles, [direction]: { ...profile, ...patch } });
  const drag = event => {
    if (!dragging || disabled) return;
    const bounds = svgRef.current.getBoundingClientRect();
    const percent = (value, origin, size) => Math.round(Math.max(0, Math.min(100, (value - origin) / size * 100)));
    update({ [`${dragging}X`]: percent(event.clientX, bounds.left, bounds.width),
      [`${dragging}Y`]: percent(event.clientY, bounds.top, bounds.height) });
  };
  const beginDrag = (point, event) => {
    if (disabled) return;
    event.preventDefault();
    svgRef.current.setPointerCapture(event.pointerId);
    setDragging(point);
  };
  return <div className="swipe-settings">
    <label className="field-block"><span>翻页方式</span>
      <select value={mode} onChange={e => onChange('navigation', e.target.value)} disabled={disabled}>
        <option value="tap">自定义点击</option>
        <option value="confirm">确认按钮（失败时换方式重试）</option>
        <option value="up">上滑（手指向上）</option>
        <option value="left">左滑（手指向左）</option>
        <option value="right">右滑（手指向右）</option>
      </select>
      <small>{mode === 'confirm' ? '保留原有确认翻题流程。' : mode === 'tap' ? '直接点击你指定的位置，不依赖 AI 定位翻页按钮。' : '直接在屏幕上滑动；采集模式也使用此设置。'}</small>
    </label>
    {mode === 'tap' && <CustomClickSettings settings={settings} onChange={onChange} disabled={disabled} />}
    {!['confirm', 'tap'].includes(mode) && <>
    <div className="swipe-preview-row">
      <svg ref={svgRef} className="swipe-preview" viewBox="0 0 100 178" preserveAspectRatio="none"
        role="img" aria-label="滑动轨迹预览，可拖动起点和终点" onPointerMove={drag}
        onPointerUp={() => setDragging(null)} onPointerCancel={() => setDragging(null)}>
        <defs><marker id="swipe-arrow" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6" fill="none" stroke="#c6f477" strokeWidth="1.2" /></marker></defs>
        <path d="M0,44.5 H100 M0,89 H100 M0,133.5 H100 M25,0 V178 M50,0 V178 M75,0 V178" stroke="#41574d" strokeWidth=".4" />
        <line x1={profile.startX} y1={profile.startY * 1.78} x2={profile.endX} y2={profile.endY * 1.78}
          stroke="#c6f477" strokeWidth="1.6" markerEnd="url(#swipe-arrow)" />
        <circle data-testid="swipe-start" cx={profile.startX} cy={profile.startY * 1.78} r="6"
          fill="#85d3ff" stroke="#14221f" strokeWidth="1" onPointerDown={e => beginDrag('start', e)} />
        <circle data-testid="swipe-end" cx={profile.endX} cy={profile.endY * 1.78} r="6"
          fill="#f28b5d" stroke="#14221f" strokeWidth="1" onPointerDown={e => beginDrag('end', e)} />
      </svg>
      <div className="swipe-preview-help"><strong>自定义滑动范围</strong>
        <p><i className="swipe-dot swipe-dot-start" />蓝点是起点<br /><i className="swipe-dot swipe-dot-end" />橙点是终点</p>
        <p>拖动两个点，或调整下方数值。坐标按整个屏幕的百分比计算。</p>
        <p>左上角 0%, 0%<br />右下角 100%, 100%</p>
      </div>
    </div>
    <div className="swipe-coordinate-grid">
      {[['startX', '起点 X'], ['startY', '起点 Y'], ['endX', '终点 X'], ['endY', '终点 Y']].map(([key, label]) =>
        <div key={key} className="swipe-coordinate">
          <label className="field-block"><span>{label}（%）</span><input aria-label={label} type="number" min="0" max="100" step="1"
            value={profile[key]} onChange={e => update({ [key]: Number(e.target.value) })} disabled={disabled} /></label>
          <input aria-label={`${label}滑杆`} type="range" min="0" max="100" step="1" value={profile[key]}
            onChange={e => update({ [key]: Number(e.target.value) })} disabled={disabled} />
        </div>)}
    </div>
    <label className="field-block"><span>滑动时长（毫秒）</span><input aria-label="滑动时长（毫秒）" type="number" min="50" max="5000" step="50"
      value={profile.durationMs} onChange={e => update({ durationMs: Number(e.target.value) })} disabled={disabled} />
      <small>每个方向分别保存范围和时长。</small></label>
    {error && <p className="swipe-error" role="alert">{error}</p>}
    <button type="button" className="button button-quiet swipe-reset" disabled={disabled}
      onClick={() => onChange('swipeProfiles', { ...profiles, [direction]: { ...SWIPE_DEFAULTS[direction] } })}>恢复本方向默认范围</button>
    </>}
    <button type="button" className="button button-primary swipe-test" disabled={disabled} onClick={onTestGesture}>动作测试（不调用 AI）</button>
    <p className="swipe-preview-help">启动后打开目标题目页，再点悬浮窗的“测试翻页”。测试只执行一次，便于检查点击位置或滑动范围。</p>
  </div>;
}
