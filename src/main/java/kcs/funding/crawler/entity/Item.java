package kcs.funding.crawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "item")
public class Item {
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
    @Column(name = "item_image_url")
    private String itemImageUrl;

    @NotEmpty
    @Column(name = "brand_name", length = 3000)
    private String brandName;

    @NotEmpty
    @Column(name = "category", length = 3000)
    private String category;

    @Column(name = "option_name", length = 3000)
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
        item.itemImageUrl = itemImageUrl;
        item.brandName = brandName;
        item.category = category;
        item.optionName = optionName;
        item.productId = productId;
        return item;
    }

    public void update(String itemName, int itemPrice, String imageUrl, String optionName) {
        this.itemName = itemName;
        this.itemPrice = itemPrice;
        this.itemImageUrl = imageUrl;
        this.optionName = optionName;
    }

}

