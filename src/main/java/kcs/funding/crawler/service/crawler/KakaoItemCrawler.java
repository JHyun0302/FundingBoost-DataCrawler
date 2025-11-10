package kcs.funding.crawler.service.crawler;

import java.util.Optional;
import jakarta.transaction.Transactional;
import kcs.funding.crawler.entity.Item;
import kcs.funding.crawler.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoItemCrawler {

    private final ItemRepository itemRepository;

    /**
     * @return 처리된 건수(신규+업데이트)
     */
    @Transactional
    public int crawlBrand(String brandUrl, String brandName, String categoryName) {
        int affected = 0;
        try {
            Document doc = Jsoup.connect(brandUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();

            // 카카오 선물하기는 동적 렌더링이 있을 수 있음 -> 필요하면 Selenium으로 대체
            Elements productLinks = doc.select("a[href^=/product/]");

            for (Element link : productLinks) {
                String aria = link.attr("aria-label");
                if (!aria.contains("상품명")) continue;

                String itemName = extractName(aria);
                int itemPrice = extractPrice(aria);

                String imageUrl = link.select("img").attr("src");
                if (!imageUrl.startsWith("http")) {
                    imageUrl = "https:" + imageUrl;
                }

                String productId = link.attr("href")
                        .replace("/product/", "")
                        .trim();

                String optionName = crawlOptionNames(productId);

                Optional<Item> existing = itemRepository.findByProductId(productId);
                if (existing.isPresent()) {
                    Item item = existing.get();
                    item.update(itemName, itemPrice, imageUrl, optionName);
                    affected++;
                    continue;
                }

                Item item = Item.createItem(
                        itemName,
                        itemPrice,
                        imageUrl,
                        brandName,
                        categoryName,
                        optionName,
                        productId
                );

                itemRepository.save(item);
                affected++;
            }

        } catch (Exception e) {
            throw new RuntimeException("크롤링 실패 -> " + brandUrl, e);
        }
        return affected;
    }

    private String crawlOptionNames(String productId) {
        try {
            String detailUrl = "https://gift.kakao.com/product/" + productId;
            Document detailDoc = Jsoup.connect(detailUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();

            // 필요시 더 정교화
            Elements labelList = detailDoc.select("label");
            if (labelList.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            for (Element label : labelList) {
                String text = label.text().replaceAll("\\[.*?\\]", "").trim();
                if (!text.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(text);
                }
            }
            return sb.toString();

        } catch (Exception e) {
            return null; // 옵션 없는 상품은 null
        }
    }

    private String extractName(String ariaLabel) {
        return ariaLabel.split("판매가")[0]
                .replace("상품명:", "")
                .replace("상품명 :", "")
                .trim();
    }

    private int extractPrice(String ariaLabel) {
        String price = ariaLabel.replaceAll("[^0-9]", "");
        return price.isEmpty() ? 0 : Integer.parseInt(price);
    }
}
