package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 干警对某个轨迹落点发起「标记越界」。
 * 必须二次确认并填写原因；服务端会独立复核该点确实在活动范围外/禁区内才采信。
 * 优先用 trackPointId 关联轨迹点；手工补录时给 lat/lng/pointTimeIso（带偏移 ISO-8601）。
 */
public record MarkBreachRequest(
        Long trackPointId,
        @Min(-90) @Max(90) Double lat,
        @Min(-180) @Max(180) Double lng,
        String pointTimeIso,
        @NotBlank(message = "标记越界必须填写原因，该操作全程留痕")
        @Size(min = 4, max = 256, message = "原因不少于 4 个字，便于事后核查")
        String reason) {
}
