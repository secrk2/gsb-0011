package cn.sfj.jiaowutong.web.vo;

import java.util.List;

/**
 * 轨迹批量上报结果：
 * - accepted：通过时效与漂移校验、实际入库的新点数（按采集时间合并入轨迹）；
 * - duplicates：幂等拦截的重复点（同一 clientPointId 重放，不产生重复轨迹点）；
 * - driftDropped：GPS 漂移丢弃点数（两点跳变等效速度超合理上限，留底但不入轨迹）；
 * - rejected：时效等校验拒绝点及原因；
 * - outsideFence：本批越界点数（已生成红点）。
 * 时间字段为 UTC（ISO，Z 结尾），展示由前端按司法所时区换算。
 */
public record TrackIngestView(int total, int accepted, int duplicates, int driftDropped, int rejected,
                              List<RejectedPoint> rejectedPoints, int outsideFence,
                              String latestPointTimeUtc, Double latestLat, Double latestLng,
                              boolean latestInsideFence, String latestDeviceStatus,
                              Integer latestBatteryPercent, boolean newViolationGenerated) {

    public record RejectedPoint(String clientPointId, String reason) {
    }
}
