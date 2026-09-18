package cn.sfj.jiaowutong.web.vo;

import java.time.LocalDateTime;

public record TransitionView(String fromStatus, String fromStatusLabel,
                             String toStatus, String toStatusLabel,
                             String reason, String operatorName, LocalDateTime operatedAt) {
}
