package cn.sfj.jiaowutong.web;

import cn.sfj.jiaowutong.common.ApiResult;
import cn.sfj.jiaowutong.security.CurrentUserHolder;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.service.MonitorService;
import cn.sfj.jiaowutong.web.dto.ClearTracksRequest;
import cn.sfj.jiaowutong.web.dto.MarkBreachRequest;
import cn.sfj.jiaowutong.web.vo.MonitorOverviewView;
import cn.sfj.jiaowutong.web.vo.MonitorView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 定位监控（干警/监管员）：
 * 总览轮询、单对象周/月轨迹与围栏、落点越界人工标记（二次确认原因）、轨迹清除（留痕）。
 * 矫正对象账号一律无权访问（菜单与服务端双重拦截）。
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /** 监控总览：对象行（定位状态/设备状态/三态/双口径完成度），前端每 5 秒轮询 */
    @GetMapping("/overview")
    public ApiResult<MonitorOverviewView> overview() {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(monitorService.overview(user));
    }

    /** 单对象监控：range=WEEK（默认）/ MONTH，窗口按司法所本地日界定 */
    @GetMapping("/{id}")
    public ApiResult<MonitorView> monitor(@PathVariable Long id,
                                          @RequestParam(defaultValue = "WEEK") String range) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(monitorService.monitor(id, range, user));
    }

    /** 实时心跳：只回当前状态，前端每 5 秒拉取（避免整月轨迹高频重传） */
    @GetMapping("/live/{id}")
    public ApiResult<Map<String, Object>> live(@PathVariable Long id) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(monitorService.live(id, user));
    }

    /** 对轨迹落点「标记越界」：二次确认 + 必填原因，服务端复核落点确在活动范围外 */
    @PostMapping("/{id}/mark-breach")
    public ApiResult<MonitorView.MarkView> markBreach(@PathVariable Long id,
                                                      @Valid @RequestBody MarkBreachRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(monitorService.markBreach(id, request, user));
    }

    /** 清除历史轨迹：二次确认 + 必填原因，写清除留痕（区分“从未上报”空态） */
    @PostMapping("/{id}/clear-tracks")
    public ApiResult<Map<String, Object>> clearTracks(@PathVariable Long id,
                                                      @Valid @RequestBody ClearTracksRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(monitorService.clearTracks(id, request, user));
    }
}
