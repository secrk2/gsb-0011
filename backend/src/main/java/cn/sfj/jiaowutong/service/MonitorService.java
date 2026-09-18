package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.common.time.TimeZones;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.dto.ClearTracksRequest;
import cn.sfj.jiaowutong.web.dto.MarkBreachRequest;
import cn.sfj.jiaowutong.web.vo.MonitorOverviewView;
import cn.sfj.jiaowutong.web.vo.MonitorView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 定位监控（干警/监管员侧）：
 * - 时间全部 UTC 存储，窗口（周/月）先按司法所<b>本地日</b>界定再换算 UTC 查询，
 *   跨时区不会把“昨天/明天”的点错算进来；
 * - 轨迹 5 秒一点、月视图数十万点无法直画，服务端等间隔抽稀，越界点全部保留；
 * - 完成度双口径（打卡天数 / 关键报到）同屏给出并注明口径，避免“只在非规定日零散报到”的对象被单口径美化；
 * - 标记越界服务端独立复核落点确在活动范围外；清除轨迹写留痕，供三态空态区分。
 */
@Service
public class MonitorService {

    /** 周视图最多直绘点数 */
    private static final int MAX_WEEK_POINTS = 700;
    /** 月视图最多直绘点数 */
    private static final int MAX_MONTH_POINTS = 900;
    /** 越界点保底保留上限 */
    private static final int MAX_OUTSIDE_KEPT = 200;
    /** 漂移丢弃点回显上限 */
    private static final int MAX_DROPPED_SHOWN = 100;

    public static final String CALIBER_NOTE =
            "口径①打卡天数＝矫正期内实际报到的不重复天数（当天多次只计1天）÷矫正期已过天数；"
                    + "口径②关键报到＝规定报到星期当天完成次数÷到期应完成次数。"
                    + "只在非规定日零散报到的对象，口径①可能不低但口径②为0，处置以口径②为准。";

    private final CorrectionObjectRepository objectRepository;
    private final JudicialOfficeRepository officeRepository;
    private final TrackPointRepository trackPointRepository;
    private final CheckInRepository checkInRepository;
    private final FenceZoneRepository fenceZoneRepository;
    private final LocationMarkRepository markRepository;
    private final TrackClearRecordRepository clearRepository;
    private final ViolationEventRepository violationRepository;
    private final AccessControlService accessControl;
    private final FenceService fenceService;

    public MonitorService(CorrectionObjectRepository objectRepository,
                          JudicialOfficeRepository officeRepository,
                          TrackPointRepository trackPointRepository,
                          CheckInRepository checkInRepository,
                          FenceZoneRepository fenceZoneRepository,
                          LocationMarkRepository markRepository,
                          TrackClearRecordRepository clearRepository,
                          ViolationEventRepository violationRepository,
                          AccessControlService accessControl,
                          FenceService fenceService) {
        this.objectRepository = objectRepository;
        this.officeRepository = officeRepository;
        this.trackPointRepository = trackPointRepository;
        this.checkInRepository = checkInRepository;
        this.fenceZoneRepository = fenceZoneRepository;
        this.markRepository = markRepository;
        this.clearRepository = clearRepository;
        this.violationRepository = violationRepository;
        this.accessControl = accessControl;
        this.fenceService = fenceService;
    }

    // ============================ 总览 ============================

