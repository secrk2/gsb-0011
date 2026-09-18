package cn.sfj.jiaowutong.service;

/**
 * 地理围栏：Haversine 距离判断。
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
}
