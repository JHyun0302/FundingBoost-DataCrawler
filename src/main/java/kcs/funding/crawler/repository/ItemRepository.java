package kcs.funding.crawler.repository;

import java.util.Optional;
import kcs.funding.crawler.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByProductId(String productId);
}
