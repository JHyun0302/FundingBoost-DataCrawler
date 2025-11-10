package kcs.funding.crawler.Runner;

import kcs.funding.crawler.service.ItemCrawlService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CrawlerScheduler {

    private final ItemCrawlService itemCrawlService;

    // 매일 새벽 3시 실행
    @Scheduled(cron = "0 0 3 * * *")
    public void crawlDaily() {
        itemCrawlService.crawlAllBrands(30);
    }
}