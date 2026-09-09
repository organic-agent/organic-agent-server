/* diagram.js — 노드·엣지 데이터로 흐름도(SVG)를 그린다. 좌표는 노드에만 적고, 화살표는 직각으로 자동 경로.
 *
 * 사용:  <div class="diagram" id="d1"></div>
 *        <script>renderDiagram('#d1', { width, height, groups, nodes, edges })</script>
 *
 * spec.groups[] : { x, y, w, h, label, color, tint }                     단계별 영역 상자
 * spec.large    : 전체 지도용 큰 글자·행 간격. spec.title은 도면의 접근성 이름.
 * spec.nodes[]  : { id, x, y, w, [h], lane, kicker, title, technicalTitle, lines[], href, shape }
 *                 technicalTitle : 원래 코드 식별자. 제목 툴팁과 접근성 이름에 보존.
 *                 lane  : wes | lambda | gpu | browser | db | s3 | warn   (윗줄 색)
 *                 title : 문자열 또는 [줄1, 줄2]
 *                 lines : 상자 안 알약 행. 문자열 배열
 *                 href  : 있으면 클릭 가능(↗ 표시)
 *                 shape : 'box'(기본) | 'db'(원통) | 'actor'(사람)
 *                 h 는 생략하면 내용으로 계산
 * spec.edges[]  : { from, to, [fromSide], [toSide], [fromAt], [toAt], [via], label, [labelAt], [dashed], [color], [bidir] }
 *                 side  : l | r | t | b   (기본 from=r, to=l)
 *                 at    : 0~1, 그 변의 어디에서 나가/들어오나 (기본 0.5)
 *                 via   : [[x,y], …] 경유점. 경유점 사이에 직각 꺾임은 자동으로 넣는다. 좌표에 null 을 주면 직전 점의 값(예: [[300,null]] = 수평으로 300까지)
 *                 labelAt: 라벨을 놓을 세그먼트 번호(0부터). 생략하면 가장 긴 세그먼트. labelDx/labelDy 로 미세 이동
 *                 label 안의 \\n 은 줄바꿈
 *                 color : wes | lambda | gpu | browser | warn   (기본 회색)
 */
