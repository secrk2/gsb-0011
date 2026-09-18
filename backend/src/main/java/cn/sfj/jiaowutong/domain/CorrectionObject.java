package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 社区矫正对象档案。
 */
@Entity
@Table(name = "correction_object", indexes = {
        @Index(name = "idx_obj_office", columnList = "office_id"),
        @Index(name = "idx_obj_status", columnList = "status")
})
public class CorrectionObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 矫正编号，唯一，如 JWT2026001 */
    @Column(nullable = false, unique = true, length = 32)
    private String correctionNo;

    /** 真实姓名（敏感信息：列表默认不返回，需二次确认+理由后查看并留痕） */
    @Column(nullable = false, length = 64)
    private String fullName;

    /** 脱敏展示名：姓首字母+编号，如 Z-JWT26001，由服务层计算，不直接暴露 fullName */
    @Column(nullable = false, length = 64)
    private String maskedName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CorrectionStatus status = CorrectionStatus.INTAKE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id")
    private JudicialOffice office;

    @Column(length = 32)
    private String idCardTail;

    /** 罪名 */
    @Column(length = 64)
    private String charge;

    /** 矫正期限起 */
    private LocalDate startDate;

    /** 矫正期限止 */
    private LocalDate endDate;

    /** 联系电话 */
    @Column(length = 20)
    private String phone;

    /** 规定报到日：MONDAY..SUNDAY */
    @Column(length = 16)
    private String reportDay;

    /** 最近一次有效定位时间（由轨迹上报更新） */
    private LocalDateTime lastLocationAt;

    private Double lastLat;
    private Double lastLng;

        /** 最近一次定位是否在围栏内 */
    private Boolean lastInsideFence;

    /** 最近一次有效定位点的设备状态：NORMAL/LOW_BATTERY/NO_SIGNAL/POWER_OFF */
    @Column(name = "last_device_status", length = 16)
    private String lastDeviceStatus;

    /** 最近一次上报电量（0..100） */
    @Column(name = "last_battery_percent")
    private Integer lastBatteryPercent;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = cn.sfj.jiaowutong.common.time.TimeZones.utcNow();
        }
    }

    public Long getId() { return id; }
    public String getCorrectionNo() { return correctionNo; }
    public String getFullName() { return fullName; }
    public String getMaskedName() { return maskedName; }
    public CorrectionStatus getStatus() { return status; }
    public JudicialOffice getOffice() { return office; }
    public String getIdCardTail() { return idCardTail; }
    public String getCharge() { return charge; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getPhone() { return phone; }
    public String getReportDay() { return reportDay; }
    public LocalDateTime getLastLocationAt() { return lastLocationAt; }
    public Double getLastLat() { return lastLat; }
    public Double getLastLng() { return lastLng; }
    public Boolean getLastInsideFence() { return lastInsideFence; }
    public String getLastDeviceStatus() { return lastDeviceStatus; }
    public Integer getLastBatteryPercent() { return lastBatteryPercent; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCorrectionNo(String correctionNo) { this.correctionNo = correctionNo; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setMaskedName(String maskedName) { this.maskedName = maskedName; }
    public void setStatus(CorrectionStatus status) { this.status = status; }
    public void setOffice(JudicialOffice office) { this.office = office; }
    public void setIdCardTail(String idCardTail) { this.idCardTail = idCardTail; }
    public void setCharge(String charge) { this.charge = charge; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setReportDay(String reportDay) { this.reportDay = reportDay; }
    public void setLastLocationAt(LocalDateTime lastLocationAt) { this.lastLocationAt = lastLocationAt; }
    public void setLastLat(Double lastLat) { this.lastLat = lastLat; }
    public void setLastLng(Double lastLng) { this.lastLng = lastLng; }
    public void setLastInsideFence(Boolean lastInsideFence) { this.lastInsideFence = lastInsideFence; }
    public void setLastDeviceStatus(String lastDeviceStatus) { this.lastDeviceStatus = lastDeviceStatus; }
    public void setLastBatteryPercent(Integer lastBatteryPercent) { this.lastBatteryPercent = lastBatteryPercent; }
}
