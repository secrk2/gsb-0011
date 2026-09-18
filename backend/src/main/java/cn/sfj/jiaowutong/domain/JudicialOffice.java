package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

/**
 * 司法所（矫正对象数据隔离的最小单元）
 */
@Entity
@Table(name = "judicial_office")
public class JudicialOffice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 机构编码，唯一 */
    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    /** 所属乡镇/街道 */
    @Column(length = 64)
    private String region;

    /** 电子围栏中心（司法所/规定活动区域） */
    @Column(nullable = false)
    private Double centerLat;

    @Column(nullable = false)
    private Double centerLng;

    /** 围栏半径（米）；未配置多边形活动范围时的回退圆形围栏 */
    @Column(nullable = false)
    private Integer fenceRadiusMeters;

    /**
     * 司法所所在 IANA 时区（如 Asia/Shanghai、Asia/Urumqi、Europe/Berlin）。
     * 定位时间一律 UTC 存储，按此时区换算“当地今天/星期几/禁行日”，
     * 夏令时由 ZoneId 规则自动处理，不做手工时差加减。
     */
    @Column(name = "zone_id", nullable = false, length = 48,
            columnDefinition = "varchar(48) default 'Asia/Shanghai'")
    private String timezone = "Asia/Shanghai";

    /** 禁行星期（MONDAY..SUNDAY）：该日对象不得超出活动范围，可空表示无禁行日规定 */
    @Column(name = "forbidden_weekday", length = 16)
    private String forbiddenWeekday;

    public JudicialOffice() {
    }

    public JudicialOffice(String code, String name, String region,
                          Double centerLat, Double centerLng, Integer fenceRadiusMeters) {
        this(code, name, region, centerLat, centerLng, fenceRadiusMeters,
                "Asia/Shanghai", null);
    }

    public JudicialOffice(String code, String name, String region,
                          Double centerLat, Double centerLng, Integer fenceRadiusMeters,
                          String timezone, String forbiddenWeekday) {
        this.code = code;
        this.name = name;
        this.region = region;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
        this.fenceRadiusMeters = fenceRadiusMeters;
        this.timezone = timezone;
        this.forbiddenWeekday = forbiddenWeekday;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getRegion() { return region; }
    public Double getCenterLat() { return centerLat; }
    public Double getCenterLng() { return centerLng; }
    public Integer getFenceRadiusMeters() { return fenceRadiusMeters; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getForbiddenWeekday() { return forbiddenWeekday; }
    public void setForbiddenWeekday(String forbiddenWeekday) { this.forbiddenWeekday = forbiddenWeekday; }
}
