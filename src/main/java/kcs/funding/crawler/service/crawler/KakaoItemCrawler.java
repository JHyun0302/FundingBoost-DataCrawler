package kcs.funding.crawler.service.crawler;

import java.util.Optional;
import kcs.funding.crawler.entity.Item;
import kcs.funding.crawler.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KakaoItemCrawler {

    private final ItemRepository itemRepository;

    public void crawlBrand(String brandUrl, String brandName, String categoryName) {
        try {
            Document doc = Jsoup.connect(brandUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(8000)
                    .get();

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

                // ✅ 상품 상세 페이지에서 옵션 정보 가져오기
                String productId = link.attr("href").replace("/product/", "").trim();
                String optionName = crawlOptionNames(productId);

                // 파싱한 데이터가 이미 DB에 쌓인 데이터인 경우
                Optional<Item> existing = itemRepository.findByProductId(productId);
                if (existing.isPresent()) {
                    // 업데이트 (가격/이미지/옵션)
                    Item item = existing.get();
                    item.update(itemName, itemPrice, imageUrl, optionName);
                    continue;
                }

                Item item = Item.createItem(
                        itemName,
                        itemPrice,
                        imageUrl,
                        brandName,
                        categoryName,
                        optionName
                );

                itemRepository.save(item);
            }

        } catch (Exception e) {
            throw new RuntimeException("크롤링 실패 -> " + brandUrl, e);
        }
    }

    private String crawlOptionNames(String productId) {
        try {
            String detailUrl = "https://gift.kakao.com/product/" + productId;
            Document detailDoc = Jsoup.connect(detailUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(8000)
                    .get();

            Elements radios = detailDoc.select("div:contains(선택옵션) ~ ul input[type=radio] + label, input[type=radio]");

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
