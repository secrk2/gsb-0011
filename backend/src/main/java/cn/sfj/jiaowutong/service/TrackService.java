package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.common.time.TimeZones;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.CorrectionObjectRepository;
import cn.sfj.jiaowutong.repo.FenceZoneRepository;
import cn.sfj.jiaowutong.repo.TrackPointRepository;
import cn.sfj.jiaowutong.repo.ViolationEventRepository;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.dto.TrackBatchRequest;
import cn.sfj.jiaowutong.web.vo.TrackIngestView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 定位轨迹上报，服务端强约束（不依赖腕表端自觉）：
 * 1. <b>时间</b>：pointTime 为带偏移的绝对时刻，统一换算 UTC 入库；“今天/禁行星期”按司法所时区判定；
 * 2. <b>防旧位置</b>：实时点采集时间距服务端时钟不得超过 {@link #REALTIME_SKEW_MIN} 分钟，
 *    离线补传点不得早于 {@link #OFFLINE_MAX_AGE_HOURS} 小时，亦不得来自未来；
 * 3. <b>GPS 漂移</b>：与最近采信点相比等效速度超 {@link GpsDriftGuard#MAX_SPEED_KMH} 的跳点丢弃，
 *    丢弃点不入轨迹连线、不冲当前位置，但留底可查；连续漂移不把锚点带飞；
 * 4. <b>幂等</b>：同一对象同一 clientPointId（含曾被漂移丢弃的点）只入库一次；
 * 5. <b>合并</b>：按采集时间合并，对象最新位置只取 ACCEPTED 点中采集时间最大者；
 * 6. <b>越界</b>：多边形活动范围/禁区优先，禁区命中或活动范围外即越界；
 *    禁行星期当天越界升级为「禁行日越界」；边沿生成红点，补传历史点不刷屏。
 */
@Service
public class TrackService {

    /** 实时点允许的时钟偏移（分钟） */
    private static final long REALTIME_SKEW_MIN = 5;
    /** 离线补传点最大可追溯时长（小时） */
    private static final long OFFLINE_MAX_AGE_HOURS = 72;
    /** 容忍的设备时钟超前（分钟） */
    private static final long FUTURE_SKEW_MIN = 2;
    /** 最近定位超过该秒数视为设备状态异常（前端置灰“信号中断”） */
    public static final long STALE_FIX_SECONDS = 300;

    private final TrackPointRepository trackPointRepository;
    private final CorrectionObjectRepository objectRepository;
    private final ViolationEventRepository violationRepository;
    private final FenceZoneRepository fenceZoneRepository;
    private final FenceService fenceService;

    public TrackService(TrackPointRepository trackPointRepository,
                        CorrectionObjectRepository objectRepository,
                        ViolationEventRepository violationRepository,
                        FenceZoneRepository fenceZoneRepository,
                        FenceService fenceService) {
        this.trackPointRepository = trackPointRepository;
        this.objectRepository = objectRepository;
        this.violationRepository = violationRepository;
        this.fenceZoneRepository = fenceZoneRepository;
        this.fenceService = fenceService;
    }

    @Transactional
    public TrackIngestView ingest(TrackBatchRequest request, LoginUser user) {
        if (user.role() != Role.OFFENDER || user.offenderId() == null) {
            throw ApiException.forbidden("仅矫正对象本人账号可上报定位轨迹");
        }
        CorrectionObject obj = objectRepository.findById(user.offenderId())
                .orElseThrow(() -> ApiException.notFound("本人档案不存在"));

        LocalDateTime now = TimeZones.utcNow();
        LocalDateTime realtimeFloor = now.minusMinutes(REALTIME_SKEW_MIN);
        LocalDateTime offlineFloor = now.minusHours(OFFLINE_MAX_AGE_HOURS);
        LocalDateTime futureCeil = now.plusMinutes(FUTURE_SKEW_MIN);

        int duplicates = 0;
        int driftDropped = 0;
        int outsideCount = 0;
        List<TrackIngestView.RejectedPoint> rejected = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        java.util.Set<String> seenInBatch = new java.util.HashSet<>();

        for (TrackBatchRequest.PointDto p : request.points()) {
            // 幂等优先：库内已接收（含漂移丢弃点）或本批已出现，直接跳过
            if (!seenInBatch.add(p.clientPointId())
                    || trackPointRepository.existsByOffender_IdAndClientPointId(obj.getId(), p.clientPointId())) {
                duplicates++;
                continue;
            }

            // 带偏移时刻 → UTC 存储分量
            LocalDateTime pointUtc = p.pointTime().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();

            // 时效校验：拒绝旧位置/未来时间
            if (pointUtc.isAfter(futureCeil)) {
                rejected.add(new TrackIngestView.RejectedPoint(p.clientPointId(),
                        "定位时间晚于当前时间，疑似伪造定位（" + pointUtc + "Z）"));
                continue;
            }
            if (Boolean.FALSE.equals(p.offlineCaptured()) && pointUtc.isBefore(realtimeFloor)) {
                rejected.add(new TrackIngestView.RejectedPoint(p.clientPointId(),
                        "实时上报点采集于 " + TimeZones.fmtWall(pointUtc, obj.getOffice().getTimezone())
                                + "（司法所当地时间），已超过 " + REALTIME_SKEW_MIN
                                + " 分钟时效，禁止用缓存旧位置冒充当前位置"));
                continue;
            }
            if (Boolean.TRUE.equals(p.offlineCaptured()) && pointUtc.isBefore(offlineFloor)) {
                rejected.add(new TrackIngestView.RejectedPoint(p.clientPointId(),
                        "离线补传点超出 " + OFFLINE_MAX_AGE_HOURS + " 小时可追溯窗口，不予采信"));
                continue;
            }

            String deviceStatus = normalizeDeviceStatus(p.deviceStatus());
            candidates.add(new Candidate(p.clientPointId(), pointUtc, p.lat(), p.lng(),
                    Boolean.TRUE.equals(p.offlineCaptured()), deviceStatus, p.batteryPercent()));
        }

        // 漂移判定需要按采集时间归位：存量采信点 + 本批候选点合并排序，锚点只沿采信点推进
        List<TrackPoint> existing = trackPointRepository
                .findByOffender_IdAndResultOrderByPointTimeAscIdAsc(obj.getId(), TrackPoint.IngestResult.ACCEPTED);
        candidates.sort(Comparator.comparing((Candidate c) -> c.pointTime).thenComparing(c -> c.clientPointId));

        List<TrackPoint> toSave = new ArrayList<>();
        int accepted = 0;
        int ei = 0;
        TrackPoint anchor = null;
        List<FenceZone> fenceZones = fenceZoneRepository
                .findByOffice_IdAndEnabledTrueOrderByIdAsc(obj.getOffice().getId());

        // 归并两路：existing 恒为 ACCEPTED（推进锚点），候选点按时序插入并受漂移校验
        for (Candidate c : candidates) {
            while (ei < existing.size() && existing.get(ei).getPointTime().isBefore(c.pointTime)) {
                anchor = existing.get(ei);
                ei++;
            }
            GpsDriftGuard.Check check = GpsDriftGuard.check(anchor, c.lat, c.lng, c.pointTime);
            if (check.drift()) {
                // 丢弃点留底（outsideFence=false 不参与越界判定，也不推进锚点；语义由 result 区分）
                toSave.add(new TrackPoint(obj, c.clientPointId, c.pointTime, c.lat, c.lng,
                        c.offline, now, false, TrackPoint.IngestResult.DRIFT_DROPPED,
                        c.deviceStatus, c.battery, check.reason()));
                driftDropped++;
                continue;
            }

            FenceService.FenceVerdict verdict = fenceService.evaluate(
                    obj.getOffice(), c.lat, c.lng, fenceZones);
            TrackPoint point = new TrackPoint(obj, c.clientPointId, c.pointTime, c.lat, c.lng,
                    c.offline, now, verdict.outside(), TrackPoint.IngestResult.ACCEPTED,
                    c.deviceStatus, c.battery, null);
            toSave.add(point);
            accepted++;
            anchor = point;
            if (verdict.outside()) {
                outsideCount++;
            }
        }
        if (!toSave.isEmpty()) {
            trackPointRepository.saveAll(toSave);
        }

        // 合并最新位置：只认 ACCEPTED 点中采集时间最大者（漂移点/乱序历史点不会把当前位置冲飞）
        List<TrackPoint> acceptedAll = trackPointRepository
                .findByOffender_IdAndResultOrderByPointTimeAscIdAsc(obj.getId(), TrackPoint.IngestResult.ACCEPTED);
        TrackPoint latest = acceptedAll.stream()
                .max(Comparator.comparing(TrackPoint::getPointTime).thenComparing(TrackPoint::getId))
                .orElse(null);

        boolean newViolation = false;
        if (latest != null) {
            obj.setLastLocationAt(latest.getPointTime());
            obj.setLastLat(latest.getLat());
            obj.setLastLng(latest.getLng());
            obj.setLastInsideFence(!latest.getOutsideFence());
            obj.setLastDeviceStatus(latest.getDeviceStatus());
            obj.setLastBatteryPercent(latest.getBatteryPercent());
            objectRepository.save(obj);

            boolean countedStatus = obj.getStatus() == CorrectionStatus.SERVING
                    || obj.getStatus() == CorrectionStatus.ADMONISHED;
            String zone = obj.getOffice().getTimezone();

            // 越界红点：区分普通越界与禁行日越界（星期按司法所时区，DST 安全）
            if (countedStatus && latest.getOutsideFence()) {
                DayOfWeek forbidden = TimeZones.parseWeekday(obj.getOffice().getForbiddenWeekday());
                boolean forbiddenDay = TimeZones.isForbiddenWeekday(latest.getPointTime(), forbidden, zone);
                if (forbiddenDay) {
                    if (!alreadyOpen(obj.getId(), "BREACH_FORBIDDEN_DAY")) {
                        violationRepository.save(new ViolationEvent(obj, "BREACH_FORBIDDEN_DAY",
                                "对象 " + obj.getMaskedName() + " 在禁行日（每"
                                        + weekCn(forbidden) + "）超出「" + obj.getOffice().getName()
                                        + "」活动范围，最近定位 " + TimeZones.fmtWall(latest.getPointTime(), zone)
                                        + "（司法所当地时间）", now));
                        newViolation = true;
                    }
                } else if (!alreadyOpen(obj.getId(), "GEOFENCE_BREACH")) {
                    violationRepository.save(new ViolationEvent(obj, "GEOFENCE_BREACH",
                            "对象 " + obj.getMaskedName() + " 定位越出「" + obj.getOffice().getName()
                                    + "」电子围栏，最近定位 " + TimeZones.fmtWall(latest.getPointTime(), zone)
                                    + "（司法所当地时间）", now));
                    newViolation = true;
                }
            }

            // 设备状态红点：关机/无信号边沿报警一次（低电只在监控页标状态，不刷红点）
            if (countedStatus && ("POWER_OFF".equals(latest.getDeviceStatus())
                    || "NO_SIGNAL".equals(latest.getDeviceStatus()))
                    && !alreadyOpen(obj.getId(), "DEVICE_ALERT")) {
                String label = "POWER_OFF".equals(latest.getDeviceStatus()) ? "腕表关机" : "腕表定位信号中断";
                violationRepository.save(new ViolationEvent(obj, "DEVICE_ALERT",
                        "对象 " + obj.getMaskedName() + " 的定位腕表" + label + "，最后回传 "
                                + TimeZones.fmtWall(latest.getPointTime(), zone) + "（司法所当地时间）", now));
            }
        }

        return new TrackIngestView(request.points().size(), accepted, duplicates, driftDropped,
                rejected.size(), rejected, outsideCount,
                latest == null ? null : latest.getPointTime().toString() + "Z",
                latest == null ? null : latest.getLat(),
                latest == null ? null : latest.getLng(),
                latest != null && !Boolean.TRUE.equals(latest.getOutsideFence()),
                latest == null ? null : latest.getDeviceStatus(),
                latest == null ? null : latest.getBatteryPercent(),
                newViolation);
    }

    private static String normalizeDeviceStatus(String s) {
        if (s == null) {
            return "NORMAL";
        }
        return switch (s.trim().toUpperCase()) {
            case "LOW_BATTERY", "NO_SIGNAL", "POWER_OFF" -> s.trim().toUpperCase();
            default -> "NORMAL";
        };
    }

    public static String weekCn(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    /** 是否已有指定类型的未处置红点：有则不重复生成（批量补传只报一次警） */
    private boolean alreadyOpen(Long offenderId, String type) {
        return violationRepository.findTop20ByOffender_IdOrderByEventTimeDesc(offenderId).stream()
                .filter(v -> type.equals(v.getType()))
                .findFirst()
                .map(v -> !v.getReadFlag())
                .orElse(false);
    }

    private record Candidate(String clientPointId, LocalDateTime pointTime, double lat, double lng,
                             boolean offline, String deviceStatus, Integer battery) {
    }
}
