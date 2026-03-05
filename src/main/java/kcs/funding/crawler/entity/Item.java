package kcs.funding.crawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import kcs.funding.crawler.entity.common.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "item", indexes = {
        @Index(name = "idx_item_product_id", columnList = "product_id"),
        @Index(name = "idx_item_brand_category_image", columnList = "brand_name, category, item_image_url"),
        @Index(name = "idx_item_modified_date", columnList = "modified_date")
})
public class Item extends BaseTimeEntity {
    @Id
    @Column(name = "item_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long itemId;

    @NotEmpty
    @Column(name = "item_name", length = 100)
    private String itemName;

    @NotNull
    @Column(name = "item_price")
    private int itemPrice;

    @NotEmpty
    @Column(name = "item_image_url", length = 255)
    private String itemImageUrl;

    @NotEmpty
    @Column(name = "brand_name", length = 100)
    private String brandName;

    @NotEmpty
    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "option_name", length = 2000)
    private String optionName;

    @Column(name = "product_id", unique = true, nullable = false)
    private String productId;

    public static Item createItem(
            String itemName, int itemPrice, String itemImageUrl,
            String brandName, String category, String optionName, String productId
    ) {
        Item item = new Item();
        item.itemName = itemName;
        item.itemPrice = itemPrice;
        item.itemImageUrl = normalizeImageUrl(itemImageUrl);
        item.brandName = brandName;
        item.category = category;
        item.optionName = optionName;
        item.productId = productId;
        return item;
    }

    public void update(String itemName, int itemPrice, String imageUrl, String optionName) {
        this.itemName = itemName;
        this.itemPrice = itemPrice;
        this.itemImageUrl = normalizeImageUrl(imageUrl);
        if (optionName != null && !optionName.isBlank()) {
            this.optionName = optionName;
        }
    }

    private static String normalizeImageUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }

        String normalized = rawUrl.trim();
        if (normalized.startsWith("//")) {
            normalized = "https:" + normalized;
        }

        for (int i = 0; i < 2; i++) {
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http%3a") || lower.startsWith("https%3a")) {
                normalized = URLDecoder.decode(normalized, StandardCharsets.UTF_8);
                continue;
            }
            break;
        }

        return normalized;
    }

}
