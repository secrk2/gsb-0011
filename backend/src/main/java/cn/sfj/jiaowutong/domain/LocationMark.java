package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 落点越界标记（干警人工标记留痕）。
 * 干警在轨迹图上对某个落点发起“标记越界”，服务端必须复核该点确实在活动范围外/禁区内，
 * 复核通过才写入并同步生成红点；复核不通过（点在围栏内）拒绝，防止误标。
 * 全流程：二次确认 + 必填原因 + 操作人 + UTC 时间，事后可审计。
 */
@Entity
@Table(name = "location_mark", indexes = {
        @Index(name = "idx_mark_offender", columnList = "offender_id,marked_at")
})
public class LocationMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offender_id")
    private CorrectionObject offender;

    /** 关联的轨迹点（图上标记落点时）；手工补录坐标时为空 */
    private Long trackPointId;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    /** 落点采集时间（UTC 存储） */
    @Column(name = "point_time", nullable = false)
    private LocalDateTime pointTime;

    /** 干警填写的标记原因（二次确认，必填） */
    @Column(nullable = false, length = 256)
    private String reason;

    /** 服务端复核结论：命中的禁区名 / 越界类型说明 */
    @Column(name = "verdict_reason", nullable = false, length = 128)
    private String verdictReason;

    @Column(name = "marker_user_id", nullable = false)
    private Long markerUserId;

    @Column(name = "marker_user_name", nullable = false, length = 64)
    private String markerUserName;

    @Column(name = "marked_at", nullable = false)
    private LocalDateTime markedAt;

    public LocationMark() {
    }

    public LocationMark(CorrectionObject offender, Long trackPointId, Double lat, Double lng,
                        LocalDateTime pointTime, String reason, String verdictReason,
                        Long markerUserId, String markerUserName, LocalDateTime markedAt) {
        this.offender = offender;
        this.trackPointId = trackPointId;
        this.lat = lat;
        this.lng = lng;
        this.pointTime = pointTime;
        this.reason = reason;
        this.verdictReason = verdictReason;
        this.markerUserId = markerUserId;
        this.markerUserName = markerUserName;
        this.markedAt = markedAt;
    }

    public Long getId() { return id; }
    public CorrectionObject getOffender() { return offender; }
    public Long getTrackPointId() { return trackPointId; }
    public Double getLat() { return lat; }
    public Double getLng() { return lng; }
    public LocalDateTime getPointTime() { return pointTime; }
    public String getReason() { return reason; }
    public String getVerdictReason() { return verdictReason; }
    public Long getMarkerUserId() { return markerUserId; }
    public String getMarkerUserName() { return markerUserName; }
    public LocalDateTime getMarkedAt() { return markedAt; }
}
