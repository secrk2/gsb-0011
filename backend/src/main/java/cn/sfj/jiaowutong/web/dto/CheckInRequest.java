package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * 日常报到请求。fixTime 为带偏移的 GPS 采集时刻，服务端按 UTC 统一存储、按时效强校验。
 */
public record CheckInRequest(
        @NotNull(message = "缺少定位时间，无法核验位置时效") OffsetDateTime fixTime,
        @NotNull @Min(-90) @Max(90) Double lat,
        @NotNull @Min(-180) @Max(180) Double lng) {
}