    @Transactional(readOnly = true)
    public MonitorOverviewView overview(LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        LocalDateTime nowUtc = TimeZones.utcNow();
        List<JudicialOffice> offices = officeRepository.findAll();

        List<JudicialOffice> visibleOffices = offices.stream()
                .filter(o -> user.role() == Role.SUPERVISOR
                        || (user.role() == Role.STAFF && o.getId().equals(user.officeId())))
                .toList();

        List<MonitorOverviewView.OfficeOption> officeOpts = visibleOffices.stream()
                .map(o -> {
                    DayOfWeek fw = TimeZones.parseWeekday(o.getForbiddenWeekday());
                    return new MonitorOverviewView.OfficeOption(o.getId(), o.getName(), o.getRegion(),
                            o.getTimezone(), TimeZones.offsetLabel(o.getTimezone()),
                            fw == null ? null : fw.name(), fw == null ? null : TrackService.weekCn(fw),
                            TimeZones.todayIsForbidden(o.getTimezone(), fw));
                }).toList();

        List<CorrectionObject> scoped = accessControl.filterByScope(objectRepository.findAll(), user).stream()
                .filter(o -> EnumSetMonitored.contains(o.getStatus()))
                .toList();

        List<CheckIn> allCheckins = checkInRepository.findByOffender_IdIn(
                scoped.stream().map(CorrectionObject::getId).toList());

        List<MonitorOverviewView.SubjectRow> rows = new ArrayList<>();
        long checkinDaysSum = 0, servingDaysSum = 0, keySum = 0, keyDueSum = 0;

        for (CorrectionObject o : scoped) {
            List<CheckIn> mine = allCheckins.stream().filter(c -> c.getOffender().getId().equals(o.getId())).toList();
            LocalDate localToday = TimeZones.localDate(nowUtc, o.getOffice().getTimezone());
            CompletionNumbers cc = completion(o, mine, localToday);
            String trackState = trackState(o.getId());

            boolean stale = o.getLastLocationAt() != null
                    && Duration.between(o.getLastLocationAt(), nowUtc).getSeconds() > TrackService.STALE_FIX_SECONDS;
            DayOfWeek fw = TimeZones.parseWeekday(o.getOffice().getForbiddenWeekday());

            rows.add(new MonitorOverviewView.SubjectRow(
                    o.getId(), o.getCorrectionNo(), o.getMaskedName(),
                    o.getOffice().getId(), o.getOffice().getName(),
                    o.getStatus().name(), o.getStatus().getLabel(), o.getOffice().getTimezone(),
                    o.getLastLocationAt() == null ? null : o.getLastLocationAt().toString() + "Z",
                    o.getLastInsideFence(), o.getLastDeviceStatus() == null ? "NONE" : o.getLastDeviceStatus(),
                    o.getLastBatteryPercent(), stale,
                    TimeZones.todayIsForbidden(o.getOffice().getTimezone(), fw),
                    trackState,
                    cc.checkinDays, cc.keyCheckins, cc.keyDue,
                    cc.checkinPercent, cc.keyPercent, cc.diverge()));

            checkinDaysSum += cc.checkinDays;
            servingDaysSum += cc.servingDays;
            keySum += cc.keyCheckins;
            keyDueSum += cc.keyDue;
        }

        int aggCheckinPct = servingDaysSum == 0 ? 0 : (int) Math.min(100, checkinDaysSum * 100 / servingDaysSum);
        int aggKeyPct = keyDueSum == 0 ? 0 : (int) Math.min(100, keySum * 100 / keyDueSum);

        return new MonitorOverviewView(nowUtc + "Z", officeOpts, rows,
                new MonitorOverviewView.CompletionAggregate(scoped.size(),
                        checkinDaysSum, servingDaysSum, aggCheckinPct,
                        keySum, keyDueSum, aggKeyPct, CALIBER_NOTE));
    }

    // ============================ 单对象监控 ============================

    @Transactional(readOnly = true)
    public MonitorView monitor(Long id, String range, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);
        JudicialOffice office = o.getOffice();
        String zone = office.getTimezone();
        LocalDateTime nowUtc = TimeZones.utcNow();
        boolean month = "MONTH".equalsIgnoreCase(range);

        // 窗口：先取司法所本地日，再换算 UTC。周=本地自然周（周一至周日），月=本地自然月
        LocalDate localToday = TimeZones.localDate(nowUtc, zone);
        LocalDate localStart = month
                ? localToday.with(TemporalAdjusters.firstDayOfMonth())
                : localToday.with(DayOfWeek.MONDAY);
        LocalDate localEnd = month
                ? localToday.with(TemporalAdjusters.lastDayOfMonth())
                : localToday.with(DayOfWeek.SUNDAY);
        LocalDateTime fromUtc = LocalDateTime.ofInstant(localStart.atStartOfDay(TimeZones.zone(zone)).toInstant(), ZoneOffset.UTC);
        LocalDateTime toUtc = LocalDateTime.ofInstant(
                localEnd.plusDays(1).atStartOfDay(TimeZones.zone(zone)).toInstant(), ZoneOffset.UTC);

