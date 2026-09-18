package cn.sfj.jiaowutong.web.vo;

import java.util.List;

/**
 * 单个对象的定位监控视图（周 / 月窗口）。
 * 窗口按司法所本地日界定后换算成 UTC 查询，杜绝跨时区日界算错；
 * tracks 只含采信点（ACCEPTED），dropped 为 GPS 漂移丢弃点（单独图层、灰显、附原因，不连线）。
 */
public record MonitorView(
        String serverNowUtc,
        Subject subject,
        String range,
        String zoneId,
        String zoneOffset,
        String localToday,
        String localWeekday,
        String forbiddenWeekday,
        String forbiddenWeekdayLabel,
        boolean todayForbiddenDay,
        String windowStartUtc,
        String windowEndUtc,
        String trackState,
        CurrentStatus current,
        CircleFence circleFence,
        List<FenceView> fences,
        List<TrackPointView> tracks,
        List<DroppedPointView> dropped,
        List<MarkView> marks,
        Completion completion,
        LastClear lastClear) {

    public record Subject(Long id, String correctionNo, String maskedName,
                          String status, String statusLabel, String officeName) {
    }

    public record CurrentStatus(String pointTimeUtc, String localTime, Double lat, Double lng,
                                Boolean inside, String verdictCode, String verdictLabel,
                                String deviceStatus, String deviceStatusLabel,
                                Integer batteryPercent, long ageSeconds, boolean stale) {
    }

    /** 未配置多边形活动范围时使用的回退圆形围栏（前端仍画出，避免无多边形时图面空白） */
    public record CircleFence(Double centerLat, Double centerLng, Integer radiusMeters) {
    }

    public record FenceView(Long id, String name, String kind, String kindLabel,
                            List<double[]> ring) {
    }

    public record TrackPointView(Long id, String pointTimeUtc, String localTime,
                                 Double lat, Double lng, boolean offline,
                                 String deviceStatus, Integer batteryPercent,
                                 boolean outside, String verdictCode) {
    }

    public record DroppedPointView(String pointTimeUtc, String localTime,
                                   Double lat, Double lng, String reason) {
    }

    public record MarkView(Long id, Long trackPointId, Double lat, Double lng,
                           String pointTimeUtc, String localTime,
                           String reason, String verdictReason,
                           String markerUserName, String markedAtUtc, String markedAtLocal) {
    }

    /**
     * 双口径完成度：
     * - checkinDays 打卡天数（矫正期内任意一天报到都计 1 天，当天多次只计 1）；
     * - keyCheckins 关键报到（规定报到星期当天实际完成的次数），keyCheckinsDue 为到期应完成次数。
     * 只在非规定日零散报到的对象：打卡天数高、关键报到为 0，两口径方向相反，故界面同屏展示并注明口径。
     */
    public record Completion(long checkinDays, long servingDays, int checkinPercent,
                             long keyCheckins, long keyCheckinsDue, int keyPercent,
                             boolean caliberDiverge, String caliberNote) {
    }

    public record LastClear(String clearedAtUtc, String clearedAtLocal,
                            String operatorUserName, String reason, int pointCount) {
    }
}
