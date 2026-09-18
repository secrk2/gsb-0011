package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.FenceZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FenceZoneRepository extends JpaRepository<FenceZone, Long> {

    List<FenceZone> findByOffice_IdAndEnabledTrueOrderByIdAsc(Long officeId);

    List<FenceZone> findByOffice_IdOrderByKindAscIdAsc(Long officeId);
}
