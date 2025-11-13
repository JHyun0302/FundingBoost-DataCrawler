package kcs.funding.crawler.Runner;

import kcs.funding.crawler.service.ItemCrawlService;
import kcs.funding.crawler.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CrawlerScheduler {

    private final ItemCrawlService itemCrawlService;

    private final ItemService itemService;

    // 매일 오전 0시 실행
    @Scheduled(cron = "0 0 0 * * *")
    public void crawlDaily() {
        itemCrawlService.crawlAllBrands(30);
    }

    // 매일 오전 3시 실행
    @Scheduled(cron = "0 0 3 * * *")
    public void deleteItem() {
        // 7일이 지난 데이터는 삭제한다.
        itemService.purgeOldItems(7);
    }
}