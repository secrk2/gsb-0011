package cn.sfj.jiaowutong.service;

import java.util.List;

/**
 * 地理围栏几何工具：
 * - 圆形围栏：Haversine 距离；
 * - 多边形围栏（活动范围 / 禁区）：射线法 point-in-polygon，边界点算在内。
 * 坐标一律 WGS-84（lat, lng），与腕表 GPS 上报口径一致。
 */
public final class GeoUtil {

    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private GeoUtil() {
    }

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static boolean isInside(double lat, double lng,
                                   double centerLat, double centerLng, int radiusMeters) {
        return distanceMeters(lat, lng, centerLat, centerLng) <= radiusMeters;
    }

    /**
     * 点是否在多边形内部（射线法，边界点视为内部）。
     * 顶点顺序（顺/逆时针）不影响结果；支持凹多边形。
     *
     * @param ring 多边形顶点，按顺序首尾相接（无需重复闭合点），至少 3 个点
     */
    public static boolean pointInPolygon(double lat, double lng, List<double[]> ring) {
        if (ring == null || ring.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i)[0], yi = ring.get(i)[1];
            double xj = ring.get(j)[0], yj = ring.get(j)[1];

            // 点落在边线上（含顶点）：直接算内部，避免边界点反复横跳产生误报
            if (onSegment(lat, lng, xi, yi, xj, yj)) {
                return true;
            }
            boolean intersect = ((yi > lng) != (yj > lng))
                    && (lat < (xj - xi) * (lng - yi) / ((yj - yi) == 0 ? 1e-18 : (yj - yi)) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 点 (px,py) 是否在线段 (x1,y1)-(x2,y2) 上（经纬度平面近似，仅用于边界判定） */
    private static boolean onSegment(double px, double py,
                                     double x1, double y1, double x2, double y2) {
        double cross = (px - x1) * (y2 - y1) - (py - y1) * (x2 - x1);
        if (Math.abs(cross) > 1e-9) {
            return false;
        }
        return px >= Math.min(x1, x2) - 1e-9 && px <= Math.max(x1, x2) + 1e-9
                && py >= Math.min(y1, y2) - 1e-9 && py <= Math.max(y1, y2) + 1e-9;
    }

    /** 两点间等效速度（米/秒）；耗时非正时返回 +∞，调用方按“无法核验、保守采信”处理 */
    public static double speedMps(double lat1, double lng1, double lat2, double lng2,
                                  double elapsedSeconds) {
        if (elapsedSeconds <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return distanceMeters(lat1, lng1, lat2, lng2) / elapsedSeconds;
    }

    /** 米/秒 → 千米/小时 */
    public static double toKmh(double mps) {
        return mps * 3.6d;
    }
}
