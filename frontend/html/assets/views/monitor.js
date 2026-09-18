/* ============================================================
   定位监控（干警/监管员侧）
   - 总览：每 5 秒轮询，对象实时定位/设备状态/三态/双口径完成度
   - 详情：SVG 画多边形活动范围/禁区 + 回退圆围栏、轨迹连线、漂移丢弃点、
     当前位置、人工标记；周/月两视图；落点越界二次确认填原因留痕；轨迹清除留痕
   - 时间铁律：后端时间一律 UTC（Z 结尾），这里只按对象所在司法所时区显示，
     不拿浏览器时区或服务器本地时区做日界判断
   ============================================================ */
(function (global) {
  const Views = global.Views || (global.Views = {});
  const POLL_MS = 5000;

  const DEVICE_BADGE = {
    NORMAL: ['green', '🔋 设备正常'],
    LOW_BATTERY: ['amber', '🪫 低电量'],
    NO_SIGNAL: ['red', '📵 信号中断'],
    POWER_OFF: ['red', '⭕ 腕表关机'],
    NONE: ['gray', '❔ 无回传'],
  };

  function fmtAge(sec) {
    if (sec == null) return '—';
    if (sec < 60) return Math.max(0, Math.round(sec)) + ' 秒前';
    if (sec < 3600) return Math.round(sec / 60) + ' 分钟前';
    return (sec / 3600).toFixed(1) + ' 小时前';
  }

  function badge(cls, html) {
    return `<span class="badge ${cls}">${html}</span>`;
  }

  // 视图停止钩子在视图首次运行时赋值（此处 Views.monitor 尚为 undefined，不能提前挂属性）
  let stopMonitorTimer = null;
  let stopDetailTimer = null;
  function stopAllTimers() {
    if (stopMonitorTimer) { stopMonitorTimer(); stopMonitorTimer = null; }
    if (stopDetailTimer) { stopDetailTimer(); stopDetailTimer = null; }
  }
  Views.monitorStopAll = stopAllTimers;

  // ============================================================
  // 总览
  // ============================================================
  Views.monitor = async function (root) {
    stopAllTimers();
    const session = Api.getSession();
    let officeFilter = '';
    let timer = null;
    let first = true;
    let failedOnce = false;

    root.innerHTML = `
      <div class="page-head">
        <h2>🛰️ 定位监控</h2>
        <div class="desc">腕表每 5 秒回传 GPS 与设备状态；轨迹按 UTC 存储、按司法所所在时区显示与判定日界，
          跨司法所/夏令时不错算。</div>
      </div>
      <div class="mon-toolbar card-flat">
        <div class="live-pill" id="live-pill"><span class="pulse-dot"></span><span id="live-text">连接中…</span></div>
        <select class="input sm" id="f-office" style="margin-left:auto"></select>
        <button class="btn sm" id="btn-refresh">立即刷新</button>
      </div>
      <div id="mon-stats"></div>
      <div class="card"><div id="mon-list" class="skeleton">监控数据加载中…</div></div>`;

    const listEl = root.querySelector('#mon-list');
    const statsEl = root.querySelector('#mon-stats');
    const pillText = root.querySelector('#live-text');
    const officeSel = root.querySelector('#f-office');

    officeSel.onchange = () => { officeFilter = officeSel.value; load(true); };
    root.querySelector('#btn-refresh').onclick = () => load(true);

    function renderOffices(offices) {
      const showAll = session.role === 'SUPERVISOR' && offices.length > 1;
      officeSel.innerHTML = (showAll ? '<option value="">全部司法所</option>' : '')
        + offices.map((o) => `<option value="${o.id}">${UI.esc(o.name)}（UTC${UI.esc(o.zoneOffset)}）</option>`).join('');
      officeSel.style.display = offices.length > 1 ? '' : 'none';
    }

    function renderStats(d) {
      const c = d.completion;
      const forbiddenNow = d.subjects.filter((s) => s.todayForbiddenDay).length;
      const breaching = d.subjects.filter((s) => s.lastInsideFence === false).length;
      const abnormal = d.subjects.filter((s) => ['NO_SIGNAL', 'POWER_OFF'].includes(s.deviceStatus)).length;
      statsEl.innerHTML = `
        <div class="stat-row">
          <div class="stat-tile"><div class="lab">监控对象（在矫/请假/训诫）</div><div class="num">${c.subjects}</div></div>
          <div class="stat-tile critical"><div class="lab">当前越界</div><div class="num">${breaching}</div></div>
          <div class="stat-tile warn"><div class="lab">今日禁行日对象</div><div class="num">${forbiddenNow}</div></div>
          <div class="stat-tile warn"><div class="lab">设备异常（关机/无信号）</div><div class="num">${abnormal}</div></div>
        </div>
        <div class="caliber-bar" title="${UI.esc(c.caliberNote)}">
          <b>在矫完成度（双口径并列，口径不同结论可能相反）：</b>
          口径① 已打卡天数 <b>${c.checkinPercent}%</b>
          （${c.checkinDaysSum}/${c.servingDaysSum} 天）　·
          口径② 关键报到 <b>${c.keyPercent}%</b>
          （${c.keyCheckinsSum}/${c.keyCheckinsDueSum} 次）
          <span class="caliber-why">⓵ 为什么两个口径都要报？</span>
        </div>`;
      statsEl.querySelector('.caliber-why').onclick = () => UI.alertModal('完成度为什么有两个口径',
        `<div style="font-size:13.5px;line-height:1.8">${UI.esc(c.caliberNote)}<br/><br/>
         界面所有完成度数字旁均标注所用口径，处置以「关键报到」口径为准。</div>`, '📐');
    }

    function trackStateChip(s) {
      if (s === 'ACTIVE') return badge('green', '轨迹正常');
      if (s === 'CLEARED') return badge('gray', '轨迹已清除');
      return badge('gray', '从未上报');
    }

    function renderTable(rows) {
      const filtered = officeFilter ? rows.filter((r) => String(r.officeId) === officeFilter) : rows;
      if (!filtered.length) {
        listEl.innerHTML = `<div class="state-box"><div class="ico">🗂️</div>
          <h3>数据范围内暂无监控对象</h3>
          <p>仅监控「在矫 / 请假外出 / 训诫」状态的对象；入矫登记、收监、解除对象不在本页。</p></div>`;
        return;
      }
      const showOffice = session.role === 'SUPERVISOR';
      listEl.innerHTML = `
        <div class="table-wrap">
        <table class="data mon-table">
          <thead><tr>
            <th>对象</th>${showOffice ? '<th>司法所 / 时区</th>' : ''}
            <th>当前定位（司法所当地时间）</th><th>设备状态</th><th>禁行日</th>
            <th>完成度①打卡天数</th><th>完成度②关键报到</th><th>轨迹</th><th></th>
          </tr></thead>
          <tbody>
            ${filtered.map((s) => {
              const dev = DEVICE_BADGE[s.deviceStatus] || DEVICE_BADGE.NONE;
              const loc = !s.lastPointUtc
                ? '<span class="muted">尚无定位</span>'
                : `<div class="${s.stale ? 'loc-stale' : ''}">${UI.esc(UI.tzText(s.lastPointUtc, s.zoneId))}
                     <span class="age">（${fmtAge(UI.tzAgeSec(s.lastPointUtc))}）</span></div>
                   <div style="margin-top:2px">${s.lastInsideFence === false
                     ? badge('red', '📍 越界') : badge('green', '围栏内')}
                     ${s.stale ? badge('amber', '长时间无回传') : ''}</div>`;
              const diverge = s.caliberDiverge
                ? ' class="caliber-diverge" title="两口径结论相反：零散报到撑高天数，关键报到一次未成"' : '';
              return `
              <tr class="clickable" data-id="${s.id}">
                <td data-label="对象"><b>${UI.esc(s.maskedName)}</b>
                  <div class="muted small">${UI.esc(s.correctionNo)}</div>${UI.statusBadge(s.status)}</td>
                ${showOffice ? `<td data-label="司法所">${UI.esc(s.officeName)}
                  <div class="muted small">UTC${UI.esc(tzOffset(s.zoneId))}</div></td>` : ''}
                <td data-label="当前定位">${loc}</td>
                <td data-label="设备状态">${badge(dev[0], dev[1])}
                  ${s.batteryPercent != null ? `<div class="muted small">电量 ${s.batteryPercent}%</div>` : ''}</td>
                <td data-label="禁行日">${s.todayForbiddenDay
                  ? badge('red', '今日禁行') : '<span class="muted">—</span>'}</td>
                <td data-label="完成度①"${diverge}><b>${s.checkinPercent}%</b>
                  <div class="muted small">${s.checkinDays} 天</div></td>
                <td data-label="完成度②"${diverge}>
                  <b class="${s.caliberDiverge ? 'pct-bad' : ''}">${s.keyPercent}%</b>
                  <div class="muted small">${s.keyCheckins}/${s.keyCheckinsDue} 次</div></td>
                <td data-label="轨迹">${trackStateChip(s)}</td>
                <td data-label="操作"><button class="btn sm primary" data-btn="mon">看轨迹</button></td>
              </tr>`;
            }).join('')}
          </tbody>
        </table></div>
        <div class="mon-footnote">口径①＝矫正期已报到天数÷已过天数；口径②＝规定报到星期实到次数÷到期应到次数。
          黄底行为两口径背离对象。全部判定按司法所时区。</div>`;
      listEl.querySelectorAll('tr[data-id]').forEach((tr) => {
        tr.onclick = () => { location.hash = '#/monitor/' + tr.dataset.id; };
      });
    }

    async function load(manual) {
      try {
        const d = await Api.get('/monitor/overview');
        failedOnce = false;
        if (first || officeSel.options.length === 0) { renderOffices(d.offices); first = false; }
        renderStats(d);
        renderTable(d.subjects);
        pillText.textContent = '实时 · 每 5 秒自动刷新 · 服务端 ' + UI.tzText(d.serverNowUtc, 'UTC');
        root.querySelector('#live-pill').className = 'live-pill ok';
      } catch (e) {
        failedOnce = true;
        pillText.textContent = '刷新失败：' + e.message + '（' + POLL_MS / 1000 + 's 后重试）';
        root.querySelector('#live-pill').className = 'live-pill bad';
        if (first || manual) {
          listEl.innerHTML = failureHtml('定位监控加载失败', e.message);
          const btn = listEl.querySelector('#btn-retry-fail');
          if (btn) btn.onclick = () => load(true);
          statsEl.innerHTML = '';
        }
      } finally {
        first = false;
      }
    }

    await load(false);
    timer = setInterval(() => {
      // 视图已卸载（路由切走）时自我了断，不在后台空轮询
      if (!root.isConnected) { clearInterval(timer); timer = null; return; }
      load(false);
    }, POLL_MS);
    stopMonitorTimer = () => { if (timer) clearInterval(timer); };
  };

  // ============================================================
  // 单对象轨迹详情
  // ============================================================
  Views.monitorDetail = async function (root, id) {
    stopAllTimers();
    let range = 'WEEK';
    let liveTimer = null;
    let trackTimer = null;
    let liveTicks = 0;
    let mapCtx = null;
    let data = null;

    async function boot() {
      if (liveTimer) { clearInterval(liveTimer); liveTimer = null; }
      if (trackTimer) { clearInterval(trackTimer); trackTimer = null; }
      mapCtx = null;
      root.innerHTML = `<div class="skeleton">轨迹加载中…</div>`;
      try {
        data = await Api.get(`/monitor/${id}?range=${range}`);
      } catch (e) {
        if (e.code === 'FORBIDDEN' || e.status === 403) {
          root.innerHTML = forbiddenHtml(e.message);
          root.querySelector('#btn-back').onclick = () => { location.hash = '#/monitor'; };
          return;
        }
        root.innerHTML = failureHtml('轨迹监控加载失败', e.message);
        const btn = root.querySelector('#btn-retry-fail');
        if (btn) btn.onclick = boot;
        return;
      }
      render();
      startLive();
    }

    function startLive() {
      if (liveTimer) clearInterval(liveTimer);
      liveTicks = 0;
      liveTimer = setInterval(() => {
        if (!root.isConnected) { clearInterval(liveTimer); liveTimer = null; return; }
        pollLive();
      }, POLL_MS);
      // 轨迹线每 15 秒静默重拉一次（月视图点多，不随 5 秒心跳重传/重绘）
      if (trackTimer) clearInterval(trackTimer);
      trackTimer = setInterval(() => {
        if (!root.isConnected) { clearInterval(trackTimer); trackTimer = null; return; }
        silentRefreshTracks();
      }, POLL_MS * 3);
      stopDetailTimer = () => {
        if (liveTimer) clearInterval(liveTimer);
        if (trackTimer) clearInterval(trackTimer);
      };
    }

    async function pollLive() {
      try {
        const live = await Api.get(`/monitor/live/${id}`);
        const cur = live.current || {};
        const next = Object.keys(cur).length ? cur : null;
        const prevHad = !!data.current;
        const nextHas = !!next;
        data.current = next;
        data.todayForbiddenDay = live.todayForbiddenDay;
        // 空态发生变化（首次回传 / 被清除）必须重绘整张图，普通心跳只移动当前点
        if (prevHad !== nextHas || live.trackState !== data.trackState) {
          data.trackState = live.trackState;
          render();
          startLive();
          return;
        }
        patchLive();
      } catch { /* 单次心跳失败不打断看图，下个周期重试 */ }
    }

    // 静默重拉轨迹并更新线/点/标记，不动 DOM 其余部分、不打断落点点击
    async function silentRefreshTracks() {
      try {
        const fresh = await Api.get(`/monitor/${id}?range=${range}`);
        data.tracks = fresh.tracks;
        data.dropped = fresh.dropped;
        data.marks = fresh.marks;
        renderMap();
        renderMarks();
      } catch { /* 静默失败，下个周期再试 */ }
    }

    async function switchRange(r) {
      range = r;
      root.querySelector('#map-slot').innerHTML = '<div class="skeleton">轨迹加载中…</div>';
      try {
        data = await Api.get(`/monitor/${id}?range=${range}`);
        render();
        startLive();
      } catch (e) {
        root.querySelector('#map-slot').innerHTML = inlineFail('该视图轨迹加载失败：' + e.message);
      }
    }

    function render() {
      const s = data.subject;
      const forbiddenBadge = data.todayForbiddenDay
        ? badge('red', '⛔ 今日为司法所规定禁行日（每周' + UI.esc(data.forbiddenWeekdayLabel) + '）')
        : (data.forbiddenWeekdayLabel
            ? badge('gray', '禁行日：每周' + UI.esc(data.forbiddenWeekdayLabel)) : '');

      root.innerHTML = `
        <div class="page-head mon-head">
          <button class="btn sm" id="btn-back">← 返回监控列表</button>
          <h2 style="margin:0">🛰️ ${UI.esc(s.maskedName)}</h2>
          <span class="muted">${UI.esc(s.correctionNo)}</span>
          ${UI.statusBadge(s.status)} ${forbiddenBadge}
          <span style="flex:1"></span>
          <span class="muted small">${UI.esc(s.officeName)} · 时区 ${UI.esc(data.zoneId)}
            （UTC${UI.esc(data.zoneOffset)}）· 当地 ${UI.esc(data.localToday)} ${UI.esc(data.localWeekday)}</span>
        </div>

        <div id="current-slot">${currentHtml(data)}</div>

        <div class="mon-range-bar">
          <div class="seg">
            <button class="seg-btn ${range === 'WEEK' ? 'on' : ''}" data-range="WEEK">周视图（当地自然周）</button>
            <button class="seg-btn ${range === 'MONTH' ? 'on' : ''}" data-range="MONTH">月视图（当地自然月）</button>
          </div>
          <div class="muted small">窗口 ${UI.esc(UI.tzText(data.windowStartUtc, data.zoneId))}
            ～ ${UI.esc(UI.tzText(data.windowEndUtc, data.zoneId))}（司法所当地时间，已含夏令时规则）</div>
          <span style="flex:1"></span>
          <button class="btn sm" id="btn-clear">🗑 清除本对象历史轨迹</button>
        </div>

        <div class="card" id="map-card">
          <div class="card-title">活动轨迹与电子围栏
            <span class="sub">绿框＝规定活动范围　红框＝禁区　蓝线＝采信轨迹　灰✕＝GPS漂移丢弃　🚩＝人工越界标记</span>
          </div>
          <div id="map-slot"></div>
          <div id="map-legend" class="map-legend"></div>
        </div>

        <div class="mon-bottom">
          <div class="card" id="completion-slot"></div>
          <div class="card" id="marks-slot"></div>
        </div>`;

      root.querySelector('#btn-back').onclick = () => { location.hash = '#/monitor'; };
      root.querySelectorAll('.seg-btn').forEach((b) => {
        b.onclick = () => { if (b.dataset.range !== range) switchRange(b.dataset.range); };
      });
      root.querySelector('#btn-clear').onclick = onClear;

      renderMap();
      renderCompletion();
      renderMarks();
    }

    function currentHtml(d) {
      const c = d.current;
      if (!c) {
        return `<div class="card current-card"><div class="cur-row">
          <span class="cur-ico">❔</span><b>当前无有效定位</b>
          <span class="muted">腕表应每 5 秒回传；从未回传或轨迹被清除后显示此态。</span></div></div>`;
      }
      const inside = c.inside;
      const dev = DEVICE_BADGE[c.deviceStatus] || DEVICE_BADGE.NONE;
      return `<div class="card current-card ${inside ? '' : 'cur-breach'}">
        <div class="cur-row">
          <span class="cur-ico">${inside ? '✅' : '📍'}</span>
          <div>
            <div class="cur-title">${inside ? '在规定活动范围内' : UI.esc(c.verdictLabel || '越界')}</div>
            <div class="muted small">最近回传 <b>${UI.esc(c.localTime)}</b>（司法所当地时间）· ${fmtAge(c.ageSeconds)}
              ${c.stale ? badge('amber', '超过 5 分钟无回传，疑似设备异常') : ''}</div>
          </div>
          <span style="flex:1"></span>
          ${badge(dev[0], dev[1])}
          ${c.batteryPercent != null ? badge(c.batteryPercent < 20 ? 'amber' : 'gray', '🔋 ' + c.batteryPercent + '%') : ''}
          <span class="muted small mono">${c.lat.toFixed(5)}, ${c.lng.toFixed(5)}</span>
        </div></div>`;
    }

    // ---------- SVG 地图 ----------
    function renderMap() {
      const slot = root.querySelector('#map-slot');
      if (!slot) return;

      // 三种空态分开，绝不笼统“暂无数据”
      if (data.trackState === 'EMPTY') {
        slot.innerHTML = `<div class="state-box map-empty">
          <div class="ico">📭</div><h3>该对象暂无轨迹</h3>
          <p>系统从未收到该腕表的有效回传。请核对：①腕表是否已配发开机；②设备号是否绑定本档案；
            ③对象是否处于信号盲区（无信号时设备会缓存、恢复后补传）。</p>
          <p class="muted small">注意：本态与“轨迹已清除”不同——没有任何清除记录，也没有历史点。</p>
          <button class="btn primary" id="btn-retry-empty">重新检查</button>
        </div>`;
        slot.querySelector('#btn-retry-empty').onclick = () => switchRange(range);
        root.querySelector('#map-legend').innerHTML = '';
        return;
      }
      if (data.trackState === 'CLEARED' && !data.tracks.length) {
        const lc = data.lastClear;
        slot.innerHTML = `<div class="state-box map-empty">
          <div class="ico">🧹</div><h3>轨迹已清除（非“从未上报”）</h3>
          <p>该对象的历史轨迹已按工作流程清除，清除动作留有记录：</p>
          <div class="clear-record">
            <div><b>清除时间：</b>${lc ? UI.esc(lc.clearedAtLocal) + '（司法所当地时间）' : '—'}</div>
            <div><b>操作干警：</b>${lc ? UI.esc(lc.operatorUserName) : '—'}</div>
            <div><b>清除点数：</b>${lc ? lc.pointCount : 0} 个有效轨迹点</div>
            <div><b>清除原因：</b>${lc ? UI.esc(lc.reason) : '—'}</div>
          </div>
          <p class="muted small">清除后若腕表继续回传，新轨迹会从当前时刻重新累积；漂移丢弃的质量记录仍保留可查。</p>
          <button class="btn primary" id="btn-retry-cleared">重新检查</button>
        </div>`;
        slot.querySelector('#btn-retry-cleared').onclick = () => switchRange(range);
        root.querySelector('#map-legend').innerHTML = '';
        return;
      }
      if (!data.tracks.length) {
        // 有轨迹但不在本周/本月窗口：不是错误、也不是无轨迹
        slot.innerHTML = `<div class="state-box map-empty">
          <div class="ico">🗓️</div><h3>当前${range === 'WEEK' ? '周' : '月'}窗口内没有落点</h3>
          <p>对象有历史轨迹，但司法所当地 ${range === 'WEEK' ? '本周一至今' : '本月 1 日至今'} 没有回传点。
            可切换到${range === 'WEEK' ? '月' : '周'}视图查看更早轨迹。</p></div>`;
        root.querySelector('#map-legend').innerHTML = '';
        return;
      }

      const map = buildMapSvg(data);
      mapCtx = map;
      slot.innerHTML = map.svg;
      root.querySelector('#map-legend').innerHTML = map.legend;

      slot.querySelectorAll('[data-pt]').forEach((el) => {
        el.onclick = () => onPointMark(el.dataset.pt, el.dataset.outside === 'true', el.dataset.time);
      });
    }

    // 5 秒心跳：只更新状态卡与当前点位置，不重画整张图（避免打断落点点击）
    function patchLive() {
      const slot = root.querySelector('#current-slot');
      if (slot) slot.innerHTML = currentHtml(data);
      if (!mapCtx) { renderMap(); return; }
      const marker = root.querySelector('#cur-marker');
      const c = data.current;
      if (!marker || !c) { renderMap(); return; }
      marker.setAttribute('transform',
        `translate(${mapCtx.xOf(c.lng).toFixed(1)},${mapCtx.yOf(c.lat).toFixed(1)})`);
    }

    function onPointMark(ptId, outside, localTime) {
      if (!outside) {
        UI.toast('该落点在规定活动范围内，无需标记越界；如坐标有误请核对手表定位', 'warn');
        return;
      }
      UI.confirmModal({
        title: '标记该落点为越界',
        icon: '🚩',
        warn: '标记越界会生成违规红点并通知处置流程，必须如实填写原因；服务端将独立复核该点确在活动范围外。',
        bodyHtml: `落点时间：<b>${UI.esc(localTime)}</b>（司法所当地时间）<br/>
          确认该落点属于擅自越界，而不是 GPS 漂移/离线补传延迟？`,
        reasonLabel: '越界标记原因',
        reasonPlaceholder: '如：电话联系本人确认擅自进入禁区，责令立即返回',
        requireReason: true,
        confirmText: '确认标记并留痕',
        danger: true,
      }).then(async (reason) => {
        try {
          await Api.post(`/monitor/${id}/mark-breach`, { trackPointId: Number(ptId), reason });
          UI.toast('已标记越界并留痕，违规红点已生成', 'success');
          data = await Api.get(`/monitor/${id}?range=${range}`);
          render(); startLive();
        } catch (e) {
          if (e.code === 'MARK_INSIDE') {
            UI.alertModal('服务端复核未通过', UI.esc(e.message), '⛔');
          } else if (e.code === 'MARK_DUPLICATE') {
            UI.toast('该落点已标记过，请勿重复标记', 'warn');
          } else {
            UI.toast(e.message, 'error');
          }
        }
      }).catch(() => {});
    }

    async function onClear() {
      let reason;
      try {
        reason = await UI.confirmModal({
          title: '清除该对象的历史轨迹',
          icon: '🧹',
          warn: '清除后图面不再显示历史落点（设备继续回传会重新累积）；本次清除会记录操作人、原因、时间与点数，接受审计。',
          bodyHtml: `对象 <b>${UI.esc(data.subject.maskedName)}</b>，当前视图范围清除的是<b>全部有效轨迹点</b>。`,
          reasonLabel: '清除工作原因',
          reasonPlaceholder: '如：阶段性归档、档案核查需要，所领导已口头审批',
          requireReason: true,
          confirmText: '确认清除并留痕',
          danger: true,
        });
      } catch { return; }
      try {
        const res = await Api.post(`/monitor/${id}/clear-tracks`, { reason });
        UI.toast(res.message || '轨迹已清除并留痕', 'success');
        await boot();
      } catch (e) {
        UI.toast(e.message, 'error');
      }
    }

    function renderCompletion() {
      const c = data.completion;
      const slot = root.querySelector('#completion-slot');
      slot.innerHTML = `
        <div class="card-title">📐 在矫完成度（两个口径并列，界面标注口径）
          <span class="sub">防止“只在非规定日报到”的部分到对象被单口径美化</span></div>
        <div class="caliber-grid ${c.caliberDiverge ? 'diverge' : ''}">
          <div class="caliber-item">
            <div class="cal-name">口径① 已打卡天数 <span class="cal-tag">按天</span></div>
            <div class="cal-num">${c.checkinPercent}%</div>
            <div class="muted small">已报到 ${c.checkinDays} 天 / 矫正期已过 ${c.servingDays} 天
              （当天多次报到只计 1 天）</div>
          </div>
          <div class="caliber-item key">
            <div class="cal-name">口径② 已完成关键报到 <span class="cal-tag">按规定报到日</span></div>
            <div class="cal-num ${c.keyPercent === 0 ? 'pct-bad' : ''}">${c.keyPercent}%</div>
            <div class="muted small">已完成 ${c.keyCheckins} 次 / 到期应完成 ${c.keyCheckinsDue} 次
              （处置以本口径为准）</div>
          </div>
        </div>
        ${c.caliberDiverge ? `<div class="caliber-warn">⚠️ 两口径结论相反：该对象零散打卡撑起口径①，
          但规定报到日一次未完成（口径②为 0），不得按口径①认定其表现良好。</div>` : ''}
        <div class="caliber-note">${UI.esc(c.caliberNote)}</div>`;
    }

    function renderMarks() {
      const slot = root.querySelector('#marks-slot');
      const marks = data.marks || [];
      slot.innerHTML = `
        <div class="card-title">🚩 越界标记留痕（本窗口 ${marks.length} 条）
          <span class="sub">点击轨迹上的<b>越界落点</b>发起标记，需二次确认填原因</span></div>
        ${marks.length ? marks.map((m) => `
          <div class="violation-item">
            <span style="margin-top:2px">🚩</span>
            <div class="v-body">
              <div class="v-detail">${UI.esc(m.localTime)} · ${badge('red', UI.esc(m.verdictReason))}</div>
              <div class="v-meta">标记人 ${UI.esc(m.markerUserName)} · ${UI.esc(m.markedAtLocal)}
                （当地）<br/>原因：${UI.esc(m.reason)}</div>
            </div>
          </div>`).join('') : '<div class="muted small">本窗口暂无人工越界标记。</div>'}`;
    }

    boot();
  };

  // ============================================================
  // SVG 地图绘制（纯函数式拼装，等距圆柱投影）
  // ============================================================
  function buildMapSvg(d) {
    const W = 920, H = 480, PAD = 46;
    const circles = [];
    const polys = [];

    (d.fences || []).forEach((f) => polys.push(f));
    // 没有任何多边形时画回退圆围栏；有多边形也画出中心参考点（淡圆）
    const c = d.circleFence;
    circles.push({ lat: c.centerLat, lng: c.centerLng, r: c.radiusMeters,
      fallback: !(d.fences || []).some((f) => f.kind === 'ALLOW') });

    const pts = d.tracks;
    const dropped = d.dropped || [];
    const marks = d.marks || [];
    const cur = d.current;

    // 经纬度边界
    let minLat = Infinity, maxLat = -Infinity, minLng = Infinity, maxLng = -Infinity;
    const eat = (lat, lng) => {
      minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
      minLng = Math.min(minLng, lng); maxLng = Math.max(maxLng, lng);
    };
    polys.forEach((f) => f.ring.forEach(([la, ln]) => eat(la, ln)));
    // 回退圆：用半径近似四角
    if (circles.some((x) => x.fallback)) {
      const x = circles.find((z) => z.fallback);
      const dLat = x.r / 111320;
      const dLng = x.r / (111320 * Math.cos(Math.PI * x.lat / 180));
      eat(x.lat - dLat, x.lng - dLng); eat(x.lat + dLat, x.lng + dLng);
    }
    pts.forEach((p) => eat(p.lat, p.lng));
    dropped.forEach((p) => eat(p.lat, p.lng));
    marks.forEach((m) => eat(m.lat, m.lng));
    if (cur) eat(cur.lat, cur.lng);
    // 至少包含司法所中心
    eat(c.centerLat, c.centerLng);
    if (!isFinite(minLat)) { minLat = c.centerLat - 0.01; maxLat = c.centerLat + 0.01;
      minLng = c.centerLng - 0.01; maxLng = c.centerLng + 0.01; }
    // 边距比例
    const padLat = Math.max((maxLat - minLat) * 0.12, 0.0006);
    const padLng = Math.max((maxLng - minLng) * 0.12, 0.0006);
    minLat -= padLat; maxLat += padLat; minLng -= padLng; maxLng += padLng;

    const midLat = (minLat + maxLat) / 2;
    const xOf = (lng) => PAD + (lng - minLng) / (maxLng - minLng) * (W - 2 * PAD);
    const yOf = (lat) => H - PAD - (lat - minLat) / (maxLat - minLat) * (H - 2 * PAD);
    void midLat;

    const E = [];
    // 网格底色
    E.push(`<rect x="0" y="0" width="${W}" height="${H}" rx="10" class="map-bg"/>`);

    // 回退圆形围栏
    circles.forEach((cc) => {
      const metersPerPxY = (maxLat - minLat) * 111320 / (H - 2 * PAD);
      const rr = cc.r / metersPerPxY;
      E.push(`<circle cx="${xOf(cc.lng).toFixed(1)}" cy="${yOf(cc.lat).toFixed(1)}" r="${rr.toFixed(1)}"
        class="fence-circle ${cc.fallback ? '' : 'ref'}" />`);
    });

    // 多边形
    polys.forEach((f) => {
      const ptsStr = f.ring.map(([la, ln]) => `${xOf(ln).toFixed(1)},${yOf(la).toFixed(1)}`).join(' ');
      const cls = f.kind === 'FORBIDDEN' ? 'fence-danger' : 'fence-allow';
      E.push(`<polygon points="${ptsStr}" class="${cls}"><title>${UI.esc(f.kindLabel)}：${UI.esc(f.name)}</title></polygon>`);
      // 名称标注取首顶点
      const [la0, ln0] = f.ring[0];
      E.push(`<text x="${xOf(ln0).toFixed(1)}" y="${(yOf(la0) - 6).toFixed(1)}"
        class="fence-label ${cls}">${UI.esc(f.name)}</text>`);
    });

    // 轨迹连线（采信点）
    if (pts.length > 1) {
      const line = pts.map((p) => `${xOf(p.lng).toFixed(1)},${yOf(p.lat).toFixed(1)}`).join(' ');
      E.push(`<polyline points="${line}" class="track-line" />`);
    }
    // 采信点：圆=实时，方=离线补传；红=越界，蓝=内
    pts.forEach((p, i) => {
      const cx = xOf(p.lng).toFixed(1), cy = yOf(p.lat).toFixed(1);
      const cls = (p.outside ? 'pt-out' : 'pt-in') + (p.offline ? ' pt-offline' : '');
      const shape = p.offline
        ? `<rect x="${cx - 3.4}" y="${cy - 3.4}" width="6.8" height="6.8" rx="1.2"
             data-pt="${p.id}" data-outside="${p.outside}" data-time="${UI.esc(p.localTime)}"
             class="${cls}" ><title>${UI.esc(p.localTime)} 离线补传${p.outside ? '·越界（点击可标记）' : '·围栏内'}</title></rect>`
        : `<circle cx="${cx}" cy="${cy}" r="3.4" data-pt="${p.id}" data-outside="${p.outside}"
             data-time="${UI.esc(p.localTime)}" class="${cls}">
             <title>${UI.esc(p.localTime)} 实时${p.outside ? '·越界（点击可标记）' : '·围栏内'}</title></circle>`;
      void i;
      E.push(shape);
    });
    // 漂移丢弃点：灰 ✕
    dropped.forEach((p) => {
      const cx = xOf(p.lng), cy = yOf(p.lat);
      E.push(`<g class="pt-dropped"><line x1="${cx - 4}" y1="${cy - 4}" x2="${cx + 4}" y2="${cy + 4}"/>
        <line x1="${cx - 4}" y1="${cy + 4}" x2="${cx + 4}" y2="${cy - 4}">
        <title>GPS漂移已丢弃 ${UI.esc(p.localTime)}：${UI.esc(p.reason)}</title></line></g>`);
    });
    // 人工标记：橙旗
    marks.forEach((m) => {
      const cx = xOf(m.lng), cy = yOf(m.lat);
      E.push(`<g class="pt-mark" transform="translate(${cx.toFixed(1)},${(cy - 2).toFixed(1)})">
        <path d="M0,0 L0,-13 L10,-10 L0,-7 Z"><title>人工标记越界 ${UI.esc(m.localTime)}：${UI.esc(m.reason)}</title></path></g>`);
    });
    // 当前位置：脉冲圆 + 白边深色点；整体一个变换组，心跳只更新 transform
    if (cur) {
      const cx = xOf(cur.lng).toFixed(1), cy = yOf(cur.lat).toFixed(1);
      E.push(`<g id="cur-marker" transform="translate(${cx},${cy})">
        <circle r="9" class="cur-pulse ${cur.inside ? '' : 'breach'}"/>
        <circle r="5" class="cur-dot ${cur.inside ? '' : 'breach'}">
          <title>当前位置 ${UI.esc(cur.localTime)}（司法所当地时间）${cur.inside ? '·围栏内' : '·' + UI.esc(cur.verdictLabel || '越界')}</title>
        </circle>
      </g>`);
    }
    // 指北针与比例尺说明
    E.push(`<text x="${W - 26}" y="26" class="map-north">N ↑</text>`);

    const legend = `
      <span><i class="lg-box allow"></i>规定活动范围（多边形）</span>
      <span><i class="lg-box danger"></i>禁区（进入即越界）</span>
      <span><i class="lg-line"></i>采信轨迹</span>
      <span><i class="lg-dot in"></i>围栏内落点</span>
      <span><i class="lg-dot out"></i>越界落点（点击标记）</span>
      <span><i class="lg-square"></i>离线补传点</span>
      <span><i class="lg-x">✕</i>GPS 漂移丢弃（不连线/不报警）</span>
      <span><i class="lg-flag">🚩</i>人工标记</span>`;

    return {
      svg: `<svg viewBox="0 0 ${W} ${H}" class="mon-svg" role="img"
        aria-label="对象活动轨迹与电子围栏地图" preserveAspectRatio="xMidYMid meet">${E.join('')}</svg>`,
      legend,
      xOf, yOf, W, H,
    };
  }

  // ============================================================
  // 时间显示：只按对象司法所时区把 UTC 瞬时换成本地墙钟
  // ============================================================
  function tzOffset(zoneId) {
    try {
      const dt = new Date();
      const arr = new Intl.DateTimeFormat('en-US', { timeZone: zoneId, timeZoneName: 'longOffset' })
        .formatToParts(dt);
      const name = (arr.find((p) => p.type === 'timeZoneName') || {}).value || '';
      const m = name.match(/GMT([+-]\d{1,2})(?::?(\d{2}))?/);
      if (!m) return '+08:00';
      const h = m[1].padStart(3, '0');
      return `${h}:${m[2] || '00'}`;
    } catch { return '+08:00'; }
  }

  function failureHtml(title, message) {
    // 错误态（加载失败）：与“无轨迹”“已清除”明确区分，给重试
    return `<div class="state-box map-empty" id="fail-box">
      <div class="ico">⚠️</div><h3>${UI.esc(title)}</h3>
      <p>${UI.esc(message || '网络异常或服务暂不可用')}</p>
      <p class="muted small">这是加载错误态，不是无数据：请检查网络或稍后重试。</p>
      <button class="btn primary" id="btn-retry-fail">重新加载</button></div>`;
  }

  function inlineFail(msg) {
    return `<div class="state-box map-empty"><div class="ico">⚠️</div><h3>加载失败</h3><p>${UI.esc(msg)}</p></div>`;
  }

  function forbiddenHtml(message) {
    return `<div class="state-box forbidden"><div class="ico">🚫</div>
      <h3>无权查看该对象定位</h3><p>${UI.esc(message)}</p>
      <button class="btn primary" id="btn-back">返回监控列表</button></div>`;
  }
})(window);
