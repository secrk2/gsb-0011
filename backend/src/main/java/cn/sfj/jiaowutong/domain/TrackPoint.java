package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 轨迹点。
 * clientPointId 为手机端生成的幂等键（UUID），离线缓存恢复后重放不会产生重复点。
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

    /** 定位采集时间（手机本地时钟），离线期间为过去时间 */
    @Column(nullable = false)
    private LocalDateTime pointTime;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    /** 采集该点时是否处于离线：true=离线缓存点，false=实时点 */
    @Column(nullable = false)
    private Boolean offlineCaptured;

    /** 服务端入库时间 */
    @Column(nullable = false)
    private LocalDateTime receivedAt;

    /** 该点是否越界 */
    @Column(nullable = false)
    private Boolean outsideFence;

    /** 合并标记：离线补传时被去重忽略，或被合并；正常点为 ACCEPTED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IngestResult result = IngestResult.ACCEPTED;

    public enum IngestResult { ACCEPTED, DUPLICATE }

    public TrackPoint() {
    }

    public TrackPoint(CorrectionObject offender, String clientPointId, LocalDateTime pointTime,
                      Double lat, Double lng, Boolean offlineCaptured,
                      LocalDateTime receivedAt, Boolean outsideFence, IngestResult result) {
        this.offender = offender;
        this.clientPointId = clientPointId;
        this.pointTime = pointTime;
        this.lat = lat;
        this.lng = lng;
        this.offlineCaptured = offlineCaptured;
        this.receivedAt = receivedAt;
        this.outsideFence = outsideFence;
        this.result = result;
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
    public IngestResult getResult() { return result; }
}
