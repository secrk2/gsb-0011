package cn.sfj.jiaowutong.common.time;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 时间口径（定位模块的时间铁律）：
 * 1. 库内所有时间（轨迹点、报到、标记、清除留痕）一律以 {@link LocalDateTime} 存 <b>UTC 墙钟分量</b>，
 *    不依赖服务器/容器的 TZ 设置；
 * 2. 展示与“今天/星期几/是否同一天”的业务判定，一律按司法所配置的 IANA 时区（zoneId）换算，
 *    夏令时切换由 JVM 自带 tzdata 通过 {@link ZoneId} 规则自动处理，业务代码不做任何 +8/-8 手算；
 * 3. 跨司法所比较“是否同一天/是否越界当日”时，各对象按各所时区独立判定，
 *    不能统一拿服务器本地日相减（干警不会因日界算错白跑）。
 */
public final class TimeZones {

    /** 未配置时区时的兜底（存量数据 / 国内司法所） */
    public static final String DEFAULT_ZONE = "Asia/Shanghai";

    private TimeZones() {
    }

    /** 统一时钟入口；生产固定 UTC 系统时钟 */
    private static volatile Clock clock = Clock.systemUTC();

    public static ZoneId zone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneId.of(DEFAULT_ZONE);
        }
        return ZoneId.of(zoneId); // 非法时区字符串抛 DateTimeException
    }

    /** 当前 UTC 时间（入库口径；clock 固定为 UTC 系统时钟） */
    public static LocalDateTime utcNow() {
        return LocalDateTime.now(clock);
    }

    /** 指定司法所时区的当前时刻 */
    public static ZonedDateTime nowAt(String zoneId) {
        return ZonedDateTime.now(clock).withZoneSameInstant(zone(zoneId));
    }

    /** UTC 存储值 → 指定司法所时区的带时刻对象 */
    public static ZonedDateTime atZone(LocalDateTime utc, String zoneId) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone(zoneId));
    }

    /** 把某时区的墙钟时间换算为 UTC 存储值（兼容按本地时区墙钟上报的客户端） */
    public static LocalDateTime toUtc(LocalDateTime wallTime, String zoneId) {
        return LocalDateTime.ofInstant(wallTime.atZone(zone(zoneId)).toInstant(), ZoneOffset.UTC);
    }

    /** 该 UTC 时刻在司法所时区的本地日期 */
    public static LocalDate localDate(LocalDateTime utc, String zoneId) {
        return atZone(utc, zoneId).toLocalDate();
    }

    /**
     * 判断某 UTC 时刻在司法所时区是否落在“禁行星期”。
     * 用 {@link ZonedDateTime#getDayOfWeek()}：DST 切换日（当地 23h/25h）也能得到正确的当地星期，
     * 不会出现“夏令时一换、星期错位一天导致误报”。
     */
    public static boolean isForbiddenWeekday(LocalDateTime utc, DayOfWeek forbidden, String zoneId) {
        return forbidden != null && atZone(utc, zoneId).getDayOfWeek() == forbidden;
    }

    /** 该司法所时区今天是否为禁行星期 */
    public static boolean todayIsForbidden(String zoneId, DayOfWeek forbidden) {
        return forbidden != null && nowAt(zoneId).getDayOfWeek() == forbidden;
    }

    public static DayOfWeek parseWeekday(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return DayOfWeek.valueOf(name.trim().toUpperCase());
    }

    /** 展示用：UTC 值 → “yyyy-MM-dd HH:mm” 司法所本地墙钟 */
    public static String fmtWall(LocalDateTime utc, String zoneId) {
        if (utc == null) {
            return "—";
        }
        ZonedDateTime z = atZone(utc, zoneId);
        return String.format("%04d-%02d-%02d %02d:%02d",
                z.getYear(), z.getMonthValue(), z.getDayOfMonth(), z.getHour(), z.getMinute());
    }

    /** 展示用时区偏移标签，如 +08:00（DST 生效期自动给出对应偏移） */
    public static String offsetLabel(String zoneId) {
        String id = nowAt(zoneId).getOffset().getId();
        return "Z".equals(id) ? "+00:00" : id;
    }
}