        // 轨迹（只取采信点）并抽稀
        List<TrackPoint> raw = trackPointRepository
                .findByOffender_IdAndResultAndPointTimeBetweenOrderByPointTimeAscIdAsc(
                        id, TrackPoint.IngestResult.ACCEPTED, fromUtc, toUtc);
        List<TrackPoint> tracks = thin(raw, month ? MAX_MONTH_POINTS : MAX_WEEK_POINTS);

        // 漂移丢弃点（独立图层）
        List<TrackPoint> droppedAll = trackPointRepository
                .findByOffender_IdAndResultAndPointTimeBetweenOrderByPointTimeAscIdAsc(
                        id, TrackPoint.IngestResult.DRIFT_DROPPED, fromUtc, toUtc);
        List<TrackPoint> dropped = droppedAll.size() > MAX_DROPPED_SHOWN
                ? droppedAll.subList(droppedAll.size() - MAX_DROPPED_SHOWN, droppedAll.size())
                : droppedAll;

        String trackState = trackState(id);

        // 当前状态（以最新采信点为准，不随抽稀丢失）
        MonitorView.CurrentStatus current = buildCurrent(o, nowUtc);

        List<FenceZone> zones = fenceZoneRepository.findByOffice_IdAndEnabledTrueOrderByIdAsc(office.getId());
        List<MonitorView.FenceView> fenceViews = zones.stream()
                .map(z -> new MonitorView.FenceView(z.getId(), z.getName(), z.getKind(),
                        "FORBIDDEN".equals(z.getKind()) ? "禁区" : "活动范围",
                        FenceService.parseRing(z.getRingText())))
                .toList();

        List<MonitorView.TrackPointView> trackViews = tracks.stream().map(t -> {
            FenceService.FenceVerdict v = fenceService.evaluate(office, t.getLat(), t.getLng(), zones);
            return new MonitorView.TrackPointView(t.getId(),
                    t.getPointTime().toString() + "Z", TimeZones.fmtWall(t.getPointTime(), zone),
                    t.getLat(), t.getLng(), Boolean.TRUE.equals(t.getOfflineCaptured()),
                    t.getDeviceStatus(), t.getBatteryPercent(),
                    Boolean.TRUE.equals(t.getOutsideFence()), v.code());
        }).toList();

        List<MonitorView.DroppedPointView> droppedViews = dropped.stream()
                .map(t -> new MonitorView.DroppedPointView(
                        t.getPointTime().toString() + "Z", TimeZones.fmtWall(t.getPointTime(), zone),
                        t.getLat(), t.getLng(), t.getDriftReason()))
                .toList();

        List<MonitorView.MarkView> markViews = markRepository.findByOffender_IdOrderByMarkedAtDescIdDesc(id).stream()
                .filter(m -> !m.getPointTime().isBefore(fromUtc) && m.getPointTime().isBefore(toUtc))
                .map(m -> new MonitorView.MarkView(m.getId(), m.getTrackPointId(), m.getLat(), m.getLng(),
                        m.getPointTime().toString() + "Z", TimeZones.fmtWall(m.getPointTime(), zone),
                        m.getReason(), m.getVerdictReason(), m.getMarkerUserName(),
                        m.getMarkedAt().toString() + "Z", TimeZones.fmtWall(m.getMarkedAt(), zone)))
                .toList();

        List<CheckIn> mine = checkInRepository.findByOffender_Id(id);
        CompletionNumbers cc = completion(o, mine, localToday);

        TrackClearRecord lastClear = clearRepository
                .findFirstByOffenderIdOrderByClearedAtDescIdDesc(id).orElse(null);
        MonitorView.LastClear lastClearView = lastClear == null ? null
                : new MonitorView.LastClear(lastClear.getClearedAt().toString() + "Z",
                        TimeZones.fmtWall(lastClear.getClearedAt(), zone),
                        lastClear.getOperatorUserName(), lastClear.getReason(), lastClear.getPointCount());

        DayOfWeek fw = TimeZones.parseWeekday(office.getForbiddenWeekday());
        MonitorView.Subject subject = new MonitorView.Subject(
                o.getId(), o.getCorrectionNo(), o.getMaskedName(),
                o.getStatus().name(), o.getStatus().getLabel(), office.getName());

