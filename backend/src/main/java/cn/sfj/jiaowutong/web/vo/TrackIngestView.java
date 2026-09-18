package cn.sfj.jiaowutong.web.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 轨迹批量上报结果：
 * - accepted：实际入库的新点数（按采集时间合并入轨迹）
 * - duplicates：幂等拦截的重复点数（同一 clientPointId 重放，不产生重复轨迹点）
 * - rejected：被拒绝点（如定位时间过旧）及原因
 * - outsideFence：本批越界点数（已生成红点）
 */
public record TrackIngestView(int total, int accepted, int duplicates, int rejected,
                              List<RejectedPoint> rejectedPoints, int outsideFence,
                              LocalDateTime latestLocationAt, Double latestLat, Double latestLng,
                              boolean latestInsideFence, boolean newViolationGenerated) {

    public record RejectedPoint(String clientPointId, String reason) {
    }
}
