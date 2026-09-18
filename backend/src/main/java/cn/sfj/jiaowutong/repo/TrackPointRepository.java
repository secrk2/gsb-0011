package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.TrackPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {

    /** 幂等判断：同一对象同一客户端点 ID 已接收过（含漂移丢弃点，重放不复活） */
    boolean existsByOffender_IdAndClientPointId(Long offenderId, String clientPointId);

    List<TrackPoint> findByOffender_IdOrderByPointTimeAscIdAsc(Long offenderId);

    List<TrackPoint> findByOffender_IdAndResultOrderByPointTimeAscIdAsc(
            Long offenderId, TrackPoint.IngestResult result);

    List<TrackPoint> findByOffender_IdAndResultAndPointTimeBetweenOrderByPointTimeAscIdAsc(
            Long offenderId, TrackPoint.IngestResult result,
            LocalDateTime fromUtc, LocalDateTime toUtc);

    long countByOffender_IdAndResult(Long offenderId, TrackPoint.IngestResult result);

    /** 清除轨迹：只删有效轨迹点；漂移丢弃点作为质量留痕保留 */
    @Modifying
    @Query("delete from TrackPoint t where t.offender.id = :offenderId and t.result = :result")
    int deleteAcceptedByOffender(Long offenderId, TrackPoint.IngestResult result);
}
