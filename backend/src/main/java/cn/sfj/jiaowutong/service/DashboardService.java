package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.vo.DashboardView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 矫务作战台聚合：
 * - 各司法所在矫漏斗（入矫登记 / 在矫 / 请假外出 / 训诫 / 收监 / 解除）；
 * - 今日应报到：按对象规定报到星期匹配，标注是否已报到、是否逾时未报；
 * - 红点：未处置的越界/未报到/训诫事件，按数据范围过滤。
 */
@Service
public class DashboardService {

    /** 晚于该时刻仍未报到视为逾时（红点） */
    private static final LocalTime OVERDUE_AFTER = LocalTime.of(18, 0);

    private final JudicialOfficeRepository officeRepository;
    private final CorrectionObjectRepository objectRepository;
    private final CheckInRepository checkInRepository;
    private final ViolationEventRepository violationRepository;

    public DashboardService(JudicialOfficeRepository officeRepository,
                            CorrectionObjectRepository objectRepository,
                            CheckInRepository checkInRepository,
                            ViolationEventRepository violationRepository) {
        this.officeRepository = officeRepository;
        this.objectRepository = objectRepository;
        this.checkInRepository = checkInRepository;
        this.violationRepository = violationRepository;
    }

    @Transactional(readOnly = true)
    public DashboardView build(LoginUser user) {
        // 服务器统一 UTC；各司法所“今天/现在几点”按其所配置时区分别换算，不拿一个服务器本地日套用全区
        LocalDateTime nowUtc = cn.sfj.jiaowutong.common.time.TimeZones.utcNow();
        LocalDate serverLocalToday = cn.sfj.jiaowutong.common.time.TimeZones
                .localDate(nowUtc, cn.sfj.jiaowutong.common.time.TimeZones.DEFAULT_ZONE);

        List<JudicialOffice> offices = officeRepository.findAll();
        List<CorrectionObject> all = objectRepository.findAll();

        // 数据范围：监管员全区；干警本所；对象本人（作战台对对象仅给本人摘要）
        List<CorrectionObject> scoped;
        if (user.role() == Role.SUPERVISOR) {
            scoped = all;
        } else if (user.role() == Role.STAFF) {
            scoped = all.stream().filter(o -> user.officeId().equals(o.getOffice().getId())).toList();
        } else {
            scoped = all.stream()
                    .filter(o -> user.offenderId() != null && user.offenderId().equals(o.getId()))
                    .toList();
        }

        Map<CorrectionStatus, Long> globalCounts = new EnumMap<>(CorrectionStatus.class);
        for (CorrectionStatus s : CorrectionStatus.values()) {
            globalCounts.put(s, 0L);
        }
        for (CorrectionObject o : scoped) {
            globalCounts.merge(o.getStatus(), 1L, Long::sum);
        }
        Map<String, Long> globalFunnel = new LinkedHashMap<>();
        for (CorrectionStatus s : List.of(CorrectionStatus.INTAKE, CorrectionStatus.SERVING,
                CorrectionStatus.LEAVE, CorrectionStatus.ADMONISHED,
                CorrectionStatus.REIMPRISONED, CorrectionStatus.RELEASED)) {
            globalFunnel.put(s.name(), globalCounts.get(s));
        }

        List<DashboardView.OfficeFunnel> officeFunnels = new ArrayList<>();
        for (JudicialOffice office : offices) {
            List<CorrectionObject> inOffice = all.stream()
                    .filter(o -> o.getOffice().getId().equals(office.getId()))
                    .toList();
            // 干警只能看到本所卡片；监管员看全部；对象视角不暴露其他所
            if (user.role() == Role.STAFF && !office.getId().equals(user.officeId())) {
                continue;
            }
            if (user.role() == Role.OFFENDER
                    && inOffice.stream().noneMatch(o -> o.getId().equals(user.offenderId()))) {
                continue;
            }
            long intake = inOffice.stream().filter(o -> o.getStatus() == CorrectionStatus.INTAKE).count();
            long serving = inOffice.stream().filter(o -> o.getStatus() == CorrectionStatus.SERVING).count();
            long leave = inOffice.stream().filter(o -> o.getStatus() == CorrectionStatus.LEAVE).count();
            long admonished = inOffice.stream().filter(o -> o.getStatus() == CorrectionStatus.ADMONISHED).count();
            long reimprisoned = inOffice.stream().filter(o -> o.getStatus() == CorrectionStatus.REIMPRISONED).count();
            long released = inOffice.stream().filter(o -> o.getStatus() == CorrectionStatus.RELEASED).count();
            // 在矫口径总量：在矫+请假外出+训诫（监外执行中）
            long activeTotal = serving + leave + admonished;
            officeFunnels.add(new DashboardView.OfficeFunnel(
                    office.getId(), office.getName(), office.getRegion(),
                    intake, serving, leave, admonished, reimprisoned, released, activeTotal));
        }

        // 今日应报到：每个对象按其司法所时区的“当地今天星期几/几点”判定（跨时区不串日）
        List<DashboardView.DueTodayItem> due = scoped.stream()
                .filter(o -> EnumSet.of(CorrectionStatus.SERVING, CorrectionStatus.LEAVE,
                        CorrectionStatus.ADMONISHED).contains(o.getStatus()))
                .map(o -> {
                    String zone = o.getOffice().getTimezone();
                    var zonedNow = cn.sfj.jiaowutong.common.time.TimeZones.nowAt(zone);
                    LocalDate localToday = zonedNow.toLocalDate();
                    String localWeek = zonedNow.getDayOfWeek().toString();
                    boolean dueToday = localWeek.equals(o.getReportDay());
                    if (!dueToday) {
                        return null;
                    }
                    boolean checked = checkInRepository.existsByOffender_IdAndCheckDate(o.getId(), localToday);
                    boolean overdue = !checked && zonedNow.toLocalTime().isAfter(OVERDUE_AFTER);
                    return new DashboardView.DueTodayItem(
                            o.getId(), o.getCorrectionNo(), o.getMaskedName(),
                            o.getOffice().getName(), o.getReportDay(),
                            checked, overdue);
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(DashboardView.DueTodayItem::correctionNo))
                .toList();

        // 红点：未处置事件，按范围过滤
        List<DashboardView.RedDotItem> redDots = violationRepository.findAll().stream()
                .filter(v -> !v.getReadFlag())
                .filter(v -> scoped.stream().anyMatch(o -> o.getId().equals(v.getOffender().getId())))
                .sorted(Comparator.comparing(ViolationEvent::getEventTime).reversed())
                .limit(30)
                .map(v -> {
                    CorrectionObject o = v.getOffender();
                    return ObjectService.toRedDot(v, o);
                })
                .toList();

        return new DashboardView(serverLocalToday.toString(), user.role().name(), globalFunnel,
                officeFunnels, due, redDots, redDots.size());
    }
}
