package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.TrackClearRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackClearRecordRepository extends JpaRepository<TrackClearRecord, Long> {

    List<TrackClearRecord> findByOffenderIdOrderByClearedAtDescIdDesc(Long offenderId);

    Optional<TrackClearRecord> findFirstByOffenderIdOrderByClearedAtDescIdDesc(Long offenderId);
}
