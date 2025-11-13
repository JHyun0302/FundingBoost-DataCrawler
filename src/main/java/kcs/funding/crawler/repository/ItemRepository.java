package kcs.funding.crawler.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kcs.funding.crawler.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByProductId(String productId);

    // brand+category+imageUrl 이 이미 있으면 "같은 아이템"으로 보고 insert 스킵
    boolean existsByBrandNameAndCategoryAndAndItemImageUrl(String brandName, String category, String itemImageUrl);

    // 오래된 데이터 일괄 삭제
    int deleteByModifiedDateBefore(LocalDateTime threshold);
}
