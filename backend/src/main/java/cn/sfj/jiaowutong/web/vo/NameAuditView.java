package cn.sfj.jiaowutong.web.vo;

import java.time.LocalDateTime;

public record NameAuditView(String viewerName, String reason, LocalDateTime viewedAt) {
}
