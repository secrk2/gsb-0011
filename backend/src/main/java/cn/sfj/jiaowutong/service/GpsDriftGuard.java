package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.domain.TrackPoint;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * GPS 漂移守门器：<b>不能只比距离</b>。
 *
 * <p>规则：新点与“最近一个被采信的点”（锚点；漂移丢弃点不充当锚点，避免锚点被带飞）之间，
 * 当位移超过 {@link #NOISE_FLOOR_METERS} 噪声地板、且等效速度超过
 * {@link #MAX_SPEED_KMH} 合理上限时，判为漂移丢弃。
 *
 * <p>用速度而不是固定距离阈值的原因：腕表 5 秒一点，人步行每秒 1~2 米属正常；
 * 同样的 500 米跳变，隔 5 秒是 360 km/h（不可能，漂移），隔 2 小时离线补传则只有 0.25 km/h（正常）。
 */
public final class GpsDriftGuard {

    /** 合理位移速度上限（km/h）：高速行车级别，超出视为 GPS 跳点 */
    public static final double MAX_SPEED_KMH = 200d;
    /** 噪声地板（米）：小于该位移不做速度判罚，避免静止时普通卫星抖动被误杀 */
    public static final double NOISE_FLOOR_METERS = 100d;

    private GpsDriftGuard() {
    }

    /**
     * @param anchor 最近一个 ACCEPTED 点（按采集时间），首点无锚点时传 null
     * @return 校验结果；drift=true 表示该点应丢弃
     */
    public static Check check(TrackPoint anchor, double lat, double lng, LocalDateTime pointTimeUtc) {
        if (anchor == null) {
            return Check.ok(0d, 0d);
        }
        double meters = GeoUtil.distanceMeters(
                anchor.getLat(), anchor.getLng(), lat, lng);
        long seconds = Duration.between(anchor.getPointTime(), pointTimeUtc).getSeconds();
        if (seconds <= 0) {
            // 同刻/乱序到达：不判漂移（合并由采集时间顺序处理），交给上层
            return Check.ok(meters, 0d);
        }
        double kmh = GeoUtil.toKmh(GeoUtil.speedMps(
                anchor.getLat(), anchor.getLng(), lat, lng, seconds));
        if (meters > NOISE_FLOOR_METERS && kmh > MAX_SPEED_KMH) {
            String reason = String.format(
                    "距上一采信点 %.0f 米、仅 %d 秒，等效 %.0f km/h 超过合理上限 %.0f km/h，判为 GPS 漂移丢弃",
                    meters, seconds, kmh, MAX_SPEED_KMH);
            return Check.drift(meters, kmh, reason);
        }
        return Check.ok(meters, kmh);
    }

    public record Check(boolean drift, double meters, double kmh, String reason) {
        static Check ok(double meters, double kmh) {
            return new Check(false, meters, kmh, null);
        }

        static Check drift(double meters, double kmh, String reason) {
            return new Check(true, meters, kmh, reason);
        }
    }
}
