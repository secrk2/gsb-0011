package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.domain.FenceZone;
import cn.sfj.jiaowutong.domain.JudicialOffice;
import cn.sfj.jiaowutong.repo.FenceZoneRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 电子围栏判定（多边形优先）：
 * <ol>
 *   <li><b>禁区（FORBIDDEN）优先级最高</b>：任何时间进入禁区多边形即越界；</li>
 *   <li>配置了活动范围（ALLOW）多边形：点必须落在至少一个活动范围内，否则视为超出活动范围；</li>
 *   <li>未配置任何活动范围多边形：回退司法所圆形围栏（Haversine）。</li>
 * </ol>
 * 注意：判定只回答“空间上在哪里”，“今天是不是禁行日/是否升级违规”由 {@code MonitorService}
 * 按司法所时区另行裁决，空间与时间两件事不混算。
 */
@Service
public class FenceService {

    private final FenceZoneRepository fenceZoneRepository;

    public FenceService(FenceZoneRepository fenceZoneRepository) {
        this.fenceZoneRepository = fenceZoneRepository;
    }

    public List<FenceZone> zones(Long officeId) {
        return fenceZoneRepository.findByOffice_IdAndEnabledTrueOrderByIdAsc(officeId);
    }

    public FenceVerdict evaluate(JudicialOffice office, double lat, double lng) {
        return evaluate(office, lat, lng, zones(office.getId()));
    }

    public FenceVerdict evaluate(JudicialOffice office, double lat, double lng, List<FenceZone> zones) {
        List<FenceZone> forbidden = new ArrayList<>();
        List<FenceZone> allows = new ArrayList<>();
        for (FenceZone z : zones) {
            if ("FORBIDDEN".equals(z.getKind())) {
                forbidden.add(z);
            } else if ("ALLOW".equals(z.getKind())) {
                allows.add(z);
            }
        }

        // 1. 禁区永远不可进
        for (FenceZone z : forbidden) {
            List<double[]> ring = parseRing(z.getRingText());
            if (GeoUtil.pointInPolygon(lat, lng, ring)) {
                return FenceVerdict.outside("FORBIDDEN_ZONE", "进入禁区「" + z.getName() + "」", z.getName());
            }
        }

        // 2. 有活动范围多边形：必须在其中之一
        if (!allows.isEmpty()) {
            for (FenceZone z : allows) {
                List<double[]> ring = parseRing(z.getRingText());
                if (GeoUtil.pointInPolygon(lat, lng, ring)) {
                    return FenceVerdict.inside("在活动范围「" + z.getName() + "」内", z.getName());
                }
            }
            return FenceVerdict.outside("OUT_OF_ALLOW",
                    "超出规定活动范围（" + allows.size() + " 个多边形区域均未命中）", null);
        }

        // 3. 回退圆形围栏
        boolean inside = GeoUtil.isInside(lat, lng,
                office.getCenterLat(), office.getCenterLng(), office.getFenceRadiusMeters());
        return inside
                ? FenceVerdict.inside("在「" + office.getName() + "」圆形围栏内", null)
                : FenceVerdict.outside("OUT_OF_CIRCLE",
                        "超出「" + office.getName() + "」电子围栏（半径 " + office.getFenceRadiusMeters() + " 米）", null);
    }

    /** "lat,lng;lat,lng;..." → [[lat,lng],...]，跳过坏行 */
    public static List<double[]> parseRing(String ringText) {
        List<double[]> ring = new ArrayList<>();
        if (ringText == null || ringText.isBlank()) {
            return ring;
        }
        for (String part : ringText.split(";")) {
            String[] xy = part.trim().split(",");
            if (xy.length != 2) {
                continue;
            }
            try {
                ring.add(new double[]{Double.parseDouble(xy[0].trim()), Double.parseDouble(xy[1].trim())});
            } catch (NumberFormatException ignore) {
                // 坏坐标行忽略，不影响其余顶点
            }
        }
        return ring;
    }

    /** 空间判定结论。outside=true 时 reason 说明越界类型；inside 时 zoneName 为命中的活动范围名 */
    public record FenceVerdict(boolean outside, String code, String reason, String zoneName) {
        static FenceVerdict outside(String code, String reason, String zoneName) {
            return new FenceVerdict(true, code, reason, zoneName);
        }

        static FenceVerdict inside(String reason, String zoneName) {
            return new FenceVerdict(false, "INSIDE", reason, zoneName);
        }
    }
}
