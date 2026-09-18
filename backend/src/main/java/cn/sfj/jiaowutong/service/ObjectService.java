package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
public class ObjectService {

    private final CorrectionObjectRepository objectRepository;
    private final StatusTransitionRepository transitionRepository;
    private final NameViewAuditRepository nameAuditRepository;
    private final ViolationEventRepository violationRepository;
    private final TrackPointRepository trackPointRepository;
    private final CheckInRepository checkInRepository;
    private final AccessControlService accessControl;

    public ObjectService(CorrectionObjectRepository objectRepository,
                         StatusTransitionRepository transitionRepository,
                         NameViewAuditRepository nameAuditRepository,
                         ViolationEventRepository violationRepository,
                         TrackPointRepository trackPointRepository,
                         CheckInRepository checkInRepository,
                         AccessControlService accessControl) {
        this.objectRepository = objectRepository;
        this.transitionRepository = transitionRepository;
        this.nameAuditRepository = nameAuditRepository;
        this.violationRepository = violationRepository;
        this.trackPointRepository = trackPointRepository;
        this.checkInRepository = checkInRepository;
        this.accessControl = accessControl;
    }

    @Transactional(readOnly = true)
    public List<ObjectView> list(CorrectionStatus status, Long officeId, LoginUser user) {
        List<CorrectionObject> all = objectRepository.findAll();
        return accessControl.filterByScope(all, user).stream()
                .filter(o -> status == null || o.getStatus() == status)
                .filter(o -> officeId == null || officeId.equals(o.getOffice().getId()))
                .sorted(Comparator.comparing(CorrectionObject::getCorrectionNo))
                .map(o -> ObjectView.of(o, isSelfOffender(o, user)))
                .toList();
    }

    private boolean isSelfOffender(CorrectionObject o, LoginUser user) {
        return user.role() == Role.OFFENDER && o.getId().equals(user.offenderId());
    }

    @Transactional(readOnly = true)
    public ObjectDetailView detail(Long id, LoginUser user) {
        CorrectionObject o = accessControl.loadVisible(id, user);
        boolean self = isSelfOffender(o, user);
        ObjectView base = ObjectView.of(o, self);

        List<TransitionView> transitions = transitionRepository
                .findByOffenderIdOrderByOperatedAtDescIdDesc(id).stream()
                .limit(20)
                .map(t -> new TransitionView(
                        t.getFromStatus(),
                        t.getFromStatus() == null ? "—" : labelOf(t.getFromStatus()),
                        t.getToStatus(), labelOf(t.getToStatus()),
                        t.getReason(), t.getOperatorName(),
                        t.getOperatedAt().toString() + "Z",
                        cn.sfj.jiaowutong.common.time.TimeZones.fmtWall(t.getOperatedAt(),
                                o.getOffice().getTimezone())))
                .toList();

        List<DashboardView.RedDotItem> violations = violationRepository
                .findTop20ByOffender_IdOrderByEventTimeDesc(id).stream()
                .map(v -> toRedDot(v, o)).toList();

        boolean checkedToday = checkInRepository.existsByOffender_IdAndCheckDate(
                id, cn.sfj.jiaowutong.common.time.TimeZones.localDate(
                        cn.sfj.jiaowutong.common.time.TimeZones.utcNow(),
                        o.getOffice().getTimezone()));
        long trackCount = trackPointRepository
                .countByOffender_IdAndResult(id, TrackPoint.IngestResult.ACCEPTED);

        return new ObjectDetailView(base, transitions, violations, checkedToday, trackCount);
    }

    /** 二次确认 + 必填理由后返回全名，并落审计留痕 */
    @Transactional
    public String revealFullName(Long id, String reason, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);
        nameAuditRepository.save(new NameViewAudit(id, user.userId(), user.realName(), reason));
        return o.getFullName();
    }

    @Transactional(readOnly = true)
    public List<NameAuditView> nameAudits(Long id, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);
        return nameAuditRepository.findByOffenderIdOrderByViewedAtDescIdDesc(id).stream()
                .map(a -> new NameAuditView(a.getViewerName(), a.getReason(),
                        a.getViewedAt().toString() + "Z",
                        cn.sfj.jiaowutong.common.time.TimeZones.fmtWall(a.getViewedAt(),
                                o.getOffice().getTimezone())))
                .toList();
    }

    /** 状态机流转；非法回退由状态机抛 INVALID_TRANSITION 说明原因 */
    @Transactional
    public TransitionView transition(Long id, CorrectionStatus target, String reason, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);
        CorrectionStatus from = o.getStatus();
        CorrectionStateMachine.assertTransition(from, target);

        o.setStatus(target);
        objectRepository.save(o);
        java.time.LocalDateTime nowUtc = cn.sfj.jiaowutong.common.time.TimeZones.utcNow();
        StatusTransition saved = new StatusTransition(
                id, from, target, user.userId(), user.realName(), reason);
        transitionRepository.save(saved);

        // 训诫本身是处置措施，同步生成一条违规处置红点
        if (target == CorrectionStatus.ADMONISHED) {
            violationRepository.save(new ViolationEvent(o, "ADMONISH",
                    "对象 " + o.getMaskedName() + " 因违规被训诫" + (reason == null || reason.isBlank() ? "" : "：" + reason),
                    nowUtc));
        }

        return new TransitionView(from.name(), from.getLabel(),
                target.name(), target.getLabel(), reason, user.realName(),
                nowUtc.toString() + "Z",
                cn.sfj.jiaowutong.common.time.TimeZones.fmtWall(nowUtc, o.getOffice().getTimezone()));
    }

    @Transactional(readOnly = true)
    public List<TrackView> tracks(Long id, LoginUser user) {
        CorrectionObject o = accessControl.loadVisible(id, user);
        String zone = o.getOffice().getTimezone();
        return trackPointRepository.findByOffender_IdOrderByPointTimeAscIdAsc(id).stream()
                .map(t -> new TrackView(
                        t.getClientPointId(),
                        t.getPointTime().toString() + "Z",
                        cn.sfj.jiaowutong.common.time.TimeZones.fmtWall(t.getPointTime(), zone),
                        t.getLat(), t.getLng(),
                        Boolean.TRUE.equals(t.getOfflineCaptured()),
                        t.getReceivedAt() == null ? null : t.getReceivedAt().toString() + "Z",
                        t.getOutsideFence(),
                        t.getDeviceStatus(), t.getResult().name(), t.getDriftReason()))
                .toList();
    }

    static String labelOf(String statusName) {
        try {
            return CorrectionStatus.valueOf(statusName).getLabel();
        } catch (Exception e) {
            return statusName;
        }
    }

    static DashboardView.RedDotItem toRedDot(ViolationEvent v, CorrectionObject o) {
        return new DashboardView.RedDotItem(
                v.getId(), o.getId(), o.getCorrectionNo(), o.getMaskedName(),
                o.getOffice().getName(), v.getType(), typeLabel(v.getType()),
                v.getDetail(),
                v.getEventTime().toString() + "Z",
                cn.sfj.jiaowutong.common.time.TimeZones.fmtWall(v.getEventTime(),
                        o.getOffice().getTimezone()),
                v.getReadFlag());
    }

    static String typeLabel(String type) {
        return switch (type) {
            case "GEOFENCE_BREACH" -> "越界";
            case "BREACH_FORBIDDEN_DAY" -> "禁行日越界";
            case "MARK_BREACH" -> "人工标记越界";
            case "DEVICE_ALERT" -> "设备异常";
            case "ABSENT" -> "未按日报到";
            case "ADMONISH" -> "训诫";
            default -> type;
        };
    }
}
