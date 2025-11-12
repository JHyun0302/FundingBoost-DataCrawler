package kcs.funding.crawler.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.bonigarcia.wdm.WebDriverManager;
import kcs.funding.crawler.entity.BrandTarget;
import kcs.funding.crawler.repository.BrandTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrandDiscoveryService {

    private final BrandTargetRepository brandTargetRepository;
    private static final Pattern BRAND_ID = Pattern.compile("/brand/(\\d+)");

    private static final int brandUrlCnt = 10;

    public void discoverBrands() {
        Map<String, String> categories = Map.of(
                "뷰티", "https://gift.kakao.com/category/6",
                "패션", "https://gift.kakao.com/category/7",
                "식품", "https://gift.kakao.com/category/5",
                "디지털", "https://gift.kakao.com/category/8",
                "리빙", "https://gift.kakao.com/category/4",
                "스포츠", "https://gift.kakao.com/category/9"
        );

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new","--disable-gpu","--no-sandbox",
                "--window-size=1280,2000","--disable-dev-shm-usage",
                "--lang=ko-KR","--user-agent=Mozilla/5.0");
        WebDriverManager.chromedriver().setup();

        WebDriver driver = new ChromeDriver(options);
        // 실행 중 중복 방지(동일 트랜잭션 가시성 문제 회피)
        Set<String> seenUrls = new HashSet<>();

        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

            categories.forEach((categoryName, url) -> {
                try {
                    driver.get(url);
                    wait.until(d -> ((JavascriptExecutor)d)
                            .executeScript("return document.readyState").equals("complete"));
                    // 후행 로딩 대기
                    Thread.sleep(1000);

                    // 1) 카테고리 페이지에서 '유니크한 브랜드 URL'만 추출
                    Document doc = Jsoup.parse(driver.getPageSource(), url);
                    // a[href*="/brand/"] 중 /brand/<숫자> 형식만
                    LinkedHashSet<String> brandUrls = doc.select("a[href*=\"/brand/\"]").stream()
                            .map(a -> abs("https://gift.kakao.com", a.attr("href")))
                            .map(BrandDiscoveryService::normalizeBrandUrl) // https, 슬래시, 쿼리 제거
                            .filter(href -> BRAND_ID.matcher(href).find())
                            .collect(Collectors.toCollection(LinkedHashSet::new));

                    // 중복 제거 후 상위 10개
                    List<String> top10 = brandUrls.stream().limit(brandUrlCnt).toList();
                    log.info("category={} candidates={} picked={}", categoryName, brandUrls.size(), top10.size());

                    for (String brandUrl : top10) {
                        // 실행 중 중복/기등록 모두 스킵
                        if (!seenUrls.add(brandUrl)) {
                            log.debug("skip (seen in this run): {}", brandUrl);
                            continue;
                        }
                        if (brandTargetRepository.existsByBrandUrl(brandUrl)) {
                            log.debug("skip (exists in DB): {}", brandUrl);
                            continue;
                        }

                        // 2) 브랜드 상세 페이지에서 '정확한 브랜드명' 추출
                        String brandName = fetchBrandName(driver, wait, brandUrl);

                        if (brandName == null || brandName.isBlank()) {
                            log.warn("brand name not found: {}", brandUrl);
                            continue;
                        }
                        // “브랜드명:” 같은 접두 제거(안전망)
                        brandName = brandName.replaceFirst("^\\s*브랜드명\\s*[:：]\\s*", "").trim();

                        BrandTarget target = BrandTarget.create(brandName, categoryName, brandUrl);
                        brandTargetRepository.save(target);
                        log.info("saved: [{}] {} -> {}", categoryName, brandName, brandUrl);
                    }
                } catch (Exception e) {
                    // 개별 카테고리 실패는 로깅만 하고 다음 카테고리 진행
                    log.error("카테고리 수집 실패 - {} : {}", categoryName, e.getMessage(), e);
                }
            });
        } finally {
            driver.quit();
        }
        log.info("브랜드 자동 수집 완료");
    }

    private static String abs(String base, String href) {
        if (href == null) return "";
        if (href.startsWith("http")) return href;
        if (!href.startsWith("/")) href = "/" + href;
        return base + href;
    }

    private static String normalizeBrandUrl(String url) {
        // https 강제 / 쿼리/프래그먼트 제거 / 마지막 슬래시 제거
        String u = url.replaceFirst("^http://", "https://");
        int q = u.indexOf('?'); if (q > -1) u = u.substring(0, q);
        int h = u.indexOf('#'); if (h > -1) u = u.substring(0, h);
        if (u.endsWith("/")) u = u.substring(0, u.length()-1);
        return u;
    }

    private static String fetchBrandName(WebDriver driver, WebDriverWait wait, String brandUrl) throws InterruptedException {
        driver.get(brandUrl);
        wait.until(d -> ((JavascriptExecutor)d)
                .executeScript("return document.readyState").equals("complete"));
        Thread.sleep(600);

        Document brandDoc = Jsoup.parse(((ChromeDriver)driver).getPageSource(), brandUrl);

        Element og = brandDoc.selectFirst("meta[property=og:title]");
        if (og != null && !og.attr("content").isBlank()) {
            return og.attr("content").trim();
        }
        Element h = brandDoc.selectFirst("h1, h2, .brand-title, .BrandTitle, [data-brand-title]");
        return h != null ? h.text().trim() : null;
    }
}