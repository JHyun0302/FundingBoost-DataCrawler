package kcs.funding.crawler.service;

import java.util.Map;
import kcs.funding.crawler.entity.BrandTarget;
import kcs.funding.crawler.repository.BrandTargetRepository;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BrandDiscoveryService {

    private final BrandTargetRepository brandTargetRepository;

    public void discoverBrands() {
        Map<String, String> categories = Map.of(
                "뷰티", "https://gift.kakao.com/category/6",
                "패션", "https://gift.kakao.com/category/7",
                "식품", "https://gift.kakao.com/category/5",
                "디지털", "https://gift.kakao.com/category/8",
                "리빙", "https://gift.kakao.com/category/4",
                "스포츠", "https://gift.kakao.com/category/9"
        );

        categories.forEach((categoryName, url) -> {
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0")
                        .timeout(8000)
                        .get();

                // ✅ "브랜드" 섹션 → 브랜드 링크들
                Elements brandLinks = doc.select("a[href^=/brand/]");

                int count = 0;
                for (Element link : brandLinks) {
                    if (count >= 10) break; // 각 카테고리별 10개만

                    String brandUrl = "https://gift.kakao.com" + link.attr("href");
                    String brandName = link.text().trim();

                    if (brandTargetRepository.existsByBrandUrl(brandUrl)) {
                        continue; // 이미 저장된 브랜드인 경우 skip
                    }

                    // Insert
                    BrandTarget target = BrandTarget.create(brandName, categoryName, brandUrl);
                    brandTargetRepository.save(target);

                    count++;
                }

            } catch (Exception e) {
                throw new RuntimeException("브랜드 수집 실패: " + url, e);
            }
        });

        System.out.println("✅ 브랜드 자동 수집 완료");
    }
}
