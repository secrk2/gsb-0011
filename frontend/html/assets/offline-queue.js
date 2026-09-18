/* ============================================================
 * 离线定位队列（矫正对象手机端）
 * - 断网期间定位点写入本地队列（localStorage 持久化，杀进程不丢）
 * - 恢复网络后整队列批量补传；服务端按 clientPointId 幂等去重、
 *   按采集时间合并，重放不产生重复轨迹点
 * - 定位时间取自 GPS 采集时刻，旧点/伪造点由服务端拒绝，本地照实记录
 * ============================================================ */
(function (global) {
  const QUEUE_KEY = 'jwt_track_queue_v1';
  const LOG_KEY = 'jwt_track_log_v1';

  function load(key, fallback) {
    try { return JSON.parse(localStorage.getItem(key) || 'null') || fallback; }
    catch { return fallback; }
  }
  function save(key, val) { localStorage.setItem(key, JSON.stringify(val)); }

  function uuid() {
    if (global.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'pt-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
      + '-' + Math.random().toString(36).slice(2, 6);
  }

  /** 转无时区 ISO（匹配后端 LocalDateTime），用浏览器本地时钟分量 */
  function localIso(d) {
    const p = (n) => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
      + 'T' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  const TrackQueue = {
    queue: load(QUEUE_KEY, []),
    logs: load(LOG_KEY, []),
    online: navigator.onLine,
    // 演示用：模拟乡村断网（即使设备有网络也按离线处理）
    simOffline: false,
    syncing: false,
    listeners: [],

    get effectiveOnline() { return this.online && !this.simOffline; },

    onChange(fn) { this.listeners.push(fn); },
    /** 视图级监听：重复进入页面时替换旧回调，避免叠加触发已销毁视图 */
    setViewListener(fn) { this.viewListener = fn; },
    emit() {
      const snap = this.snapshot();
      this.listeners.forEach((fn) => { try { fn(snap); } catch {} });
      if (this.viewListener) { try { this.viewListener(snap); } catch {} }
    },
    snapshot() {
      return {
        online: this.effectiveOnline,
        realOnline: this.online,
        simOffline: this.simOffline,
        queueCount: this.queue.length,
        logs: this.logs.slice(0, 30),
        syncing: this.syncing,
      };
    },

    setSimOffline(v) {
      this.simOffline = !!v;
      if (this.simOffline) {
        this.log('OFFLINE', '已模拟乡村弱网/断网场景：定位转入本地缓存');
      } else {
        this.log('SYNC', '模拟网络恢复，开始合并补传…');
        this.sync();
      }
      this.emit();
    },

    init() {
      const setOnline = (online) => {
        const was = this.online;
        this.online = online;
        this.log(online ? 'SYNC' : 'OFFLINE', online ? '网络已恢复' : '网络断开，进入离线模式');
        if (!was && online) {
          this.log('SYNC', '开始合并补传离线队列…');
          this.sync();
        }
        this.emit();
      };
      window.addEventListener('online', () => setOnline(true));
      window.addEventListener('offline', () => setOnline(false));

      // 兜底心跳：onLine 偶发不准（有信号无网），失败的请求也会标离线
      setInterval(() => { if (navigator.onLine && this.queue.length) this.sync(); }, 20000);
      this.emit();
    },

    log(type, msg) {
      const time = new Date().toLocaleTimeString('zh-CN', { hour12: false });
      this.logs.unshift({ time, type, msg });
      this.logs = this.logs.slice(0, 50);
      save(LOG_KEY, this.logs);
    },

    /** 采集一个定位点；offlineCaptured 按“采集瞬间是否在线”如实标记 */
    capture(lat, lng, fixAgeSeconds) {
      const now = new Date();
      const point = {
        clientPointId: uuid(),
        pointTime: localIso(now),
        lat: Number(lat.toFixed(6)),
        lng: Number(lng.toFixed(6)),
        offlineCaptured: !this.effectiveOnline,
      };
      this.queue.push(point);
      save(QUEUE_KEY, this.queue);
      const where = this.effectiveOnline ? '在线实时点，待上报' : '离线缓存点';
      this.log(this.effectiveOnline ? 'OK' : 'OFFLINE',
        `采集定位（${where}）${point.lat.toFixed(4)},${point.lng.toFixed(4)}`
          + (fixAgeSeconds != null ? `，定位龄 ${fixAgeSeconds}s` : ''));
      this.emit();
      if (this.effectiveOnline) this.sync();
      return point;
    },

    /** 整队列补传；幂等由服务端按 clientPointId 保证，重放安全 */
    async sync() {
      if (this.syncing || this.queue.length === 0) return;
      if (!navigator.onLine || this.simOffline) { this.emit(); return; }
      this.syncing = true; this.online = true; this.emit();

      // 复制一份发送：成功后按 clientPointId 移除；服务端拒绝的旧点不再重放（永久拒绝）
      const batch = this.queue.slice();
      const ids = new Set(batch.map((p) => p.clientPointId));
      try {
        const result = await Api.post('/offender/tracks', { points: batch });
        const remaining = this.queue.filter((p) => !ids.has(p.clientPointId));
        // 理论上服务端处理整批；保留任何未在发送批次中的新采集点
        this.queue = remaining;
        save(QUEUE_KEY, this.queue);

        if (result.duplicates > 0) {
          this.log('DUP', `合并补传完成：${result.duplicates} 个重复点被幂等去重，未产生重复轨迹`);
        }
        if (result.rejected > 0) {
          (result.rejectedPoints || []).forEach((r) =>
            this.log('REJECT', `旧位置点 ${r.clientPointId.slice(0, 8)} 被服务端拒绝：${r.reason}`));
        }
        if (result.accepted > 0) {
          this.log('OK', `补传成功：${result.accepted} 个轨迹点已按采集时间合并入库`
            + (result.outsideFence ? `，其中 ${result.outsideFence} 个点越界` : '')
            + (result.newViolationGenerated ? '，已生成越界预警' : ''));
        }
        if (result.accepted === 0 && result.duplicates === 0 && result.rejected > 0) {
          this.log('REJECT', '本批全部为失效旧位置点，均未采信');
        }
      } catch (e) {
        if (e.offline || e.code === 'NETWORK_OFFLINE') {
          this.online = false;
          this.log('OFFLINE', '补传失败：网络实际不可用，继续保留本地队列（' + batch.length + ' 点）');
        } else {
          this.log('REJECT', '补传失败：' + e.message + '，保留队列待重试');
        }
      } finally {
        this.syncing = false;
        this.emit();
      }
    },

    clearAll() {
      this.queue = [];
      save(QUEUE_KEY, this.queue);
      this.emit();
    },
  };

  global.TrackQueue = TrackQueue;
  global.localIso = localIso;
})(window);
