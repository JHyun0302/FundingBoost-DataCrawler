package kcs.funding.crawler.controller;

import kcs.funding.crawler.service.BrandDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InitController {

    private final BrandDiscoveryService brandDiscoveryService;

    @PostMapping("/init/brands")
    public String initBrands() {
        brandDiscoveryService.discoverBrands();
        return "브랜드 수집 완료";
    }
}

