/* 通用 UI：toast、modal、转义、状态文案 */
(function (global) {
  const STATUS_LABEL = {
    INTAKE: '入矫登记', SERVING: '在矫', LEAVE: '请假外出',
    ADMONISHED: '训诫', REIMPRISONED: '收监', RELEASED: '解除',
  };
  const STATUS_ICON = {
    INTAKE: '📝', SERVING: '✅', LEAVE: '🚪',
    ADMONISHED: '⚠️', REIMPRISONED: '🔒', RELEASED: '📭',
  };
  const WEEK_LABEL = {
    MONDAY: '周一', TUESDAY: '周二', WEDNESDAY: '周三', THURSDAY: '周四',
    FRIDAY: '周五', SATURDAY: '周六', SUNDAY: '周日',
  };
  const DEVICE_LABEL = {
    NORMAL: '设备正常', LOW_BATTERY: '低电量', NO_SIGNAL: '定位信号中断',
    POWER_OFF: '腕表关机', NONE: '无回传',
  };
  const DEVICE_ICON = {
    NORMAL: '🔋', LOW_BATTERY: '🪫', NO_SIGNAL: '📵', POWER_OFF: '⭕', NONE: '❔',
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function statusBadge(status) {
    return `<span class="badge ${esc(status)}"><span class="b-ico">${STATUS_ICON[status] || ''}</span>${esc(STATUS_LABEL[status] || status)}</span>`;
  }

  function fmtDateTime(s) {
    if (!s) return '—';
    return String(s).replace('T', ' ').slice(0, 16);
  }

  // 后端所有时间为 UTC（Z 结尾）。界面统一按“对象所在司法所时区”换算显示，
  // 不能直接用浏览器本地时区（干警浏览器可能与对象所在地跨时区/跨夏令时）。
  function tzParts(isoUtc, zoneId) {
    const d = new Date(isoUtc);
    if (isNaN(d.getTime())) return null;
    const fmt = new Intl.DateTimeFormat('zh-CN', {
      timeZone: zoneId || 'Asia/Shanghai',
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
    });
    const p = {};
    fmt.formatToParts(d).forEach((x) => { p[x.type] = x.value; });
    return p;
  }

  /** UTC ISO → “yyyy-MM-dd HH:mm”，按指定 IANA 时区 */
  function tzText(isoUtc, zoneId) {
    const p = tzParts(isoUtc, zoneId);
    return p ? `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}` : '—';
  }

  /** UTC ISO 已过去多久（秒） */
  function tzAgeSec(isoUtc) {
    const t = new Date(isoUtc).getTime();
    return isNaN(t) ? null : Math.max(0, Math.round((Date.now() - t) / 1000));
  }

  let toastTimer = new Map();
  function toast(message, type) {
    type = type || 'info';
    const root = document.getElementById('toast-root');
    const el = document.createElement('div');
    el.className = 'toast ' + type;
    el.textContent = message;
    root.appendChild(el);
    setTimeout(() => {
      el.style.opacity = '0';
      el.style.transition = 'opacity .25s';
      setTimeout(() => el.remove(), 260);
    }, 3800);
  }

  /**
   * 确认弹窗。options: {title, bodyHtml, confirmText, danger, requireReason}
   * 返回 Promise：确认时 resolve(reason|null)，取消时 reject('cancel')
   */
  function confirmModal(options) {
    return new Promise((resolve, reject) => {
      const root = document.getElementById('modal-root');
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      const reasonField = options.requireReason ? `
        <div class="field" style="margin-top:14px">
          <label>请填写${esc(options.reasonLabel || '理由')}（必填，将留痕）</label>
          <textarea class="input" id="modal-reason" minlength="4" maxlength="256"
            placeholder="例如：${esc(options.reasonPlaceholder || '司法所工作核查需要')}"></textarea>
        </div>` : '';
      mask.innerHTML = `
        <div class="modal ${options.requireReason ? 'warn' : ''}" role="dialog" aria-modal="true">
          <div class="modal-head">${options.icon ? options.icon + ' ' : ''}${esc(options.title || '请确认')}</div>
          <div class="modal-body">
            ${options.warn ? `<div class="confirm-warn">⚠️ ${esc(options.warn)}</div>` : ''}
            <div>${options.bodyHtml || ''}</div>
            ${reasonField}
            <div class="modal-error" id="modal-err" style="display:none"></div>
          </div>
          <div class="modal-foot">
            <button class="btn" id="modal-cancel">取消</button>
            <button class="btn ${options.danger ? 'danger' : 'primary'}" id="modal-ok">${esc(options.confirmText || '确认')}</button>
          </div>
        </div>`;
      root.appendChild(mask);
      const reasonEl = mask.querySelector('#modal-reason');
      if (reasonEl) setTimeout(() => reasonEl.focus(), 30);

      function close() { mask.remove(); }
      mask.querySelector('#modal-cancel').onclick = () => { close(); reject(new Error('cancel')); };
      mask.addEventListener('click', (e) => { if (e.target === mask) { close(); reject(new Error('cancel')); } });
      mask.querySelector('#modal-ok').onclick = () => {
        const reason = reasonEl ? reasonEl.value.trim() : null;
        if (options.requireReason && reason.length < 4) {
          const err = mask.querySelector('#modal-err');
          err.style.display = 'block';
          err.className = 'login-error';
          err.textContent = '理由不少于 4 个字，该操作将被审计留痕';
          return;
        }
        close();
        resolve(reason);
      };
    });
  }

  /** 普通信息弹窗 */
  function alertModal(title, bodyHtml, icon) {
    return new Promise((resolve) => {
      const root = document.getElementById('modal-root');
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = `
        <div class="modal" role="dialog">
          <div class="modal-head">${icon ? icon + ' ' : ''}${esc(title)}</div>
          <div class="modal-body">${bodyHtml}</div>
          <div class="modal-foot"><button class="btn primary" id="modal-ok">我知道了</button></div>
        </div>`;
      root.appendChild(mask);
      mask.querySelector('#modal-ok').onclick = () => { mask.remove(); resolve(); };
      mask.addEventListener('click', (e) => { if (e.target === mask) { mask.remove(); resolve(); } });
    });
  }

  global.UI = {
    esc, statusBadge, fmtDateTime, tzText, tzParts, tzAgeSec, toast, confirmModal, alertModal,
    statusIcon: (s) => STATUS_ICON[s] || '',
    deviceLabel: (s) => DEVICE_LABEL[s] || (s || '—'),
    deviceIcon: (s) => DEVICE_ICON[s] || '❔',
    STATUS_LABEL, STATUS_ICON, WEEK_LABEL, DEVICE_LABEL, DEVICE_ICON,
  };
})(window);
