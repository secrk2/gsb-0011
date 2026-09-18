package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 清除对象历史轨迹：二次确认 + 必填原因，清除动作本身写留痕。 */
public record ClearTracksRequest(
        @NotBlank(message = "清除轨迹必须填写工作原因")
        @Size(min = 4, max = 256, message = "原因不少于 4 个字，便于审计留痕")
        String reason) {
}
