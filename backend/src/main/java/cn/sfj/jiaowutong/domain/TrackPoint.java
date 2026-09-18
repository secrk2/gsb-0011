package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 轨迹点（时间一律 UTC 存储：{@link #pointTime}、{@link #receivedAt} 均为 UTC 墙钟分量）。
 * clientPointId 为手机端生成的幂等键（UUID），离线缓存恢复后重放不会产生重复点。
 *
 * <p>质量分级 {@link #result}：
 * <ul>
 *   <li>ACCEPTED：通过时效校验与 GPS 漂移校验，计入轨迹与越界判定；</li>
 *   <li>DRIFT_DROPPED：连续两点跳变速度超过合理上限（{@code GpsDriftGuard}），判为漂移丢弃，
 *       不入轨迹连线、不参与越界判定，仅留底供“为什么没报警/为什么少点”的追溯；</li>
 *   <li>DUPLICATE：幂等去重（一般不入库）。</li>
 * </ul>
 */
@Entity
@Table(name = "track_point", uniqueConstraints = {
        @UniqueConstraint(name = "uk_track_client_point", columnNames = {"offender_id", "client_point_id"})
}, indexes = {
        @Index(name = "idx_track_offender_time", columnList = "offender_id,point_time")
})
public class TrackPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offender_id")
    private CorrectionObject offender;

    /** 客户端生成的幂等 ID */
    @Column(name = "client_point_id", nullable = false, length = 64)
    private String clientPointId;

    /** 定位采集时间（UTC）；腕表 GPS 采集时刻，离线期间为过去时间 */
    @Column(name = "point_time", nullable = false)
    private LocalDateTime pointTime;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    /** 采集该点时是否处于离线：true=离线缓存点，false=实时点 */
    @Column(name = "offline_captured", nullable = false)
    private Boolean offlineCaptured;

    /** 服务端入库时间（UTC） */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    /**
     * 该点是否越界。ACCEPTED 点为真实判定结果；
     * DRIFT_DROPPED 点恒为 false（不参与越界判定，是否采信看 {@link #result}）。
     * 列保持 NOT NULL 以便在既有 H2 文件库上平滑加列升级。
     */
    @Column(name = "outside_fence", nullable = false)
    private Boolean outsideFence = false;

    /** 设备状态：NORMAL 正常 / LOW_BATTERY 低电 / NO_SIGNAL 无信号 / POWER_OFF 关机 */
    @Column(name = "device_status", nullable = false, length = 16,
            columnDefinition = "varchar(16) default 'NORMAL'")
    private String deviceStatus = "NORMAL";

    /** 电量百分比 0..100（设备未上报时为空） */
    @Column(name = "battery_percent")
    private Integer batteryPercent;

    /** 采信结果：ACCEPTED 计入轨迹；DRIFT_DROPPED 漂移丢弃 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IngestResult result = IngestResult.ACCEPTED;

    /** 漂移丢弃原因（速度 km/h、相对前点等），ACCEPTED 时为空 */
    @Column(name = "drift_reason", length = 256)
    private String driftReason;

    public enum IngestResult { ACCEPTED, DRIFT_DROPPED, DUPLICATE }

    public TrackPoint() {
    }

    public TrackPoint(CorrectionObject offender, String clientPointId, LocalDateTime pointTime,
                      Double lat, Double lng, Boolean offlineCaptured,
                      LocalDateTime receivedAt, Boolean outsideFence, IngestResult result) {
        this(offender, clientPointId, pointTime, lat, lng, offlineCaptured, receivedAt,
                outsideFence, result, "NORMAL", null, null);
    }

    public TrackPoint(CorrectionObject offender, String clientPointId, LocalDateTime pointTime,
                      Double lat, Double lng, Boolean offlineCaptured,
                      LocalDateTime receivedAt, Boolean outsideFence, IngestResult result,
                      String deviceStatus, Integer batteryPercent, String driftReason) {
        this.offender = offender;
        this.clientPointId = clientPointId;
        this.pointTime = pointTime;
        this.lat = lat;
        this.lng = lng;
        this.offlineCaptured = offlineCaptured;
        this.receivedAt = receivedAt;
        this.outsideFence = outsideFence;
        this.result = result;
        this.deviceStatus = deviceStatus == null ? "NORMAL" : deviceStatus;
        this.batteryPercent = batteryPercent;
        this.driftReason = driftReason;
    }

    public Long getId() { return id; }
    public CorrectionObject getOffender() { return offender; }
    public String getClientPointId() { return clientPointId; }
    public LocalDateTime getPointTime() { return pointTime; }
    public Double getLat() { return lat; }
    public Double getLng() { return lng; }
    public Boolean getOfflineCaptured() { return offlineCaptured; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public Boolean getOutsideFence() { return outsideFence; }
    public String getDeviceStatus() { return deviceStatus; }
    public Integer getBatteryPercent() { return batteryPercent; }
    public IngestResult getResult() { return result; }
    public String getDriftReason() { return driftReason; }
}
