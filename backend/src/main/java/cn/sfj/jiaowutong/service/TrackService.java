package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.CorrectionObjectRepository;
import cn.sfj.jiaowutong.repo.TrackPointRepository;
import cn.sfj.jiaowutong.repo.ViolationEventRepository;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.dto.TrackBatchRequest;
import cn.sfj.jiaowutong.web.vo.TrackIngestView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 定位轨迹上报，服务端强约束（不依赖手机端自觉）：
 * 1. 防旧位置糊弄：实时点（offlineCaptured=false）采集时间距服务端时钟不得超过 {@link #REALTIME_SKEW_MIN} 分钟，
 *    离线补传点不得早于 {@link #OFFLINE_MAX_AGE_HOURS} 小时，亦不得来自未来；
 * 2. 幂等：同一对象同一 clientPointId 只入库一次，断网恢复重放不产生重复轨迹点；
 * 3. 合并：批量点按「采集时间」合并进轨迹，对象最新位置取所有已接收点中采集时间最大者，
 *    乱序/迟到批次不会让位置回退；
 * 4. 越界：按司法所围栏判定，仅在「围栏内→围栏外」边沿生成一次红点，避免补传历史点刷屏。
 */
@Service
public class TrackService {

    /** 实时点允许的时钟偏移（分钟） */
    private static final long REALTIME_SKEW_MIN = 5;
    /** 离线补传点最大可追溯时长（小时） */
    private static final long OFFLINE_MAX_AGE_HOURS = 72;
    /** 容忍的手机时钟超前（分钟） */
    private static final long FUTURE_SKEW_MIN = 2;

    private final TrackPointRepository trackPointRepository;
    private final CorrectionObjectRepository objectRepository;
    private final ViolationEventRepository violationRepository;

    public TrackService(TrackPointRepository trackPointRepository,
                        CorrectionObjectRepository objectRepository,
                        ViolationEventRepository violationRepository) {
        this.trackPointRepository = trackPointRepository;
        this.objectRepository = objectRepository;
        this.violationRepository = violationRepository;
    }

    @Transactional
    public TrackIngestView ingest(TrackBatchRequest request, LoginUser user) {
        if (user.role() != Role.OFFENDER || user.offenderId() == null) {
            throw ApiException.forbidden("仅矫正对象本人账号可上报定位轨迹");
        }
        CorrectionObject obj = objectRepository.findById(user.offenderId())
                .orElseThrow(() -> ApiException.notFound("本人档案不存在"));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime realtimeFloor = now.minusMinutes(REALTIME_SKEW_MIN);
        LocalDateTime offlineFloor = now.minusHours(OFFLINE_MAX_AGE_HOURS);
        LocalDateTime futureCeil = now.plusMinutes(FUTURE_SKEW_MIN);

        int accepted = 0;
        int duplicates = 0;
        int outsideCount = 0;
        boolean acceptedOutside = false;
        List<TrackIngestView.RejectedPoint> rejected = new ArrayList<>();
        List<TrackPoint> fresh = new ArrayList<>();
        // 同一批次内的幂等去重：离线重放可能把相同 clientPointId 在一个请求里发来多次
        java.util.Set<String> seenInBatch = new java.util.HashSet<>();

        for (TrackBatchRequest.PointDto p : request.points()) {
            // 幂等优先：库内已接收，或本批已出现过，直接跳过，不入库
            if (!seenInBatch.add(p.clientPointId())
                    || trackPointRepository.existsByOffender_IdAndClientPointId(obj.getId(), p.clientPointId())) {
                duplicates++;
                continue;
            }

            // 时效校验：拒绝旧位置/未来时间
            if (p.pointTime().isAfter(futureCeil)) {
                rejected.add(new TrackIngestView.RejectedPoint(p.clientPointId(),
                        "定位时间晚于当前时间，疑似伪造定位（" + p.pointTime() + "）"));
                continue;
            }
            if (Boolean.FALSE.equals(p.offlineCaptured()) && p.pointTime().isBefore(realtimeFloor)) {
                rejected.add(new TrackIngestView.RejectedPoint(p.clientPointId(),
                        "实时上报点采集于 " + p.pointTime() + "，已超过 " + REALTIME_SKEW_MIN
                                + " 分钟时效，禁止用缓存旧位置冒充当前位置"));
                continue;
            }
            if (Boolean.TRUE.equals(p.offlineCaptured()) && p.pointTime().isBefore(offlineFloor)) {
                rejected.add(new TrackIngestView.RejectedPoint(p.clientPointId(),
                        "离线补传点超出 " + OFFLINE_MAX_AGE_HOURS + " 小时可追溯窗口，不予采信"));
                continue;
            }

            boolean outside = !GeoUtil.isInside(p.lat(), p.lng(),
                    obj.getOffice().getCenterLat(), obj.getOffice().getCenterLng(),
                    obj.getOffice().getFenceRadiusMeters());
            TrackPoint point = new TrackPoint(obj, p.clientPointId(), p.pointTime(),
                    p.lat(), p.lng(), p.offlineCaptured(), now, outside,
                    TrackPoint.IngestResult.ACCEPTED);
            fresh.add(point);
            accepted++;
            if (outside) {
                outsideCount++;
                acceptedOutside = true;
            }
        }

        if (!fresh.isEmpty()) {
            trackPointRepository.saveAll(fresh);
        }

        // 3. 合并最新位置：全部已接收轨迹中采集时间最大者（离线历史点不会把当前位置冲回旧位置）
        List<TrackPoint> all = trackPointRepository.findByOffender_IdOrderByPointTimeAscIdAsc(obj.getId());
        TrackPoint latest = all.stream()
                .max(Comparator.comparing(TrackPoint::getPointTime).thenComparing(TrackPoint::getId))
                .orElse(null);

        boolean newViolation = false;
        if (latest != null) {
            obj.setLastLocationAt(latest.getPointTime());
            obj.setLastLat(latest.getLat());
            obj.setLastLng(latest.getLng());
            obj.setLastInsideFence(!latest.getOutsideFence());
            objectRepository.save(obj);

            // 4. 越界红点边沿生成：仅当最新位置确在围栏外、且没有未处置的越界红点时报警一次，
            //    批量补传多个越界历史点不会刷屏
            boolean countedStatus = obj.getStatus() == CorrectionStatus.SERVING
                    || obj.getStatus() == CorrectionStatus.ADMONISHED;
            if (countedStatus && latest.getOutsideFence() && !alreadyOpenBreach(obj.getId())) {
                violationRepository.save(new ViolationEvent(obj, "GEOFENCE_BREACH",
                        "对象 " + obj.getMaskedName() + " 定位越出「" + obj.getOffice().getName()
                                + "」电子围栏（半径 " + obj.getOffice().getFenceRadiusMeters() + " 米），最近定位时间 "
                                + latest.getPointTime(),
                        now));
                newViolation = true;
            }
        }

        int rejectedCount = rejected.size();
        return new TrackIngestView(request.points().size(), accepted, duplicates, rejectedCount,
                rejected, outsideCount,
                obj.getLastLocationAt(), obj.getLastLat(), obj.getLastLng(),
                !Boolean.FALSE.equals(obj.getLastInsideFence()), newViolation);
    }

    /**
     * 是否已有未处置的越界红点：有则不重复生成（批量补传多个越界历史点只报一次警）。
     */
    private boolean alreadyOpenBreach(Long offenderId) {
        return violationRepository.findTop20ByOffender_IdOrderByEventTimeDesc(offenderId).stream()
                .filter(v -> "GEOFENCE_BREACH".equals(v.getType()))
                .findFirst()
                .map(v -> !v.getReadFlag())
                .orElse(false);
    }
}
