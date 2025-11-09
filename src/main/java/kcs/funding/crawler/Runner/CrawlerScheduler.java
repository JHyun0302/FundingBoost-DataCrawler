package kcs.funding.crawler.Runner;

import kcs.funding.crawler.service.crawler.KakaoItemCrawler;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CrawlerScheduler {

    private final KakaoItemCrawler kakaoItemCrawler;

    // 매일 새벽 3시 실행
    @Scheduled(cron = "0 0 3 * * *")
    public void crawlDaily() {
        kakaoItemCrawler.crawlBrand(
                "https://gift.kakao.com/brand/9888?display=basic",
                "디올",
                "뷰티"
        );
    }


}