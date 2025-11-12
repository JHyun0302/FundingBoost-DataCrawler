package kcs.funding.crawler.repository;

import kcs.funding.crawler.entity.BrandTarget;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandTargetRepository extends JpaRepository<BrandTarget, Long> {

    boolean existsByCategoryNameAndBrandUrl(String categoryName, String brandUrl);

    boolean existsByBrandUrl(String brandUrl);
}
