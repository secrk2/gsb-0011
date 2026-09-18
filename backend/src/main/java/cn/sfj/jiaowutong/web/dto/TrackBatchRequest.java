package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 定位批量上报：实时点单条、离线恢复后队列批量补传。
 * pointTime 为<b>带时区偏移</b>的 ISO-8601 时刻（如 2026-09-18T14:00:00+08:00 或 ...Z），
 * 服务端统一换算为 UTC 存储；设备状态随腕表每 5 秒回传一并上送。
 */
public record TrackBatchRequest(
        @NotNull(message = "points 不能为空")
        @Size(min = 1, max = 200, message = "单批最多 200 个轨迹点")
        @Valid
        List<PointDto> points) {

    public record PointDto(
            @NotBlank(message = "clientPointId 缺失，无法幂等去重")
            @Size(max = 64)
            String clientPointId,
            @NotNull(message = "定位采集时间缺失，禁止用旧位置冒充")
            OffsetDateTime pointTime,
            @NotNull @Min(-90) @Max(90) Double lat,
            @NotNull @Min(-180) @Max(180) Double lng,
            /** 采集时是否离线（true=断网缓存补传点，false=实时点） */
            @NotNull Boolean offlineCaptured,
            /** 设备状态：NORMAL/LOW_BATTERY/NO_SIGNAL/POWER_OFF，缺省 NORMAL */
            @Size(max = 16) String deviceStatus,
            /** 电量百分比 0..100 */
            @Min(0) @Max(100) Integer batteryPercent) {
    }
}
