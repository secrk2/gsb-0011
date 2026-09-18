package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 轨迹清除留痕。
 * 干警按工作流程清除对象历史轨迹时必须二次确认 + 填写原因，清除动作本身不可匿名。
 * 监控页据此把“轨迹全清除”（曾有点、现无有效点、有清除记录）与
 * “该对象无轨迹”（从未上报过）两种空态区分开，不允许笼统显示“暂无数据”。
 */
@Entity
@Table(name = "track_clear_record", indexes = {
        @Index(name = "idx_clear_offender", columnList = "offender_id,cleared_at")
})
public class TrackClearRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "offender_id", nullable = false)
    private Long offenderId;

    /** 本次清除的轨迹点数 */
    @Column(nullable = false)
    private Integer pointCount;

    @Column(nullable = false, length = 256)
    private String reason;

    @Column(name = "operator_user_id", nullable = false)
    private Long operatorUserId;

    @Column(name = "operator_user_name", nullable = false, length = 64)
    private String operatorUserName;

    @Column(name = "cleared_at", nullable = false)
    private LocalDateTime clearedAt;

    public TrackClearRecord() {
    }

    public TrackClearRecord(Long offenderId, Integer pointCount, String reason,
                            Long operatorUserId, String operatorUserName, LocalDateTime clearedAt) {
        this.offenderId = offenderId;
        this.pointCount = pointCount;
        this.reason = reason;
        this.operatorUserId = operatorUserId;
        this.operatorUserName = operatorUserName;
        this.clearedAt = clearedAt;
    }

    public Long getId() { return id; }
    public Long getOffenderId() { return offenderId; }
    public Integer getPointCount() { return pointCount; }
    public String getReason() { return reason; }
    public Long getOperatorUserId() { return operatorUserId; }
    public String getOperatorUserName() { return operatorUserName; }
    public LocalDateTime getClearedAt() { return clearedAt; }
}
