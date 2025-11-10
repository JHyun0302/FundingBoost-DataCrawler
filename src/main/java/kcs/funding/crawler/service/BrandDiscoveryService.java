package kcs.funding.crawler.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
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

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--no-sandbox",
                "--window-size=1280,1800",
                "--disable-dev-shm-usage",
                "--lang=ko-KR", "--user-agent=Mozilla/5.0");
        WebDriverManager.chromedriver().setup();

        WebDriver driver = new ChromeDriver(options);
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            categories.forEach((categoryName, url) -> {
                try {
                    driver.get(url);

                    // 페이지 내 브랜드 섹션이 로드될 때까지 대기 (선택자 상황에 따라 보정)
                    // a[href*="/brand/"]가 아니라 '브랜드' 앵커 블록이 보일 때를 타겟팅
                    wait.until(d -> ((JavascriptExecutor) d)
                            .executeScript("return document.readyState").equals("complete"));

                    // 네트워크 후행 로딩 고려 - 가장 단순한 폴링
                    Thread.sleep(1200);

                    String html = driver.getPageSource();
                    Document doc = Jsoup.parse(html, url);
                    Elements brandLinks = doc.select("a[href*=\"/brand/\"]");

                    log.info("category={}, links={}", categoryName, brandLinks.size());

                    int count = 0;
                    for (Element link : brandLinks) {
                        if (count >= 10) break;

                        String href = link.attr("href");
                        if (!href.startsWith("http")) {
                            href = "https://gift.kakao.com" + href;
                        }
                        String brandUrl = href;
                        String brandName = link.text().trim();

                        // SLF4J placeholder 사용
                        log.info("brandUrl={}", brandUrl);
                        log.info("brandName={}", brandName);

                        if (brandName.isBlank()) continue; // 비어있는 텍스트 방지
                        if (brandTargetRepository.existsByBrandUrl(brandUrl)) continue;

                        BrandTarget target = BrandTarget.create(brandName, categoryName, brandUrl);
                        brandTargetRepository.save(target);
                        count++;
                    }
                } catch (Exception e) {
                    throw new RuntimeException("브랜드 수집 실패: " + url, e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("크롤러 초기화 실패", e);
        } finally {
            driver.quit();
        }

        log.info("브랜드 자동 수집 완료");
    }
}

