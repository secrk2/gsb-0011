package cn.sfj.jiaowutong.web.vo;

import java.time.LocalDateTime;

public record TrackView(String clientPointId, LocalDateTime pointTime, Double lat, Double lng,
                        boolean offlineCaptured, LocalDateTime receivedAt, boolean outsideFence) {
}
