/* 矫正对象手机端：我的矫正 / 报到 / 离线定位补传 */
(function (global) {
  const Views = global.Views || (global.Views = {});

  let currentFix = null;   // {lat,lng,fixTime(ISO 本地),source,ageOk}
  let autoTimer = null;
  let fixTimer = null;
  let detail = null;

  Views.offender = async function (root) {
    // 离开后再进入：停掉旧视图的定时器，避免后台继续采集
    if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
    if (fixTimer) { clearInterval(fixTimer); fixTimer = null; }
    root.innerHTML = `<div class="skeleton">加载中…</div>`;
    const session = Api.getSession();
    detail = await Api.get('/objects/' + session.offenderId);
    const o = detail.object;

    if (!global.__tqInit) {
      TrackQueue.init();
      global.__tqInit = true;
    }

    root.innerHTML = `
      <div class="phone-wrap">
        <div class="page-head" style="margin-bottom:12px">
          <h2 style="font-size:18px">📱 我的矫正</h2>
          <div class="desc">${UI.esc(o.officeName)} · 电子围栏半径 ${o.fenceRadiusMeters} 米</div>
        </div>

        <div id="net-banner" class="net-banner show"></div>

        <div class="phone-card">
          <h3>👤 我的档案</h3>
          <dl class="kv" style="grid-template-columns:84px 1fr">
            <dt>编号</dt><dd>${UI.esc(o.correctionNo)}</dd>
            <dt>状态</dt><dd>${UI.statusBadge(o.status)}</dd>
            <dt>规定报到</dt><dd>每${UI.WEEK_LABEL[o.reportDay] || '—'}</dd>
            <dt>今日报到</dt><dd id="checkin-state">${detail.checkedToday
              ? '<span class="badge green">✅ 已报到</span>'
              : '<span class="badge gray">🕒 尚未报到</span>'}</dd>
            <dt>最近定位</dt><dd>${o.lastLocationAt
              ? UI.fmtDateTime(o.lastLocationAt) + (o.lastInsideFence ? '（围栏内）' : '（<span style="color:var(--critical)">越界</span>）')
              : '暂无'}</dd>
          </dl>
        </div>

        <div class="phone-card">
          <h3>📍 今日报到</h3>
          <div id="fix-state" class="loc-state" style="margin-bottom:12px">尚未获取定位。报到必须使用<b>当前</b>定位，
            乡村断网恢复后也需重新定位，不能用缓存旧位置。</div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px">
            <button class="btn sm" id="btn-gps">🛰 获取真实 GPS 定位</button>
            <button class="btn sm" id="btn-fix-in">模拟定位（围栏内）</button>
            <button class="btn sm" id="btn-fix-out">模拟定位（围栏外）</button>
          </div>
          <button class="big-check-btn" id="btn-checkin">今日报到</button>
          <div style="margin-top:10px">
            <button class="btn sm" id="btn-stale" style="width:100%">
              🧪 演示：尝试用 2 小时前的缓存旧定位报到</button>
          </div>
        </div>

        <div class="phone-card">
          <h3>🧭 离线定位与补传</h3>
          <div style="font-size:12.5px;color:var(--ink-secondary);margin-bottom:10px">
            山区信号差时可先断网，定位点本地缓存（杀进程不丢）；信号恢复后整队列合并补传，
            服务端按点位编号幂等去重，重复发送不会产生重复轨迹。
          </div>
          <div id="queue-state" class="track-summary" style="margin-bottom:10px"></div>
          <div style="display:flex;gap:8px;flex-wrap:wrap">
            <button class="btn sm primary" id="btn-capture">采集一次定位</button>
            <button class="btn sm" id="btn-auto">自动采集：关</button>
            <button class="btn sm" id="btn-sync">立即补传</button>
            <button class="btn sm danger" id="btn-simoff">模拟断网：关</button>
          </div>
          <div class="queue-state" id="sync-result"></div>
        </div>

        <div class="phone-card">
          <h3>📒 定位/补传日志</h3>
          <div class="log-list" id="log-list"></div>
        </div>
      </div>`;

    // ----- 定位 -----
    root.querySelector('#btn-gps').onclick = acquireGps;
    root.querySelector('#btn-fix-in').onclick = () => simulatedFix(false);
    root.querySelector('#btn-fix-out').onclick = () => simulatedFix(true);

    function refreshFixState() {
      const el = root.querySelector('#fix-state');
      if (!el) return;
      if (!currentFix) {
        el.innerHTML = '尚未获取定位。报到必须使用<b>当前</b>定位。';
        el.className = 'loc-state';
        return;
      }
      const ageSec = Math.round((Date.now() - currentFix.fixTs) / 1000);
      const stale = ageSec > 300;
      const where = currentFix.inside ? '围栏内' : '<span class="bad">围栏外</span>';
      if (stale) {
        el.className = 'loc-state';
        el.innerHTML = `<span class="stale">⚠ 定位已过 ${Math.round(ageSec / 60)} 分钟，属旧位置</span>
          （${currentFix.lat.toFixed(5)},${currentFix.lng.toFixed(5)}，${where}）。
          请重新获取定位后再报到，<b>旧位置无法通过服务端时效校验</b>。`;
      } else {
        el.className = 'loc-state';
        el.innerHTML = `<span class="ok">● 定位有效</span>（${ageSec}s 前获取，${where}）
          ${currentFix.source === 'gps' ? '来源：设备 GPS' : '来源：模拟定位'}`;
      }
    }
    if (fixTimer) clearInterval(fixTimer);
    fixTimer = setInterval(refreshFixState, 5000);

    function simulatedFix(outside) {
      // 以档案围栏中心生成：围栏内偏移 ~0.001°，围栏外偏移 ~0.022°（约 2km+）
      const off = outside ? 0.022 : 0.0012;
      currentFix = {
        lat: o.fenceCenterLat + off,
        lng: o.fenceCenterLng + (outside ? 0.006 : -0.0006),
        fixTs: Date.now(),
        source: outside ? 'sim-out' : 'sim-in',
        inside: !outside,
      };
      refreshFixState();
      UI.toast(outside ? '已获取定位：当前在电子围栏外' : '已获取定位：当前在电子围栏内', outside ? 'warn' : 'success');
    }

    function acquireGps() {
      if (!navigator.geolocation) {
        UI.toast('当前环境不支持 GPS，请使用“模拟定位”按钮演示', 'warn');
        return;
      }
      UI.toast('正在获取 GPS 定位（超时 10 秒）…');
      navigator.geolocation.getCurrentPosition((pos) => {
        // 关键：用 coords.timestamp 核验定位龄，拒绝拿缓存旧坐标
        const ageSec = Math.round((Date.now() - pos.coords.timestamp) / 1000);
        if (ageSec > 300) {
          refreshFixState();
          UI.toast('GPS 返回的是 ' + Math.round(ageSec / 60) + ' 分钟前的缓存位置，请移动到开阔处重新定位', 'error');
          currentFix = {
            lat: pos.coords.latitude, lng: pos.coords.longitude,
            fixTs: pos.coords.timestamp, source: 'gps-stale', inside: null,
          };
          refreshFixState();
          return;
        }
        currentFix = {
          lat: pos.coords.latitude, lng: pos.coords.longitude,
          fixTs: Date.now(), source: 'gps', inside: null,
        };
        refreshFixState();
        UI.toast('GPS 定位成功（精度 ±' + Math.round(pos.coords.accuracy) + ' 米）', 'success');
      }, (err) => {
        UI.toast('GPS 获取失败：' + err.message + '。可改用模拟定位演示', 'error');
      }, { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 });
    }

    // ----- 报到 -----
    root.querySelector('#btn-checkin').onclick = async () => {
      if (!currentFix) { UI.toast('请先获取当前定位', 'warn'); return; }
      const ageSec = Math.round((Date.now() - currentFix.fixTs) / 1000);
      if (ageSec > 300) {
        UI.toast('定位已过期，不能用旧位置报到，请重新获取定位', 'error');
        return;
      }
      const btn = root.querySelector('#btn-checkin');
      btn.disabled = true; btn.textContent = '报到提交中…';
      try {
        const res = await Api.post('/offender/check-in', {
          fixTime: localIso(new Date(currentFix.fixTs)),
          lat: currentFix.lat,
          lng: currentFix.lng,
        });
        UI.toast(res.message, res.insideFence ? 'success' : 'warn');
        detail = await Api.get('/objects/' + session.offenderId);
        root.querySelector('#checkin-state').innerHTML = '<span class="badge green">✅ 已报到</span>';
      } catch (e) {
        if (e.code === 'STALE_LOCATION') {
          await UI.alertModal('服务端已拒绝旧位置报到',
            `<div style="font-size:13.5px;line-height:1.7">${UI.esc(e.message)}</div>`, '⛔');
        } else {
          UI.toast(e.message, 'error');
        }
      } finally {
        btn.disabled = false; btn.textContent = '今日报到';
      }
    };

    // 旧位置糊弄演示：客户端不拦，服务端拒绝并说明原因
    root.querySelector('#btn-stale').onclick = async () => {
      const oldTime = new Date(Date.now() - 2 * 3600 * 1000);
      try {
        await Api.post('/offender/check-in', {
          fixTime: localIso(oldTime),
          lat: o.fenceCenterLat + 0.001,
          lng: o.fenceCenterLng,
        });
      } catch (e) {
        await UI.alertModal('旧位置报到被拦截',
          `<div style="font-size:13.5px;line-height:1.7">${UI.esc(e.message)}</div>
           <div style="margin-top:10px;font-size:12.5px;color:var(--ink-muted)">
           错误码：${UI.esc(e.code || '')}（HTTP 422）。定位时效由服务端时钟裁决。</div>`, '⛔');
      }
    };

    // ----- 离线定位 -----
    root.querySelector('#btn-capture').onclick = () => {
      const fix = pickCaptureFix(o);
      TrackQueue.capture(fix.lat, fix.lng, fix.age);
    };
    root.querySelector('#btn-sync').onclick = () => TrackQueue.sync();
    root.querySelector('#btn-simoff').onclick = (e) => {
      TrackQueue.setSimOffline(!TrackQueue.simOffline);
    };
    root.querySelector('#btn-auto').onclick = () => {
      if (autoTimer) {
        clearInterval(autoTimer); autoTimer = null;
      } else {
        autoTimer = setInterval(() => {
          const fix = pickCaptureFix(o);
          TrackQueue.capture(fix.lat, fix.lng, fix.age);
        }, 10000);
      }
    };

    function renderQueue(snap) {
      const qs = root.querySelector('#queue-state');
      if (!qs) return;
      qs.innerHTML = `
        <span>网络状态：<b style="color:${snap.online ? 'var(--good)' : 'var(--critical)'}">
          ${snap.online ? '● 在线' : '● 离线（本地缓存中）'}</b></span>
        <span>待补传轨迹点：<b>${snap.queueCount}</b></span>
        <span>补传状态：<b>${snap.syncing ? '合并补传中…' : '空闲'}</b></span>`;

      const banner = root.querySelector('#net-banner');
      if (snap.online) {
        banner.className = 'net-banner show online';
        banner.innerHTML = snap.queueCount
          ? `<span class="pulse"></span><span>网络已恢复，还有 <b>${snap.queueCount}</b> 个离线点待补传，系统将自动合并上传（幂等不重复）</span>`
          : '<span class="pulse"></span><span>网络正常，定位实时上报。</span>';
      } else {
        banner.className = 'net-banner show';
        const reason = snap.simOffline && snap.realOnline
          ? '当前为<b>模拟乡村断网</b>演示（设备有信号、应用按离线处理）'
          : '设备网络不可用';
        banner.innerHTML = `<span class="pulse"></span>
          <span>${reason}：定位仅保存在本机队列（<b>${snap.queueCount}</b> 点），
          不会用旧位置冒充实时位置；信号恢复后自动合并补传。</span>`;
      }

      root.querySelector('#btn-simoff').textContent = '模拟断网：' + (snap.simOffline ? '开' : '关');
      root.querySelector('#btn-auto').textContent = '自动采集：' + (autoTimer ? '开' : '关');
      root.querySelector('#log-list').innerHTML = snap.logs.map((l) => `
        <div class="log-item ${l.type}"><span class="t">${UI.esc(l.time)}</span><span>${UI.esc(l.msg)}</span></div>`).join('')
        || '<div style="color:var(--ink-muted);font-size:12.5px">暂无日志</div>';
    }
    TrackQueue.setViewListener(renderQueue);
    renderQueue(TrackQueue.snapshot());
  };

  // 采集点优先用当前有效定位（加微小漂移），否则按围栏内随机点
  function pickCaptureFix(o) {
    if (currentFix && (Date.now() - currentFix.fixTs) < 300000) {
      // 人在移动：每次小幅漂移；模拟“围栏外”点时向外再走一点
      const drift = (Math.random() - 0.5) * 0.0008;
      return {
        lat: currentFix.lat + drift,
        lng: currentFix.lng + drift,
        age: Math.round((Date.now() - currentFix.fixTs) / 1000),
      };
    }
    return {
      lat: o.fenceCenterLat + (Math.random() - 0.5) * 0.0016,
      lng: o.fenceCenterLng + (Math.random() - 0.5) * 0.0016,
      age: 2,
    };
  }
})(window);
