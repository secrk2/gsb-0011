package cn.sfj.jiaowutong.web.vo;

import java.util.List;

/**
 * 定位监控总览（菜单首屏）：数据范围按角色裁剪。
 * 所有时间均为 UTC（ISO，Z 结尾），界面按司法所时区显示；
 * trackState 用于把三种空态分开：ACTIVE 有轨迹 / EMPTY 从未上报 / CLEARED 轨迹曾被清除。
 * 完成度同时给两个口径（打卡天数 / 关键报到），界面必须标注口径，不能只报一个数。
 */
public record MonitorOverviewView(
        String serverNowUtc,
        List<OfficeOption> offices,
        List<SubjectRow> subjects,
        CompletionAggregate completion) {

    public record OfficeOption(Long id, String name, String region,
                               String zoneId, String zoneOffset, String forbiddenWeekday,
                               String forbiddenWeekdayLabel, boolean todayForbiddenDay) {
    }

    public record SubjectRow(Long id, String correctionNo, String maskedName,
                             Long officeId, String officeName, String status, String statusLabel,
                             String zoneId,
                             String lastPointUtc, Boolean lastInsideFence,
                             String deviceStatus, Integer batteryPercent,
                             boolean stale, boolean todayForbiddenDay,
                             String trackState,
                             long checkinDays, long keyCheckins, long keyCheckinsDue,
                             int checkinPercent, int keyPercent, boolean caliberDiverge) {
    }

    /** 两种口径的全范围汇总；caliberNote 为必须原样展示在界面的口径说明 */
    public record CompletionAggregate(long subjects,
                                      long checkinDaysSum, long servingDaysSum, int checkinPercent,
                                      long keyCheckinsSum, long keyCheckinsDueSum, int keyPercent,
                                      String caliberNote) {
    }
}
