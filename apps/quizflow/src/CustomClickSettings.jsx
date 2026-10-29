import React, { useRef, useState } from 'react';
import { sanitizeCustomClicks, TAP_DEFAULTS } from './lib/swipeSettings.js';

export default function CustomClickSettings({ settings, onChange, disabled }) {
  const points = sanitizeCustomClicks(settings.customClicks);
  const [dragging, setDragging] = useState(null);
  const svgRef = useRef(null);
  const update = (name, patch) => onChange('customClicks', { ...points, [name]: { ...points[name], ...patch } });
  const begin = (name, event) => {
    if (disabled) return;
    event.preventDefault(); svgRef.current.setPointerCapture(event.pointerId); setDragging(name);
  };
  const move = event => {
    if (!dragging || disabled) return;
    const r = svgRef.current.getBoundingClientRect();
    const p = (value, origin, length) => Math.round(Math.max(0, Math.min(100, (value - origin) / length * 100)) * 10) / 10;
    update(dragging, { x: p(event.clientX, r.left, r.width), y: p(event.clientY, r.top, r.height) });
  };
  return <div className="custom-click-settings">
    <div className="swipe-preview-row">
      <svg ref={svgRef} className="swipe-preview" viewBox="0 0 100 178" preserveAspectRatio="none" role="img"
        aria-label="自定义点击位置预览，可拖动圆点" onPointerMove={move} onPointerUp={() => setDragging(null)} onPointerCancel={() => setDragging(null)}>
        <path d="M0,44.5 H100 M0,89 H100 M0,133.5 H100 M25,0 V178 M50,0 V178 M75,0 V178" stroke="#41574d" strokeWidth=".4" />
        {['next', ...(points.confirmFirst ? ['confirm'] : [])].map(name => <g key={name}>
          <circle cx={points[name].x} cy={points[name].y * 1.78} r="13" fill="transparent" onPointerDown={e => begin(name, e)} data-testid={`tap-${name}`} />
          <circle cx={points[name].x} cy={points[name].y * 1.78} r="5" fill={name === 'next' ? '#85d3ff' : '#f28b5d'} pointerEvents="none" />
        </g>)}
      </svg>
      <div className="swipe-preview-help"><strong>固定位置点击</strong><p>蓝点：下一题<br />橙点：提交本题</p>
        <p>拖动圆点，或填写下方屏幕百分比。</p><p>采集和动作测试只点击“下一题”点。</p></div>
    </div>
    <label className="field-block"><span><input type="checkbox" checked={points.confirmFirst} disabled={disabled}
      onChange={e => onChange('customClicks', { ...points, confirmFirst: e.target.checked })} />先点提交本题，再点下一题</span>
      <small>用于自动答题；两个动作之间使用下方的页面等待时间。最终交卷仍由自动提交开关控制。</small></label>
    {['next', ...(points.confirmFirst ? ['confirm'] : [])].map(name => {
      const title = name === 'next' ? '下一题' : '提交本题';
      return <div className="tap-point-fields" key={name}><strong>{title}点击位置</strong><div className="swipe-coordinate-grid">
        {['x', 'y'].map(key => <div className="swipe-coordinate" key={key}><label className="field-block"><span>{title} {key.toUpperCase()}（%）</span>
          <input type="number" aria-label={`${title} ${key.toUpperCase()}`} min="0" max="100" step="0.1" value={points[name][key]}
            disabled={disabled} onChange={e => update(name, { [key]: Number(e.target.value) })} /></label>
          <input type="range" aria-label={`${title} ${key.toUpperCase()}滑杆`} min="0" max="100" step="0.1" value={points[name][key]}
            disabled={disabled} onChange={e => update(name, { [key]: Number(e.target.value) })} /></div>)}
      </div><label className="field-block"><span>{title}按下时长（毫秒）</span><input type="number" min="50" max="500" step="10"
        value={points[name].durationMs} disabled={disabled} onChange={e => update(name, { durationMs: Number(e.target.value) })} /></label></div>;
    })}
    <button className="button button-quiet swipe-reset" disabled={disabled} onClick={() => onChange('customClicks', sanitizeCustomClicks(TAP_DEFAULTS))}>恢复点击默认位置</button>
  </div>;
}
