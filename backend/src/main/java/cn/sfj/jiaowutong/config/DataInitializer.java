package cn.sfj.jiaowutong.config;

import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.PasswordEncoder;
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
 * 演示种子数据：3 个司法所、监管员/干警/对象三类账号，
 * 覆盖入矫登记、在矫、请假外出、训诫、收监、解除全状态，
 * 并预置今日应报到（含未报到）、越界、训诫红点与轨迹/报到记录。
 * 仅在空库时执行（H2 文件卷重启后不重复播种）。
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
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(JudicialOfficeRepository officeRepository,
                           CorrectionObjectRepository objectRepository,
                           UserAccountRepository userRepository,
                           TrackPointRepository trackPointRepository,
                           CheckInRepository checkInRepository,
                           ViolationEventRepository violationRepository,
                           StatusTransitionRepository transitionRepository,
                           PasswordEncoder passwordEncoder) {
        this.officeRepository = officeRepository;
        this.objectRepository = objectRepository;
        this.userRepository = userRepository;
        this.trackPointRepository = trackPointRepository;
        this.checkInRepository = checkInRepository;
        this.violationRepository = violationRepository;
        this.transitionRepository = transitionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("检测到已有数据，跳过种子初始化");
            return;
        }

        String todayWeek = LocalDate.now().getDayOfWeek().toString();
        LocalDate today = LocalDate.now();

        // ---------- 3 个司法所（含乡村所） ----------
        JudicialOffice chengguan = officeRepository.save(new JudicialOffice(
                "JGS-CG", "城关司法所", "城关街道", 30.21230, 114.32456, 1000));
        JudicialOffice qingshan = officeRepository.save(new JudicialOffice(
                "JGS-QS", "青山司法所", "青山乡（丘陵山区）", 30.35810, 114.47290, 1000));
        JudicialOffice longhu = officeRepository.save(new JudicialOffice(
                "JGS-LH", "龙湖司法所", "龙湖镇", 30.10540, 114.21870, 1000));

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

        // 青山所（乡村）：越界中(在矫) / 今日已报到(在矫) / 入矫登记 / 已收监
        seeds.add(new SeedObj("JWT26005", "陈大山", qingshan, CorrectionStatus.SERVING,
                "WEDNESDAY", "滥伐林木罪", today.minusMonths(3), today.plusMonths(9)));
        seeds.add(new SeedObj("JWT26006", "杨春生", qingshan, CorrectionStatus.SERVING,
                todayWeek, "非法捕捞水产品罪", today.minusMonths(6), today.plusMonths(6)));
        seeds.add(new SeedObj("JWT26007", "刘德海", qingshan, CorrectionStatus.INTAKE,
                "THURSDAY", "过失致人重伤罪", today.minusDays(3), today.plusMonths(11)));
        seeds.add(new SeedObj("JWT26008", "黄国庆", qingshan, CorrectionStatus.REIMPRISONED,
                "MONDAY", "寻衅滋事罪", today.minusMonths(9), today.plusMonths(3)));

        // 龙湖所：正常在矫 / 请假且今日应报到 / 训诫 / 正常在矫
        seeds.add(new SeedObj("JWT26009", "周文斌", longhu, CorrectionStatus.SERVING,
                "MONDAY", "开设赌场罪", today.minusMonths(4), today.plusMonths(8)));
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

        // ---------- 定位与轨迹（时间相对当前时刻，避免时区/晨跑造成未来点） ----------
        LocalDateTime tNow = LocalDateTime.now();
        // 陈大山：约 1.5 小时前尚在围栏内，40 分钟前越界（山区信号差，离线缓存补传）
        trackPointRepository.save(new TrackPoint(chen, "seed-chen-01",
                tNow.minusMinutes(90), qingshan.getCenterLat(), qingshan.getCenterLng(),
                false, tNow.minusMinutes(89), false, TrackPoint.IngestResult.ACCEPTED));
        trackPointRepository.save(new TrackPoint(chen, "seed-chen-02",
                tNow.minusMinutes(40), qingshan.getCenterLat() + 0.022, qingshan.getCenterLng() + 0.006,
                true, tNow.minusMinutes(15), true, TrackPoint.IngestResult.ACCEPTED));
        chen.setLastLocationAt(tNow.minusMinutes(40));
        chen.setLastLat(qingshan.getCenterLat() + 0.022);
        chen.setLastLng(qingshan.getCenterLng() + 0.006);
        chen.setLastInsideFence(false);
        objectRepository.save(chen);

        // 张伟国：最近定位正常
        zhang.setLastLocationAt(tNow.minusMinutes(150));
        zhang.setLastLat(chengguan.getCenterLat() + 0.002);
        zhang.setLastLng(chengguan.getCenterLng() - 0.001);
        zhang.setLastInsideFence(true);
        objectRepository.save(zhang);

        // ---------- 今日报到：杨春生已报到，张伟国未报到 ----------
        checkInRepository.save(new CheckIn(yang, today, tNow.minusMinutes(70), "APP",
                qingshan.getCenterLat() + 0.001, qingshan.getCenterLng(), true));

        // ---------- 红点事件 ----------
        violationRepository.save(new ViolationEvent(zhang, "ABSENT",
                "对象 Z-JWT26001 今日应到司法所/APP 报到，截至目前未报到", tNow.minusMinutes(20)));
        violationRepository.save(new ViolationEvent(chen, "GEOFENCE_BREACH",
                "对象 C-JWT26005 定位越出「青山司法所」电子围栏（半径 1000 米），最近定位时间 "
                        + tNow.minusMinutes(40) + "，离线补传数据", tNow.minusMinutes(15)));
        violationRepository.save(new ViolationEvent(objs.get(2), "ADMONISH",
                "对象 L-JWT26003 因本周两次未按规定时间报到，被予以训诫", today.minusDays(1).atTime(15, 30)));

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

        log.info("种子数据完成：3 个司法所、12 名对象（6 种状态）、12 个账号、3 条未处置红点");
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
