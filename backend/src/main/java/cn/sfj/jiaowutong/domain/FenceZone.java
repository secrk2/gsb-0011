package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

/**
 * 电子围栏分区（多边形）。
 * kind=ALLOW 规定活动范围（可多个，点须在其中之一）；
 * kind=FORBIDDEN 禁区（进入即越界，优先级高于活动范围）。
 * 顶点环以 “lat,lng;lat,lng;...” 纯文本存储（WGS-84，无需首尾闭合）。
 * 一个司法所未配置任何 ALLOW 多边形时，回退到 JudicialOffice 圆形围栏判定。
 */
@Entity
@Table(name = "fence_zone", indexes = {
        @Index(name = "idx_fence_office", columnList = "office_id,kind")
})
public class FenceZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id")
    private JudicialOffice office;

    @Column(nullable = false, length = 64)
    private String name;

    /** ALLOW=活动范围；FORBIDDEN=禁区 */
    @Column(nullable = false, length = 16)
    private String kind;

    /** 多边形顶点环：lat,lng;lat,lng;... */
    @Column(nullable = false, length = 4000)
    private String ringText;

    @Column(nullable = false)
    private Boolean enabled = true;

    public FenceZone() {
    }

    public FenceZone(JudicialOffice office, String name, String kind, String ringText) {
        this.office = office;
        this.name = name;
        this.kind = kind;
        this.ringText = ringText;
        this.enabled = true;
    }

    public Long getId() { return id; }
    public JudicialOffice getOffice() { return office; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public String getRingText() { return ringText; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
