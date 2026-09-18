package cn.sfj.jiaowutong.web.vo;

/**
 * 状态流转留痕视图。operatedAtUtc 为 UTC（Z 结尾），localTime 已按司法所时区换算。
 */
public record TransitionView(String fromStatus, String fromStatusLabel,
                             String toStatus, String toStatusLabel,
                             String reason, String operatorName,
                             String operatedAtUtc, String operatedAtLocal) {
}
