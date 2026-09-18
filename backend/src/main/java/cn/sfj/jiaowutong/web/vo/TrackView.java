package cn.sfj.jiaowutong.web.vo;

/**
 * 轨迹回放点（档案详情用）。
 * pointTimeUtc 为 UTC（ISO，Z 结尾）；localTime 为按司法所时区换算的展示串，
 * 前端直接显示 localTime，避免把 UTC 当本地时间造成“差 8 小时”的误读。
 */
public record TrackView(String clientPointId, String pointTimeUtc, String localTime,
                        Double lat, Double lng, boolean offlineCaptured,
                        String receivedAtUtc, Boolean outsideFence,
                        String deviceStatus, String result, String driftReason) {
}
