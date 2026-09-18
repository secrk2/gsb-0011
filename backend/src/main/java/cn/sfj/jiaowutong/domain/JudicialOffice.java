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

    /** 围栏半径（米） */
    @Column(nullable = false)
    private Integer fenceRadiusMeters;

    public JudicialOffice() {
    }

    public JudicialOffice(String code, String name, String region,
                          Double centerLat, Double centerLng, Integer fenceRadiusMeters) {
        this.code = code;
        this.name = name;
        this.region = region;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
        this.fenceRadiusMeters = fenceRadiusMeters;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getRegion() { return region; }
    public Double getCenterLat() { return centerLat; }
    public Double getCenterLng() { return centerLng; }
    public Integer getFenceRadiusMeters() { return fenceRadiusMeters; }
}
