package kcs.funding.crawler.Runner;

import kcs.funding.crawler.service.BrandDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BrandInitRunner implements ApplicationRunner {

    private final BrandDiscoveryService brandDiscoveryService;

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("🚀 브랜드 자동 초기 수집 시작");
        brandDiscoveryService.discoverBrands();
        System.out.println("✅ 브랜드 자동 초기 수집 완료");
    }
}
