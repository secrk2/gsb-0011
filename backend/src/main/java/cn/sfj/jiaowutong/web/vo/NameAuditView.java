package cn.sfj.jiaowutong.web.vo;

/**
 * 全名查看留痕视图。viewedAtUtc 为 UTC（Z 结尾），localTime 已按司法所时区换算。
 */
public record NameAuditView(String viewerName, String reason,
                            String viewedAtUtc, String viewedAtLocal) {
}
