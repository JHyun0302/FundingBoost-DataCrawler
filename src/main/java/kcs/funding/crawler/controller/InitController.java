package kcs.funding.crawler.controller;

import kcs.funding.crawler.service.BrandDiscoveryService;
import kcs.funding.crawler.service.ItemCrawlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/init")
public class InitController {

    private final BrandDiscoveryService brandDiscoveryService;

    private final ItemCrawlService crawlService;
    private final ItemCrawlService itemCrawlService;

    @PostMapping("/brands")
    public String initBrands() {
        brandDiscoveryService.discoverBrands();
        return "브랜드 수집 완료";
    }

    // 단일 브랜드 테스트
    @PostMapping("/items/one")
    public String initItems() {
        int n = itemCrawlService.crawlBrandPage(
                "https://gift.kakao.com/brand/9888?display=basic", // 디올
                "디올",
                "뷰티",
                30 // 개수 제한 (필요시 조정)
        );
        return "item 크롤링 완료: " + n;
    }

    // 전체 브랜드 일괄
    @PostMapping("/items/all")
    public String initItemsAll() {
        int n = itemCrawlService.crawlAllBrands(30); // 브랜드당 최대 30개
        return "전체 item 크롤링 완료: " + n;
    }

}

