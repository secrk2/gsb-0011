package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.LocationMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocationMarkRepository extends JpaRepository<LocationMark, Long> {

    List<LocationMark> findByOffender_IdOrderByMarkedAtDescIdDesc(Long offenderId);

    boolean existsByTrackPointId(Long trackPointId);
}
