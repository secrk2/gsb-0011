package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.TrackPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {

    /** 幂等判断：同一对象同一客户端点 ID 已接收过 */
    boolean existsByOffender_IdAndClientPointId(Long offenderId, String clientPointId);

    List<TrackPoint> findByOffender_IdOrderByPointTimeAscIdAsc(Long offenderId);
}
