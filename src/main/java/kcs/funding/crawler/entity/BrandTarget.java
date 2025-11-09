package kcs.funding.crawler.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "brand_crawl_target",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_brand_url", columnNames = {"brand_url"})
        })
public class BrandTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "brand_target_id")
    private Long brandId;

    @Column(name = "brand_name", length = 100, nullable = false)
    private String brandName;

    @Column(name = "category_name", length = 100, nullable = false)
    private String categoryName;

    @Column(name = "brand_url", length = 500, nullable = false)
    private String brandUrl;

    // 정적 생성 메서드
    public static BrandTarget create(String brandName, String categoryName, String brandUrl) {
        BrandTarget target = new BrandTarget();
        target.brandName = brandName;
        target.categoryName = categoryName;
        target.brandUrl = brandUrl;
        return target;
    }
}