        return new MonitorView(nowUtc + "Z", subject, month ? "MONTH" : "WEEK",
                zone, TimeZones.offsetLabel(zone),
                localToday.toString(), TrackService.weekCn(localToday.getDayOfWeek()),
                fw == null ? null : fw.name(), fw == null ? null : TrackService.weekCn(fw),
                TimeZones.todayIsForbidden(zone, fw),
                fromUtc + "Z", toUtc + "Z", trackState, current,
                new MonitorView.CircleFence(office.getCenterLat(), office.getCenterLng(), office.getFenceRadiusMeters()),
                fenceViews, trackViews, droppedViews, markViews,
                new MonitorView.Completion(cc.checkinDays, cc.servingDays, cc.checkinPercent,
                        cc.keyCheckins, cc.keyDue, cc.keyPercent, cc.diverge(), CALIBER_NOTE),
                lastClearView);
    }

    // ============================ 实时心跳（5 秒） ============================

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> live(Long id, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);
        LocalDateTime nowUtc = TimeZones.utcNow();
        MonitorView.CurrentStatus current = buildCurrent(o, nowUtc);
        DayOfWeek fw = TimeZones.parseWeekday(o.getOffice().getForbiddenWeekday());
        return java.util.Map.of(
                "serverNowUtc", nowUtc + "Z",
                "current", current == null ? java.util.Collections.emptyMap() : current,
                "todayForbiddenDay", TimeZones.todayIsForbidden(o.getOffice().getTimezone(), fw),
                "trackState", trackState(id));
    }

    private MonitorView.CurrentStatus buildCurrent(CorrectionObject o, LocalDateTime nowUtc) {
        String zone = o.getOffice().getTimezone();
        if (o.getLastLocationAt() == null || o.getLastLat() == null || o.getLastLng() == null) {
            return null;
        }
        FenceService.FenceVerdict v = fenceService.evaluate(o.getOffice(), o.getLastLat(), o.getLastLng());
        long age = Duration.between(o.getLastLocationAt(), nowUtc).getSeconds();
        String code = deviceCode(o);
        return new MonitorView.CurrentStatus(
                o.getLastLocationAt().toString() + "Z",
                TimeZones.fmtWall(o.getLastLocationAt(), zone),
                o.getLastLat(), o.getLastLng(),
                Boolean.TRUE.equals(o.getLastInsideFence()),
                Boolean.TRUE.equals(o.getLastInsideFence()) ? "INSIDE" : "OUTSIDE",
                verdictLabel(o, v, zone),
                code, deviceLabel(code),
                o.getLastBatteryPercent(), age, age > TrackService.STALE_FIX_SECONDS);
    }

    // ============================ 标记越界（二次确认 + 服务端复核） ============================

    @Transactional
    public MonitorView.MarkView markBreach(Long id, MarkBreachRequest req, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);
        String zone = o.getOffice().getTimezone();
        LocalDateTime nowUtc = TimeZones.utcNow();

        Double lat;
        Double lng;
        LocalDateTime pointUtc;
        Long trackPointId = req.trackPointId();

        if (trackPointId != null) {
            TrackPoint p = trackPointRepository.findById(trackPointId)
                    .orElseThrow(() -> ApiException.notFound("轨迹点不存在：" + trackPointId));
            if (!p.getOffender().getId().equals(id)) {
                throw ApiException.badRequest("MARK_INVALID", "该轨迹点不属于此对象，不能标记");
            }
            if (p.getResult() == TrackPoint.IngestResult.DRIFT_DROPPED) {
                throw ApiException.badRequest("MARK_DRIFT",
                        "该落点已被判定为 GPS 漂移并丢弃，位置不可信，不能据此标记越界");
            }
            if (markRepository.existsByTrackPointId(trackPointId)) {
                throw ApiException.badRequest("MARK_DUPLICATE", "该落点已被标记过越界，请勿重复标记");
            }
            lat = p.getLat();
            lng = p.getLng();
            pointUtc = p.getPointTime();
        } else {
            if (req.lat() == null || req.lng() == null || req.pointTimeIso() == null || req.pointTimeIso().isBlank()) {
                throw ApiException.badRequest("MARK_INVALID", "手工标记须提供坐标与落点时间");
            }
            try {
                pointUtc = OffsetDateTime.parse(req.pointTimeIso())
                        .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } catch (Exception e) {
                throw ApiException.badRequest("MARK_INVALID", "落点时间格式不正确，须为带时区偏移的 ISO-8601");
            }
            lat = req.lat();
            lng = req.lng();
        }

        // 服务端独立复核：落点必须确实在活动范围外/禁区内，防止干警误标制造假红点
        FenceService.FenceVerdict verdict = fenceService.evaluate(o.getOffice(), lat, lng);
        if (!verdict.outside()) {
            throw ApiException.badRequest("MARK_INSIDE",
                    "服务端复核未通过：该落点在规定活动范围内（" + verdict.reason() + "），不能标记为越界");
        }

        DayOfWeek fw = TimeZones.parseWeekday(o.getOffice().getForbiddenWeekday());
        boolean forbiddenDay = TimeZones.isForbiddenWeekday(pointUtc, fw, zone);
        String verdictReason = (forbiddenDay ? "禁行日越界；" : "") + verdict.reason();

        LocationMark mark = new LocationMark(o, trackPointId, lat, lng, pointUtc,
                req.reason(), verdictReason, user.userId(), user.realName(), nowUtc);
        markRepository.save(mark);

        violationRepository.save(new ViolationEvent(o, "MARK_BREACH",
                "干警 " + user.realName() + " 人工标记对象 " + o.getMaskedName() + " 落点越界（"
                        + verdictReason + "），落点时间 " + TimeZones.fmtWall(pointUtc, zone)
                        + "，标记原因：" + req.reason(), nowUtc));

        return new MonitorView.MarkView(mark.getId(), trackPointId, lat, lng,
                pointUtc.toString() + "Z", TimeZones.fmtWall(pointUtc, zone),
                req.reason(), verdictReason, user.realName(),
                nowUtc.toString() + "Z", TimeZones.fmtWall(nowUtc, zone));
    }

    // ============================ 清除轨迹（留痕） ============================

    @Transactional
    public java.util.Map<String, Object> clearTracks(Long id, ClearTracksRequest req, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);

        int count = (int) trackPointRepository.countByOffender_IdAndResult(id, TrackPoint.IngestResult.ACCEPTED);
        if (count == 0) {
            throw ApiException.badRequest("NO_TRACKS", "该对象当前没有可清除的有效轨迹点");
        }
        int deleted = trackPointRepository.deleteAcceptedByOffender(id, TrackPoint.IngestResult.ACCEPTED);
        LocalDateTime nowUtc = TimeZones.utcNow();
        clearRepository.save(new TrackClearRecord(
                id, deleted, req.reason(), user.userId(), user.realName(), nowUtc));

        // 最新位置随之失效（漂移点不采信，不能顶替为当前位置）
        o.setLastLocationAt(null);
        o.setLastLat(null);
        o.setLastLng(null);
        o.setLastInsideFence(null);
        o.setLastDeviceStatus(null);
        o.setLastBatteryPercent(null);
        objectRepository.save(o);

        return java.util.Map.of(
                "cleared", deleted,
                "clearedAtUtc", nowUtc.toString() + "Z",
                "operator", user.realName(),
                "message", "已清除 " + deleted + " 个有效轨迹点并留痕；漂移丢弃点作为质量记录保留");
    }

    // ============================ 内部工具 ============================

    private String trackState(Long id) {
        long accepted = trackPointRepository.countByOffender_IdAndResult(id, TrackPoint.IngestResult.ACCEPTED);
        if (accepted > 0) {
            return "ACTIVE";
        }
        return clearRepository.findFirstByOffenderIdOrderByClearedAtDescIdDesc(id).isPresent()
                ? "CLEARED" : "EMPTY";
    }

    /**
     * 等间隔抽稀：保留首尾，按索引步长取样；越界点（outside=true）额外全保留（有上限），
     * 保证跨区长连线不丢失关键边沿。
     */
    private List<TrackPoint> thin(List<TrackPoint> raw, int max) {
        if (raw.size() <= max) {
            return raw;
        }
        Set<Long> keepIds = new LinkedHashSet<>();
        long step = Math.max(1, Math.round((double) raw.size() / max));
        for (int i = 0; i < raw.size(); i += step) {
            keepIds.add(raw.get(i).getId());
        }
        keepIds.add(raw.get(0).getId());
        keepIds.add(raw.get(raw.size() - 1).getId());
        int outsideKept = 0;
        for (TrackPoint t : raw) {
            if (Boolean.TRUE.equals(t.getOutsideFence())) {
                if (outsideKept < MAX_OUTSIDE_KEPT && keepIds.add(t.getId())) {
                    outsideKept++;
                }
            }
        }
        return raw.stream().filter(t -> keepIds.contains(t.getId())).toList();
    }

    private CompletionNumbers completion(CorrectionObject o, List<CheckIn> checkins, LocalDate localToday) {
        LocalDate start = o.getStartDate() != null ? o.getStartDate() : localToday;
        LocalDate endCap = o.getEndDate() != null && o.getEndDate().isBefore(localToday)
                ? o.getEndDate() : localToday;
        if (endCap.isBefore(start)) {
            endCap = start;
        }
        long servingDays = ChronoUnit.DAYS.between(start, endCap) + 1;

        // 口径①：矫正期窗口内的不重复报到天数
        Set<LocalDate> days = new LinkedHashSet<>();
        // 口径②：规定报到星期当天完成的关键报到
        DayOfWeek reportDay = TimeZones.parseWeekday(o.getReportDay());
        long keyCheckins = 0;
        for (CheckIn c : checkins) {
            LocalDate d = c.getCheckDate();
            if (d.isBefore(start) || d.isAfter(endCap)) {
                continue;
            }
            days.add(d);
            if (reportDay != null && d.getDayOfWeek() == reportDay) {
                keyCheckins++;
            }
        }
        // 到期应完成的关键报到次数：start..endCap 内规定星期出现次数
        long keyDue = 0;
        if (reportDay != null) {
            for (LocalDate d = start; !d.isAfter(endCap); d = d.plusDays(1)) {
                if (d.getDayOfWeek() == reportDay) {
                    keyDue++;
                }
            }
        }
        int checkinPercent = (int) Math.min(100, days.size() * 100 / Math.max(1, servingDays));
        int keyPercent = keyDue == 0 ? 0 : (int) Math.min(100, keyCheckins * 100 / keyDue);
        return new CompletionNumbers(days.size(), servingDays, checkinPercent, keyCheckins, keyDue, keyPercent);
    }

    private record CompletionNumbers(long checkinDays, long servingDays, int checkinPercent,
                                     long keyCheckins, long keyDue, int keyPercent) {
        boolean diverge() {
            // 典型“部分到”：零散打卡撑高天数口径，但关键报到一个没成；两口径结论相反
            return keyDue > 0 && keyCheckins == 0 && checkinPercent >= 30;
        }
    }

    private static final java.util.EnumSet<CorrectionStatus> EnumSetMonitored =
            java.util.EnumSet.of(CorrectionStatus.SERVING, CorrectionStatus.LEAVE, CorrectionStatus.ADMONISHED);

    private static String deviceCode(CorrectionObject o) {
        return o.getLastDeviceStatus() == null ? "NONE" : o.getLastDeviceStatus();
    }

    static String deviceLabel(String code) {
        return switch (code) {
            case "NORMAL" -> "设备正常";
            case "LOW_BATTERY" -> "低电量";
            case "NO_SIGNAL" -> "定位信号中断";
            case "POWER_OFF" -> "腕表关机";
            case "NONE" -> "无回传";
            default -> code;
        };
    }

    private String verdictCode(CorrectionObject o) {
        if (o.getLastInsideFence() == null) {
            return "UNKNOWN";
        }
        return Boolean.TRUE.equals(o.getLastInsideFence()) ? "INSIDE" : "OUTSIDE";
    }

    private String verdictLabel(CorrectionObject o, FenceService.FenceVerdict v, String zone) {
        DayOfWeek fw = TimeZones.parseWeekday(o.getOffice().getForbiddenWeekday());
        boolean fbDay = o.getLastLocationAt() != null
                && TimeZones.isForbiddenWeekday(o.getLastLocationAt(), fw, zone);
        if (Boolean.TRUE.equals(o.getLastInsideFence())) {
            return "在活动范围内";
        }
        return (fbDay ? "禁行日越界 · " : "") + v.reason();
    }
}