(function () {
  const NS = 'http://www.w3.org/2000/svg';
  const LANE_VAR = { wes: '--accent', lambda: '--lambda', gpu: '--gpu', browser: '--browser', db: '--db', s3: '--db', warn: '--warn' };

  function el(name, attrs, parent) {
    const e = document.createElementNS(NS, name);
    for (const k in attrs) if (attrs[k] != null) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function text(parent, x, y, str, attrs) {
    const t = el('text', Object.assign({ x, y }, attrs), parent);
    t.textContent = str;
    return t;
  }

  function nodeHeight(n) {
    if (n.large && !n.h) return 58 + (Array.isArray(n.title) ? n.title.length : 1) * 22 + (n.lines || []).length * 26;
    if (n.h) return n.h;
    const titleLines = Array.isArray(n.title) ? n.title.length : 1;
    const rows = (n.lines || []).length;
    if (n.shape === 'db') return 30 + titleLines * 16 + rows * 19 + 14;
    if (n.shape === 'actor') return 70;
    return 10 + (n.kicker ? 13 : 0) + titleLines * 17 + (rows ? 4 + rows * 19 : 0) + 8;
  }

  function anchor(n, side, at) {
    at = at == null ? 0.5 : at;
    const h = nodeHeight(n);
    switch (side) {
      case 'l': return { x: n.x, y: n.y + h * at };
      case 'r': return { x: n.x + n.w, y: n.y + h * at };
      case 't': return { x: n.x + n.w * at, y: n.y };
      default:  return { x: n.x + n.w * at, y: n.y + h };
    }
  }

  // p0 → via… → p1. 두 점이 대각이면 직전 세그먼트 방향을 이어 꺾는다.
  function route(p0, side0, p1, side1, via) {
    const h0 = side0 === 'l' || side0 === 'r', h1 = side1 === 'l' || side1 === 'r';
    const pts = [p0];
    if (!via || !via.length) {
      if (h0 && h1) { const mx = (p0.x + p1.x) / 2; pts.push({ x: mx, y: p0.y }, { x: mx, y: p1.y }); }
      else if (h0) pts.push({ x: p1.x, y: p0.y });
      else if (h1) pts.push({ x: p0.x, y: p1.y });
      else { const my = (p0.y + p1.y) / 2; pts.push({ x: p0.x, y: my }, { x: p1.x, y: my }); }
      pts.push(p1);
      return dedupe(pts);
    }
    let cur = p0, horizFirst = h0;
    const all = via.map(v => ({ x: v[0], y: v[1] })).concat([p1]);
    // via 의 null 좌표는 '직전 점과 같은 값' — 앵커 소수점에 좌우되지 않게 한다
    for (const nx of all) {
      if (nx.x == null) nx.x = cur.x;
      if (nx.y == null) nx.y = cur.y;
      if (Math.abs(cur.x - nx.x) < 1) nx.x = cur.x;   // 소수점 앵커는 직선으로 맞춘다
      if (Math.abs(cur.y - nx.y) < 1) nx.y = cur.y;
      if (cur.x !== nx.x && cur.y !== nx.y) {
        pts.push(horizFirst ? { x: nx.x, y: cur.y } : { x: cur.x, y: nx.y });
        // 꺾은 뒤 마지막 세그먼트 축은 처음과 반대 → 다음 첫 이동은 처음과 같은 축
      } else {
        horizFirst = !(cur.y === nx.y); // 직전이 수평이면 다음은 수직부터
      }
      pts.push(nx); cur = nx;
    }
    return dedupe(pts);
  }
  function dedupe(pts) {
    const out = [pts[0]];
    for (let i = 1; i < pts.length; i++) { const a = out[out.length - 1], b = pts[i]; if (a.x !== b.x || a.y !== b.y) out.push(b); }
    return out;
  }

  function drawNode(svg, n) {
    const h = nodeHeight(n);
    const laneVar = LANE_VAR[n.lane] || '--line-strong';
    const g = el('g', { class: 'dnode' + (n.href ? ' link' : ''), 'data-id': n.id }, svg);
    let host = g;
    if (n.href) { host = el('a', { href: n.href, 'aria-label': (n.technicalTitle || n.title) + ' 상세 보기' }, g); }
    const description = el('title', {}, host);
    description.textContent = [n.technicalTitle || n.title, ...(n.lines || [])].join(' · ');

    if (n.shape === 'actor') {
      const cx = n.x + n.w / 2;
      el('circle', { cx, cy: n.y + 14, r: 10, class: 'actor' }, host);
      el('path', { d: `M${cx - 16},${n.y + 48} a16,16 0 0 1 32,0 z`, class: 'actor' }, host);
      text(host, cx, n.y + 64, n.title, { class: 'dtitle', 'text-anchor': 'middle' });
      return;
    }
    if (n.shape === 'db') {
      const ry = 9;
      el('path', { d: `M${n.x},${n.y + ry} v${h - 2 * ry} a${n.w / 2},${ry} 0 0 0 ${n.w},0 v-${h - 2 * ry}`, class: 'box' }, host);
      el('ellipse', { cx: n.x + n.w / 2, cy: n.y + ry, rx: n.w / 2, ry, class: 'box top', style: `stroke:var(${laneVar})` }, host);
      let y = n.y + ry + 22;
      const titles = Array.isArray(n.title) ? n.title : [n.title];
      titles.forEach(t => { text(host, n.x + n.w / 2, y, t, { class: 'dtitle', 'text-anchor': 'middle' }); y += 16; });
      y += 2;
      (n.lines || []).forEach(l => { drawRow(host, n.x + 10, y, n.w - 20, l); y += 19; });
      return;
    }
    el('rect', { x: n.x, y: n.y, width: n.w, height: h, rx: 6, class: 'box' }, host);
    el('path', { d: `M${n.x + 6},${n.y} h${n.w - 12} a6,6 0 0 1 6,6 v0 h-${n.w} v0 a6,6 0 0 1 6,-6 z`, style: `fill:var(${laneVar})`, class: 'bar' }, host);
    const step = n.large ? 26 : 19;
    let y = n.y + (n.large ? 18 : 10);
    if (n.kicker) { text(host, n.x + 10, y + 5, n.kicker + (n.href ? '  ↗' : ''), { class: 'dkicker', style: `fill:var(${laneVar})` }); y += n.large ? 20 : 13; }
    else if (n.href) { text(host, n.x + n.w - 8, n.y + 14, '↗', { class: 'dkicker', 'text-anchor': 'end' }); }
    const titles = Array.isArray(n.title) ? n.title : [n.title];
    titles.forEach(t => { y += n.large ? 17 : 13; text(host, n.x + 10, y, t, { class: 'dtitle' }); y += n.large ? 8 : 4; });
    if ((n.lines || []).length) {
      y += 4;
      n.lines.forEach(l => { drawRow(host, n.x + 8, y, n.w - 16, l, n.large); y += step; });
    }
  }
  function drawRow(host, x, y, w, str, large) {
    el('rect', { x, y, width: w, height: large ? 23 : 16, rx: 3, class: 'row' }, host);
    text(host, x + 6, y + (large ? 16 : 11.5), str, { class: 'drow' });
  }

  function drawEdge(svg, labelLayer, e, byId, prefix) {
    const a = byId[e.from], b = byId[e.to];
    if (!a || !b) { console.warn('edge: unknown node', e); return; }
    const s0 = e.fromSide || 'r', s1 = e.toSide || 'l';
    const p0 = anchor(a, s0, e.fromAt), p1 = anchor(b, s1, e.toAt);
    const pts = route(p0, s0, p1, s1, e.via);
    if (e.via && e.via.length) { const q = pts[pts.length - 1], v = pts[pts.length - 2]; if (Math.abs(q.x - v.x) < 1) q.x = v.x; if (Math.abs(q.y - v.y) < 1) q.y = v.y; }
    const d = pts.map((p, i) => (i ? 'L' : 'M') + p.x + ',' + p.y).join(' ');
    const colorVar = LANE_VAR[e.color] || '--edge';
    const g = el('g', { class: 'dedge' + (e.dashed ? ' dashed' : '') }, svg);
    el('path', { d, class: 'edge', style: `stroke:var(${colorVar})`, 'marker-end': `url(#${prefix}-arrow-${colorVar.slice(2)})`, 'marker-start': e.bidir ? `url(#${prefix}-arrowrev-${colorVar.slice(2)})` : null }, g);
    if (!e.label) return;
    // 라벨: 지정 세그먼트 또는 가장 긴 세그먼트의 중점
    let seg = e.labelAt;
    if (seg == null) {
      let best = -1; seg = 0;
      for (let i = 0; i < pts.length - 1; i++) { const len = Math.abs(pts[i + 1].x - pts[i].x) + Math.abs(pts[i + 1].y - pts[i].y); if (len > best) { best = len; seg = i; } }
    }
    seg = Math.min(seg, pts.length - 2);
    const m = { x: (pts[seg].x + pts[seg + 1].x) / 2, y: (pts[seg].y + pts[seg + 1].y) / 2 };
    const lg = el('g', { class: 'dlabel' + (e.dashed ? ' dashed' : '') }, labelLayer);
    m.x += e.labelDx || 0; m.y += e.labelDy || 0;
    const lines = String(e.label).split('\n');
    const bg = el('rect', { rx: 3, class: 'labelbg' }, lg);
    lines.forEach((ln, i) => text(lg, m.x, m.y + 4 + (i - (lines.length - 1) / 2) * 13, ln, { class: 'dlabeltext', 'text-anchor': 'middle' }));
    const bb = lg.getBBox();
    bg.setAttribute('x', bb.x - 4); bg.setAttribute('y', bb.y - 2); bg.setAttribute('width', bb.width + 8); bg.setAttribute('height', bb.height + 4);
  }

  function defs(svg, prefix) {
    const d = el('defs', {}, svg);
    ['edge', 'accent', 'lambda', 'gpu', 'browser', 'db', 'warn'].forEach(v => {
      const mk = el('marker', { id: prefix + '-arrow-' + v, viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 8, markerHeight: 8, orient: 'auto-start-reverse' }, d);
      el('path', { d: 'M0,0 L10,5 L0,10 z', style: `fill:var(--${v})` }, mk);
      const mr = el('marker', { id: prefix + '-arrowrev-' + v, viewBox: '0 0 10 10', refX: 1, refY: 5, markerWidth: 8, markerHeight: 8, orient: 'auto' }, d);
      el('path', { d: 'M10,0 L0,5 L10,10 z', style: `fill:var(--${v})` }, mr);
    });
  }

  let diagramCount = 0;
  window.renderDiagram = function (sel, spec) {
    const host = typeof sel === 'string' ? document.querySelector(sel) : sel;
    host.classList.add('diagram');
    if (spec.large) host.classList.add('overview');
    const prefix = 'diagram-' + (++diagramCount);
    const toolbar = document.createElement('div');
    toolbar.className = 'diagram-toolbar';
    const hint = document.createElement('span');
    hint.className = 'hint';
    hint.textContent = '박스 클릭 → 상세 보기 · 가로로 스크롤해 전체 흐름을 확인하세요';
    toolbar.appendChild(hint);
    const viewport = document.createElement('div');
    viewport.className = 'diagram-viewport';
    viewport.tabIndex = 0;
    viewport.setAttribute('aria-label', '아키텍처 도면. 가로·세로 스크롤 가능');
    host.append(toolbar, viewport);
    const svg = el('svg', { viewBox: `0 0 ${spec.width} ${spec.height}`, width: spec.width, height: spec.height, role: 'group', 'aria-label': spec.title || '분석 파이프라인 아키텍처' }, viewport);
    const title = el('title', {}, svg);
    title.textContent = spec.title || '분석 파이프라인 아키텍처';
    defs(svg, prefix);
    let zoom = spec.large ? 1 : 1.25;
    let fitting = false;
    const output = document.createElement('output');
    output.setAttribute('aria-live', 'polite');
    function resize() {
      const padding = parseFloat(getComputedStyle(viewport).paddingLeft) * 2;
      const scale = fitting ? Math.min(1.5, (viewport.clientWidth - padding) / spec.width) : zoom;
      svg.style.width = Math.round(spec.width * scale) + 'px';
      output.textContent = Math.round(scale * 100) + '%';
    }
    function button(label, action, aria) {
      const b = document.createElement('button');
      b.type = 'button'; b.textContent = label;
      b.setAttribute('aria-label', aria || label);
      b.addEventListener('click', action);
      toolbar.appendChild(b);
    }
    button('−', () => { fitting = false; zoom = Math.max(.5, zoom - .25); resize(); }, '도면 축소');
    toolbar.appendChild(output);
    button('+', () => { fitting = false; zoom = Math.min(2, zoom + .25); resize(); }, '도면 확대');
    button('읽기 크기', () => { fitting = false; zoom = spec.large ? 1 : 1.25; resize(); });
    button('전체 보기', () => { fitting = true; resize(); });
    if (window.ResizeObserver) new ResizeObserver(resize).observe(viewport);
    resize();
    (spec.groups || []).forEach(gr => {
      const g = el('g', { class: 'dgroup' }, svg);
      el('rect', { x: gr.x, y: gr.y, width: gr.w, height: gr.h, rx: 16, class: 'group', style: gr.color ? 'stroke:' + gr.color + ';fill:' + gr.tint : null }, g);
      if (gr.label) text(g, gr.x + 10, gr.y + (spec.large ? 30 : 16), gr.label, { class: 'dgrouplabel', style: gr.color ? 'fill:' + gr.color : null });
    });
    const byId = {};
    spec.nodes.forEach(n => { n.large = spec.large; byId[n.id] = n; });
    const edgeLayer = el('g', { class: 'edges' }, svg);
    const nodeLayer = el('g', { class: 'nodes' }, svg);
    const labelLayer = el('g', { class: 'labels' }, svg);
    spec.nodes.forEach(n => drawNode(nodeLayer, n));
    (spec.edges || []).forEach(e => drawEdge(edgeLayer, labelLayer, e, byId, prefix));
    (spec.notes || []).forEach(nt => text(svg, nt.x, nt.y, nt.text, { class: 'dnote' }));
  };
})();
