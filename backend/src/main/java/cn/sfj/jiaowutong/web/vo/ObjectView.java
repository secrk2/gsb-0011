package cn.sfj.jiaowutong.web.vo;

import cn.sfj.jiaowutong.domain.CorrectionObject;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 档案列表/详情通用视图：默认不含全名（fullName 恒为 null），
 * 全名仅在二次确认留痕接口单独返回。
 */
public record ObjectView(Long id, String correctionNo, String maskedName, String fullName,
                         String status, String statusLabel, Long officeId, String officeName,
                         String charge, String idCardTail, String phone,
                         LocalDate startDate, LocalDate endDate, String reportDay,
                         LocalDateTime lastLocationAt, Boolean lastInsideFence,
                         Double lastLat, Double lastLng,
                         Double fenceCenterLat, Double fenceCenterLng, Integer fenceRadiusMeters) {

    public static ObjectView of(CorrectionObject o, boolean includeFullName) {
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
                o.getLastLocationAt(),
                o.getLastInsideFence(),
                o.getLastLat(),
                o.getLastLng(),
                o.getOffice().getCenterLat(),
                o.getOffice().getCenterLng(),
                o.getOffice().getFenceRadiusMeters()
        );
    }
}
