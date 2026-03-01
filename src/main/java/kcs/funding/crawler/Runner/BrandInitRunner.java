package kcs.funding.crawler.Runner;

import kcs.funding.crawler.service.BrandDiscoveryService;
import kcs.funding.crawler.service.ItemCrawlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "crawler.bootstrap", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class BrandInitRunner implements ApplicationRunner {

    private final BrandDiscoveryService brandDiscoveryService;
    private final ItemCrawlService itemCrawlService;

    @Value("${crawler.bootstrap.item-limit:20}")
    private int bootstrapItemLimit;

    @Override
    public void run(ApplicationArguments args) {
        log.info("🚀 초기 데이터 자동 적재 시작");
        brandDiscoveryService.discoverBrands();
        int crawled = itemCrawlService.crawlAllBrands(bootstrapItemLimit);
        log.info("✅ 초기 데이터 자동 적재 완료 (크롤링={})", crawled);
    }
}
