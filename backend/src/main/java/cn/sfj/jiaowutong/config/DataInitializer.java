package cn.sfj.jiaowutong.config;

import cn.sfj.jiaowutong.common.time.TimeZones;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.PasswordEncoder;
import cn.sfj.jiaowutong.service.FenceService;
import cn.sfj.jiaowutong.service.PinyinUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示种子数据：3 个司法所（各带多边形活动范围与禁区）、监管员/干警/对象三类账号，
 * 覆盖入矫登记、在矫、请假外出、训诫、收监、解除全状态，并预置：
 * 今日应报到（含未报到）、多边形越界（禁区）、禁行日越界、GPS 漂移丢弃点、设备关机告警、
 * 训诫红点，双口径完成度（仅非规定日零散报到对象）、无轨迹/轨迹已清除两种空态。
 * 时间一律按 UTC 播种。仅在空库时执行（H2 文件卷重启后不重复播种）。
 */
@Component
@Order(0)
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final JudicialOfficeRepository officeRepository;
    private final CorrectionObjectRepository objectRepository;
    private final UserAccountRepository userRepository;
    private final TrackPointRepository trackPointRepository;
    private final CheckInRepository checkInRepository;
    private final ViolationEventRepository violationRepository;
    private final StatusTransitionRepository transitionRepository;
    private final FenceZoneRepository fenceZoneRepository;
    private final TrackClearRecordRepository clearRecordRepository;
    private final FenceService fenceService;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(JudicialOfficeRepository officeRepository,
                           CorrectionObjectRepository objectRepository,
                           UserAccountRepository userRepository,
                           TrackPointRepository trackPointRepository,
                           CheckInRepository checkInRepository,
                           ViolationEventRepository violationRepository,
                           StatusTransitionRepository transitionRepository,
                           FenceZoneRepository fenceZoneRepository,
                           TrackClearRecordRepository clearRecordRepository,
                           FenceService fenceService,
                           PasswordEncoder passwordEncoder) {
        this.officeRepository = officeRepository;
        this.objectRepository = objectRepository;
        this.userRepository = userRepository;
        this.trackPointRepository = trackPointRepository;
        this.checkInRepository = checkInRepository;
        this.violationRepository = violationRepository;
        this.transitionRepository = transitionRepository;
        this.fenceZoneRepository = fenceZoneRepository;
        this.clearRecordRepository = clearRecordRepository;
        this.fenceService = fenceService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("检测到已有数据，跳过种子初始化");
            return;
        }

        LocalDateTime nowUtc = TimeZones.utcNow();
        String todayWeek = TimeZones.localDate(nowUtc, TimeZones.DEFAULT_ZONE).getDayOfWeek().toString();
        LocalDate today = TimeZones.localDate(nowUtc, TimeZones.DEFAULT_ZONE);

        // ---------- 3 个司法所（含乡村所），青山所禁行星期动态取“今天”，打开即有禁行日场景 ----------
        JudicialOffice chengguan = officeRepository.save(new JudicialOffice(
                "JGS-CG", "城关司法所", "城关街道", 30.21230, 114.32456, 1000,
                "Asia/Shanghai", null));
        JudicialOffice qingshan = officeRepository.save(new JudicialOffice(
                "JGS-QS", "青山司法所", "青山乡（丘陵山区）", 30.35810, 114.47290, 1000,
                "Asia/Shanghai", todayWeek));
        JudicialOffice longhu = officeRepository.save(new JudicialOffice(
                "JGS-LH", "龙湖司法所", "龙湖镇", 30.10540, 114.21870, 1000,
                "Asia/Shanghai", null));

        // 多边形围栏：规定活动范围（约 1.1km 见方）+ 青山乡矿区禁区
        fenceZoneRepository.save(new FenceZone(chengguan, "城关规定活动区", "ALLOW",
                boxRing(chengguan.getCenterLat(), chengguan.getCenterLng(), 0.010, 0.012)));
        fenceZoneRepository.save(new FenceZone(qingshan, "青山乡规定活动区", "ALLOW",
                boxRing(qingshan.getCenterLat(), qingshan.getCenterLng(), 0.010, 0.012)));
        fenceZoneRepository.save(new FenceZone(qingshan, "乡北矿区（禁区）", "FORBIDDEN",
                boxRing(qingshan.getCenterLat() + 0.022, qingshan.getCenterLng() + 0.0065, 0.004, 0.0045)));
        fenceZoneRepository.save(new FenceZone(longhu, "龙湖镇规定活动区", "ALLOW",
                boxRing(longhu.getCenterLat(), longhu.getCenterLng(), 0.010, 0.012)));

        List<SeedObj> seeds = new ArrayList<>();

        // 城关所：未报到(在矫) / 请假外出 / 训诫 / 已解除
        seeds.add(new SeedObj("JWT26001", "张伟国", chengguan, CorrectionStatus.SERVING,
                todayWeek, "危险驾驶罪", today.minusMonths(8), today.plusMonths(4)));
        seeds.add(new SeedObj("JWT26002", "王秀兰", chengguan, CorrectionStatus.LEAVE,
                "FRIDAY", "交通肇事罪", today.minusMonths(5), today.plusMonths(7)));
        seeds.add(new SeedObj("JWT26003", "李志强", chengguan, CorrectionStatus.ADMONISHED,
                "TUESDAY", "故意伤害罪", today.minusMonths(10), today.plusMonths(2)));
        seeds.add(new SeedObj("JWT26004", "赵敏", chengguan, CorrectionStatus.RELEASED,
                "MONDAY", "盗窃罪", today.minusYears(1), today.minusDays(20)));

        // 青山所（乡村）：越界+禁行日(在矫) / 今日已报到(在矫) / 入矫登记 / 已收监
        seeds.add(new SeedObj("JWT26005", "陈大山", qingshan, CorrectionStatus.SERVING,
                "WEDNESDAY", "滥伐林木罪", today.minusMonths(3), today.plusMonths(9)));
        seeds.add(new SeedObj("JWT26006", "杨春生", qingshan, CorrectionStatus.SERVING,
                todayWeek, "非法捕捞水产品罪", today.minusMonths(6), today.plusMonths(6)));
        seeds.add(new SeedObj("JWT26007", "刘德海", qingshan, CorrectionStatus.INTAKE,
                "THURSDAY", "过失致人重伤罪", today.minusDays(3), today.plusMonths(11)));
        seeds.add(new SeedObj("JWT26008", "黄国庆", qingshan, CorrectionStatus.REIMPRISONED,
                "MONDAY", "寻衅滋事罪", today.minusMonths(9), today.plusMonths(3)));

        // 龙湖所：双口径背离(在矫) / 请假且轨迹已清除 / 训诫+设备关机 / 正常在矫但从未上报
        seeds.add(new SeedObj("JWT26009", "周文斌", longhu, CorrectionStatus.SERVING,
                "MONDAY", "开设赌场罪", today.minusMonths(1), today.plusMonths(8)));
        seeds.add(new SeedObj("JWT26010", "吴桂芳", longhu, CorrectionStatus.LEAVE,
                todayWeek, "信用卡诈骗罪", today.minusMonths(7), today.plusMonths(5)));
        seeds.add(new SeedObj("JWT26011", "徐建华", longhu, CorrectionStatus.ADMONISHED,
                "SATURDAY", "妨害公务罪", today.minusMonths(2), today.plusMonths(10)));
        seeds.add(new SeedObj("JWT26012", "孙满堂", longhu, CorrectionStatus.SERVING,
                "SUNDAY", "污染环境罪", today.minusMonths(1), today.plusMonths(11)));

        List<CorrectionObject> objs = new ArrayList<>();
        for (SeedObj s : seeds) {
            CorrectionObject o = new CorrectionObject();
            o.setCorrectionNo(s.no());
            o.setFullName(s.fullName());
            o.setMaskedName(PinyinUtil.surnameInitial(s.fullName()) + "-" + s.no());
            o.setOffice(s.office());
            o.setStatus(s.status());
            o.setReportDay(s.reportDay());
            o.setCharge(s.charge());
            o.setStartDate(s.start());
            o.setEndDate(s.end());
            o.setPhone("138" + String.format("%08d", Integer.parseInt(s.no().substring(6)) * 137 % 100000000));
            o.setIdCardTail("****" + String.format("%04X", Math.floorMod(s.no().hashCode(), 0x10000)));
            CorrectionObject saved = objectRepository.save(o);
            objs.add(saved);
            emitPath(saved, s.status());
        }

        CorrectionObject zhang = objs.get(0);
        CorrectionObject chen = objs.get(4);
        CorrectionObject yang = objs.get(5);
        CorrectionObject zhou = objs.get(8);
        CorrectionObject wu = objs.get(9);
        CorrectionObject xu = objs.get(10);
        // 孙满堂（objs.get(11)）刻意无任何轨迹 → 监控页“该对象无轨迹”空态

        // ---------- 陈大山：近 3 小时围栏内轨迹（5 分钟一点）→ 1 个 GPS 漂移跳点（丢弃）→ 离线补传进入禁区 ----------
        double cLat = qingshan.getCenterLat();
        double cLng = qingshan.getCenterLng();
        List<FenceZone> qsZones = fenceZoneRepository.findByOffice_IdAndEnabledTrueOrderByIdAsc(qingshan.getId());
        List<FenceZone> cgZones = fenceZoneRepository.findByOffice_IdAndEnabledTrueOrderByIdAsc(chengguan.getId());
        List<FenceZone> lhZones = fenceZoneRepository.findByOffice_IdAndEnabledTrueOrderByIdAsc(longhu.getId());
        int seq = 0;
        LocalDateTime anchorUtc = null;
        for (int m = 180; m >= 50; m -= 5) {
            double jitterLat = (Math.sin(seq * 1.7) * 0.0011);
            double jitterLng = (Math.cos(seq * 1.3) * 0.0013);
            LocalDateTime pt = nowUtc.minusMinutes(m);
            double lat = cLat + jitterLat;
            double lng = cLng + jitterLng;
            boolean outside = fenceService.evaluate(qingshan, lat, lng, qsZones).outside();
            trackPointRepository.save(new TrackPoint(chen, "seed-chen-in-" + seq,
                    pt, lat, lng, false, nowUtc.minusMinutes(m - 1), outside,
                    TrackPoint.IngestResult.ACCEPTED, "NORMAL", 72 - seq % 30, null));
            anchorUtc = pt;
            seq++;
        }
        // GPS 漂移：相对最后采信点仅 +5 秒跳到约 50 公里外 → 等效约 3.7 万 km/h，服务端规则判丢弃
        LocalDateTime driftUtc = anchorUtc.plusSeconds(5);
        trackPointRepository.save(new TrackPoint(chen, "seed-chen-drift",
                driftUtc, cLat + 0.45, cLng + 0.12, false, driftUtc.plusSeconds(1),
                false, TrackPoint.IngestResult.DRIFT_DROPPED, "NORMAL", 55,
                "距上一采信点约 51352 米、仅 5 秒，等效 36974 km/h 超过合理上限 200 km/h，判为 GPS 漂移丢弃"));
        // 离线补传：两个禁区内落点（最新点 30 分钟前）
        LocalDateTime out1 = nowUtc.minusMinutes(40);
        LocalDateTime out2 = nowUtc.minusMinutes(30);
        trackPointRepository.save(new TrackPoint(chen, "seed-chen-out-1",
                out1, cLat + 0.0205, cLng + 0.0050, true, nowUtc.minusMinutes(15),
                true, TrackPoint.IngestResult.ACCEPTED, "LOW_BATTERY", 18, null));
        trackPointRepository.save(new TrackPoint(chen, "seed-chen-out-2",
                out2, cLat + 0.022, cLng + 0.006, true, nowUtc.minusMinutes(14),
                true, TrackPoint.IngestResult.ACCEPTED, "LOW_BATTERY", 16, null));
        chen.setLastLocationAt(out2);
        chen.setLastLat(cLat + 0.022);
        chen.setLastLng(cLng + 0.006);
        chen.setLastInsideFence(false);
        chen.setLastDeviceStatus("LOW_BATTERY");
        chen.setLastBatteryPercent(16);
        objectRepository.save(chen);

        // 张伟国：最近定位正常但略旧（2.5 小时前）
        LocalDateTime zPt = nowUtc.minusMinutes(150);
        boolean zInside = !fenceService.evaluate(chengguan,
                chengguan.getCenterLat() + 0.002, chengguan.getCenterLng() - 0.001, cgZones).outside();
        trackPointRepository.save(new TrackPoint(zhang, "seed-zhang-01",
                zPt, chengguan.getCenterLat() + 0.002, chengguan.getCenterLng() - 0.001,
                false, nowUtc.minusMinutes(149), zInside, TrackPoint.IngestResult.ACCEPTED,
                "NORMAL", 64, null));
        zhang.setLastLocationAt(zPt);
        zhang.setLastLat(chengguan.getCenterLat() + 0.002);
        zhang.setLastLng(chengguan.getCenterLng() - 0.001);
        zhang.setLastInsideFence(zInside);
        zhang.setLastDeviceStatus("NORMAL");
        zhang.setLastBatteryPercent(64);
        objectRepository.save(zhang);

        // 徐建华：腕表关机前最后回传（6 小时前，关机状态）→ 设备异常红点
        LocalDateTime xPt = nowUtc.minusHours(6);
        boolean xInside = !fenceService.evaluate(longhu,
                longhu.getCenterLat() + 0.001, longhu.getCenterLng() + 0.001, lhZones).outside();
        trackPointRepository.save(new TrackPoint(xu, "seed-xu-off",
                xPt, longhu.getCenterLat() + 0.001, longhu.getCenterLng() + 0.001,
                false, nowUtc.minusHours(6), !xInside, TrackPoint.IngestResult.ACCEPTED,
                "POWER_OFF", 2, null));
        xu.setLastLocationAt(xPt);
        xu.setLastLat(longhu.getCenterLat() + 0.001);
        xu.setLastLng(longhu.getCenterLng() + 0.001);
        xu.setLastInsideFence(xInside);
        xu.setLastDeviceStatus("POWER_OFF");
        xu.setLastBatteryPercent(2);
        objectRepository.save(xu);

        // ---------- 报到：双口径完成度 ----------
        // 杨春生：矫正期内每个规定日报到（关键报到近满），今天也已报到
        for (LocalDate d = today.minusMonths(6).with(java.time.DayOfWeek.MONDAY);
             !d.isAfter(today); d = d.plusWeeks(1)) {
            LocalDate report = d.with(java.time.DayOfWeek.valueOf(yang.getReportDay()));
            if (report.isAfter(today)) {
                break;
            }
            seedCheckIn(yang, report, nowUtc, qingshan, cLat, cLng);
        }
        // 周文斌：近 30 天在 10 个“非周一”零散报到 → 打卡天数口径不低、关键报到口径为 0（两口径相反）
        int made = 0;
        for (int back = 29; back >= 1 && made < 10; back--) {
            LocalDate d = today.minusDays(back);
            if (d.getDayOfWeek() == java.time.DayOfWeek.MONDAY) {
                continue;
            }
            seedCheckIn(zhou, d, nowUtc, longhu,
                    longhu.getCenterLat() + 0.001, longhu.getCenterLng() - 0.001);
            made++;
        }

        // ---------- 吴桂芳：轨迹已被清除（有清除留痕、无有效点）→ “轨迹全清除”空态 ----------
        clearRecordRepository.save(new TrackClearRecord(wu.getId(), 126,
                "对象请假就医期间历史轨迹阶段性归档清除，纸质审批单存所内备查",
                0L, "韩雪梅", nowUtc.minusDays(2)));

        // ---------- 红点事件（未处置） ----------
        violationRepository.save(new ViolationEvent(zhang, "ABSENT",
                "对象 Z-JWT26001 今日应到司法所/APP 报到，截至目前未报到", nowUtc.minusMinutes(20)));
        violationRepository.save(new ViolationEvent(chen, "BREACH_FORBIDDEN_DAY",
                "对象 C-JWT26005 在禁行日（每" + cn.sfj.jiaowutong.service.TrackService
                        .weekCn(java.time.DayOfWeek.valueOf(todayWeek))
                        + "）进入青山乡「乡北矿区（禁区）」，最近定位 "
                        + TimeZones.fmtWall(out2, "Asia/Shanghai") + "（司法所当地时间），离线补传数据",
                nowUtc.minusMinutes(14)));
        violationRepository.save(new ViolationEvent(objs.get(2), "ADMONISH",
                "对象 L-JWT26003 因本周两次未按规定时间报到，被予以训诫",
                today.minusDays(1).atTime(15, 30)
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime()));
        violationRepository.save(new ViolationEvent(xu, "DEVICE_ALERT",
                "对象 X-JWT26011 的定位腕表关机，最后回传 "
                        + TimeZones.fmtWall(xPt, "Asia/Shanghai") + "（司法所当地时间）", nowUtc.minusHours(6)));

        // ---------- 账号：三类角色 ----------
        createAccount("jiandu", "陈督导", Role.SUPERVISOR, null, null);
        createAccount("gancheng", "李建国", Role.STAFF, chengguan, null);
        createAccount("ganqingshan", "罗建军", Role.STAFF, qingshan, null);
        createAccount("ganlonghu", "韩雪梅", Role.STAFF, longhu, null);

        // 矫正对象账号（8 个，覆盖在矫/请假/训诫/越界场景）
        String[] objUsers = {"obj1", "obj2", "obj3", null, "obj4", "obj5", null, null,
                "obj6", "obj7", "obj8", null};
        for (int i = 0; i < objs.size(); i++) {
            if (objUsers[i] != null) {
                createAccount(objUsers[i], objs.get(i).getFullName(), Role.OFFENDER,
                        objs.get(i).getOffice(), objs.get(i));
            }
        }

        log.info("种子数据完成：3 个司法所（含多边形围栏/禁区/禁行日）、12 名对象、12 个账号、4 条未处置红点");
    }

    /** 在规定活动范围内生成某天报到（checkDate 为司法所本地日，checkedAt 用近似 UTC） */
    private void seedCheckIn(CorrectionObject o, LocalDate localDate, LocalDateTime nowUtc,
                             JudicialOffice office, double lat, double lng) {
        LocalDateTime checkedAt = localDate.atTime(9, 0)
                .atZone(java.time.ZoneId.of(office.getTimezone()))
                .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        // 未来时间的“今天”点不补；仅补历史与今天
        if (checkedAt.isAfter(nowUtc)) {
            return;
        }
        checkInRepository.save(new CheckIn(o, localDate, checkedAt, "APP", lat, lng, true));
    }

    /** 生成以 (lat0,lng0) 为中心、dlat/dlng 半边长的矩形多边形顶点环 */
    private static String boxRing(double lat0, double lng0, double dlat, double dlng) {
        return String.format(java.util.Locale.ROOT,
                "%.6f,%.6f;%.6f,%.6f;%.6f,%.6f;%.6f,%.6f",
                lat0 + dlat, lng0 - dlng,
                lat0 + dlat, lng0 + dlng,
                lat0 - dlat, lng0 + dlng,
                lat0 - dlat, lng0 - dlng);
    }

    /** 按状态机合法路径补建流转记录 */
    private void emitPath(CorrectionObject o, CorrectionStatus target) {
        Long op = 0L;
        transitionRepository.save(new StatusTransition(
                o.getId(), null, CorrectionStatus.INTAKE, op, "系统（入矫建档）", "入矫登记建档"));
        if (target == CorrectionStatus.INTAKE) {
            return;
        }
        transitionRepository.save(new StatusTransition(
                o.getId(), CorrectionStatus.INTAKE, CorrectionStatus.SERVING, op, "系统（入矫宣告）", "入矫宣告，纳入在矫管理"));
        switch (target) {
            case LEAVE -> transitionRepository.save(new StatusTransition(
                    o.getId(), CorrectionStatus.SERVING, CorrectionStatus.LEAVE, op, "系统（种子数据）", "请假外出审批通过"));
            case ADMONISHED -> transitionRepository.save(new StatusTransition(
                    o.getId(), CorrectionStatus.SERVING, CorrectionStatus.ADMONISHED, op, "系统（种子数据）", "违反监管规定，予以训诫"));
            case REIMPRISONED -> transitionRepository.save(new StatusTransition(
                    o.getId(), CorrectionStatus.SERVING, CorrectionStatus.REIMPRISONED, op, "系统（种子数据）", "违反监管规定情节严重，撤销缓刑收监执行"));
            case RELEASED -> transitionRepository.save(new StatusTransition(
                    o.getId(), CorrectionStatus.SERVING, CorrectionStatus.RELEASED, op, "系统（种子数据）", "矫正期满，依法解除社区矫正"));
            default -> { /* SERVING */ }
        }
    }

    private void createAccount(String username, String realName, Role role,
                               JudicialOffice office, CorrectionObject linked) {
        String[] saltHash = passwordEncoder.newSaltAndHash("123456");
        UserAccount u = new UserAccount();
        u.setUsername(username);
        u.setRealName(realName);
        u.setRole(role);
        u.setOffice(office);
        u.setLinkedOffender(linked);
        u.setPasswordSalt(saltHash[0]);
        u.setPasswordHash(saltHash[1]);
        u.setEnabled(true);
        userRepository.save(u);
    }

    private record SeedObj(String no, String fullName, JudicialOffice office,
                           CorrectionStatus status, String reportDay, String charge,
                           LocalDate start, LocalDate end) {
    }
}
