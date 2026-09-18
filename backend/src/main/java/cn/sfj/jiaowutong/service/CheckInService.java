package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.CheckIn;
import cn.sfj.jiaowutong.domain.CorrectionObject;
import cn.sfj.jiaowutong.domain.CorrectionStatus;
import cn.sfj.jiaowutong.domain.Role;
import cn.sfj.jiaowutong.repo.CheckInRepository;
import cn.sfj.jiaowutong.repo.CorrectionObjectRepository;
import cn.sfj.jiaowutong.security.LoginUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 矫正对象日常报到。报到定位与轨迹上报同样禁止旧位置。
 */
@Service
public class CheckInService {

    private static final long REALTIME_SKEW_MIN = 5;

    private final CheckInRepository checkInRepository;
    private final CorrectionObjectRepository objectRepository;

    public CheckInService(CheckInRepository checkInRepository,
                          CorrectionObjectRepository objectRepository) {
        this.checkInRepository = checkInRepository;
        this.objectRepository = objectRepository;
    }

    @Transactional
    public Map<String, Object> checkIn(LocalDateTime fixTime, Double lat, Double lng, LoginUser user) {
        if (user.role() != Role.OFFENDER || user.offenderId() == null) {
            throw ApiException.forbidden("仅矫正对象本人账号可报到");
        }
        CorrectionObject obj = objectRepository.findById(user.offenderId())
                .orElseThrow(() -> ApiException.notFound("本人档案不存在"));
        if (obj.getStatus() == CorrectionStatus.RELEASED
                || obj.getStatus() == CorrectionStatus.REIMPRISONED) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "当前状态为「" + obj.getStatus().getLabel() + "」，无需日常报到");
        }

        LocalDateTime now = LocalDateTime.now();
        if (fixTime.isAfter(now.plusMinutes(2))) {
            throw ApiException.badRequest("STALE_LOCATION",
                    "报到定位时间晚于当前时间，疑似伪造定位");
        }
        if (fixTime.isBefore(now.minusMinutes(REALTIME_SKEW_MIN))) {
            throw new ApiException("STALE_LOCATION",
                    "报到定位采集于 " + fixTime + "，已超过 " + REALTIME_SKEW_MIN
                            + " 分钟时效。乡村断网时请在信号恢复后重新获取当前定位，不能用缓存旧位置报到");
        }

        boolean inside = GeoUtil.isInside(lat, lng,
                obj.getOffice().getCenterLat(), obj.getOffice().getCenterLng(),
                obj.getOffice().getFenceRadiusMeters());

        LocalDate today = LocalDate.now();
        boolean firstToday = !checkInRepository.existsByOffender_IdAndCheckDate(obj.getId(), today);
        checkInRepository.save(new CheckIn(obj, today, now, "APP", lat, lng, inside));

        // 同步更新最新位置（报到点视为一个有效实时定位）
        obj.setLastLocationAt(now);
        obj.setLastLat(lat);
        obj.setLastLng(lng);
        obj.setLastInsideFence(inside);
        objectRepository.save(obj);

        return Map.of(
                "checkedAt", now,
                "insideFence", inside,
                "firstToday", firstToday,
                "message", inside ? "报到成功，定位在规定活动范围内" : "报到成功，但当前定位在电子围栏外，已提示司法所关注"
        );
    }
}
