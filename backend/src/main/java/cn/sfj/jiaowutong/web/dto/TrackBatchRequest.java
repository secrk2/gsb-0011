package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定位批量上报：实时点单条、离线恢复后队列批量补传。
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
            LocalDateTime pointTime,
            @NotNull @Min(-90) @Max(90) Double lat,
            @NotNull @Min(-180) @Max(180) Double lng,
            /** 采集时是否离线（true=断网缓存补传点，false=实时点） */
            @NotNull Boolean offlineCaptured) {
    }
}
