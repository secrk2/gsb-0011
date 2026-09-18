package cn.sfj.jiaowutong.web.vo;

import cn.sfj.jiaowutong.common.time.TimeZones;
import cn.sfj.jiaowutong.domain.CorrectionObject;

import java.time.LocalDate;

/**
 * 档案列表/详情通用视图：默认不含全名（fullName 恒为 null），
 * 全名仅在二次确认留痕接口单独返回。
 * 所有定位时间均为 UTC（lastPointUtc，ISO Z 结尾），界面按 zoneId 换算显示。
 */
public record ObjectView(Long id, String correctionNo, String maskedName, String fullName,
                         String status, String statusLabel, Long officeId, String officeName,
                         String charge, String idCardTail, String phone,
                         LocalDate startDate, LocalDate endDate, String reportDay,
                         String lastPointUtc, Boolean lastInsideFence,
                         String deviceStatus, Integer batteryPercent,
                         Double lastLat, Double lastLng,
                         String zoneId, String zoneOffset,
                         String forbiddenWeekday, String forbiddenWeekdayLabel,
                         Double fenceCenterLat, Double fenceCenterLng, Integer fenceRadiusMeters) {

    public static ObjectView of(CorrectionObject o, boolean includeFullName) {
        var fw = cn.sfj.jiaowutong.common.time.TimeZones.parseWeekday(o.getOffice().getForbiddenWeekday());
        return new ObjectView(
                o.getId(),
                o.getCorrectionNo(),
                o.getMaskedName(),
                includeFullName ? o.getFullName() : null,
                o.getStatus().name(),
                o.getStatus().getLabel(),
                o.getOffice().getId(),
                o.getOffice().getName(),
                o.getCharge(),
                o.getIdCardTail(),
                o.getPhone(),
                o.getStartDate(),
                o.getEndDate(),
                o.getReportDay(),
                o.getLastLocationAt() == null ? null : o.getLastLocationAt().toString() + "Z",
                o.getLastInsideFence(),
                o.getLastDeviceStatus() == null ? "NONE" : o.getLastDeviceStatus(),
                o.getLastBatteryPercent(),
                o.getLastLat(),
                o.getLastLng(),
                o.getOffice().getTimezone(),
                TimeZones.offsetLabel(o.getOffice().getTimezone()),
                fw == null ? null : fw.name(),
                fw == null ? null : cn.sfj.jiaowutong.service.TrackService.weekCn(fw),
                o.getOffice().getCenterLat(),
                o.getOffice().getCenterLng(),
                o.getOffice().getFenceRadiusMeters()
        );
    }
}
