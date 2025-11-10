package kcs.funding.crawler.service;

import jakarta.annotation.PreDestroy;
import kcs.funding.crawler.entity.BrandTarget;
import kcs.funding.crawler.entity.Item;
import kcs.funding.crawler.repository.BrandTargetRepository;
import kcs.funding.crawler.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItemCrawlService {

    private final ItemRepository itemRepository;
    private final BrandTargetRepository brandTargetRepository;

    private final WebDriver driver;
    private final WebDriverWait wait;

    @PreDestroy
    public void shutdown() {
        driver.quit();
    }

    /** 전체 브랜드 표를 돌며 적재 */
    public int crawlAllBrands(int perBrandLimit) {
        List<BrandTarget> brands = brandTargetRepository.findAll();
        int total = 0;
        for (BrandTarget b : brands) {
            try {
                total += crawlBrandPage(b.getBrandUrl(), b.getBrandName(), b.getCategoryName(), perBrandLimit);
            } catch (Exception ex) {
                log.warn("brand crawl failed: {} ({})", b.getBrandName(), b.getBrandUrl(), ex);
            }
        }
        log.info("ALL DONE. total items affected={}", total);
        return total;
    }

    /** 단일 브랜드 테스트 */
    public int crawlBrandPage(String brandUrl, String brandName, String categoryName, int perBrandLimit) {
        driver.get(brandUrl);
        waitUntilDomReady();

        // lazy-load 유도
        lazyScroll(5, 600);
        waitPriceNodesShort();

        String html = driver.getPageSource();
        Document doc = Jsoup.parse(html, brandUrl);

        // 상품 카드 앵커 (aria-label 포함)
        Elements cards = doc.select("a[href^=/product/][aria-label]");
        int affected = 0;

        for (Element a : cards) {
            if (perBrandLimit > 0 && affected >= perBrandLimit) break;

            String href = a.attr("href");
            String productId = extractProductId(href);
            if (productId == null) continue;

            String aria = a.attr("aria-label");
            String name = extractNameFromAria(aria);

            int price = resolvePriceNearAnchor(a);
            // 가격이 여전히 0이면 디버깅 로그를 남겨 원인 파악
            if (price <= 0) {
                log.debug("price not found; aria='{}', anchorText='{}'",
                        aria, a.text());
            }

            // 이미지 (lazy 속성 우선)
            Element img = a.selectFirst("img");
            String image = extractImage(img);
            if (isFallbackImage(image)) {
                // srcset이나 fname 파라미터에서 복원 시도
                image = extractImageFromSrcset(a);
            }
            if (isFallbackImage(image)) {
                // 여전히 기본 썸네일이면 skip
                continue;
            }

            // 상세 옵션 (필요 시 Selenium으로 상세 페이지 열어 파싱)
            String option = extractOptionsBySelenium(productId);

            final String fName = name;
            final int fPrice = price;
            final String fImage = image;
            final String fOption = option;
            final String fBrand = brandName;
            final String fCategory = categoryName;
            final String fPid = productId;

            // upsert
            affected += itemRepository.findByProductId(fPid)
                    .map(it -> {
                        it.update(fName, fPrice, fImage, fOption);
                        return 0;
                    })
                    .orElseGet(() -> {
                        Item item = Item.createItem(fName, fPrice, fImage, fBrand, fCategory, fOption, fPid);
                        itemRepository.save(item);
                        return 1;
                    });
        }

        log.info("brand='{}' ({}) -> {} items", brandName, brandUrl, affected);
        return affected;
    }

    // --- helpers ---

    private void waitUntilDomReady() {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> ((JavascriptExecutor) d).executeScript("return document.readyState").equals("complete"));
    }

    private void lazyScroll(int times, long sleepMs) {
        for (int i = 0; i < times; i++) {
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
        }
    }

    private String extractProductId(String href) {
        if (href == null) return null;
        // /product/12345?param= -> 12345
        String path = href;
        int idx = path.indexOf("/product/");
        if (idx < 0) return null;
        String tail = path.substring(idx + "/product/".length());
        int q = tail.indexOf('?');
        return (q >= 0) ? tail.substring(0, q) : tail;
    }

    private String extractNameFromAria(String aria) {
        if (aria == null) return "";
        // "상품명: XXX 판매가 12,345원" 패턴
        String s = aria.replace("상품명 :", "상품명:")
                .replace("상품명 :", "상품명:")
                .trim();
        int p = s.indexOf("판매가");
        if (p > 0) s = s.substring(0, p);
        s = s.replace("상품명:", "").trim();
        return s;
    }

    // 기존: aria 기반
    private static final Pattern P_PRICE_ARIA = Pattern.compile("판매가\\s*([\\d,]+)\\s*원");
    // 텍스트 어디서든 "숫자 + 원" 을 잡되, 가장 앞(가까운) 매치만 사용
    private static final Pattern P_PRICE_ANY = Pattern.compile("(\\d{1,3}(?:,\\d{3})*)(?=\\s*원)");

    private int parsePriceFromAria(String aria) {
        if (aria == null) return 0;
        Matcher m = P_PRICE_ARIA.matcher(aria);
        if (m.find()) {
            String num = m.group(1).replace(",", "");
            return safeToInt(num);
        }
        return 0;
    }

    private int parsePriceFromText(String text) {
        if (text == null) return 0;
        Matcher m = P_PRICE_ANY.matcher(text);
        if (m.find()) {
            String num = m.group(1).replace(",", "");
            return safeToInt(num);
        }
        return 0;
    }

    private int safeToInt(String digits) {
        try {
            long v = Long.parseLong(digits);
            if (v <= 0) return 0;
            if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE; // 비정상 큰 값 보호
            return (int) v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 앵커 주변에서 가격 텍스트를 찾는다: aria → 앵커 내부 → 카드 노드(closest li/div/article) → 형제 노드 */
    private int resolvePriceNearAnchor(Element a) {
        // 1) aria-label 우선
        int price = parsePriceFromAria(a.attr("aria-label"));
        if (price > 0) return price;

        // 2) 앵커 내부에서 직접 찾기
        Element inside = a.selectFirst("*:matchesOwn(\\d[\\d,]*\\s*원), .price, [class*=price], strong, span");
        if (inside != null) {
            price = parsePriceFromText(inside.text());
            if (price > 0) return price;
        }

        // 3) 카드 컨테이너(가장 가까운 li/div/article)에서 찾기
        Element card = a.closest("li, div, article");
        if (card != null) {
            Element inCard = card.selectFirst("*:matchesOwn(\\d[\\d,]*\\s*원), .price, [class*=price], strong, span");
            if (inCard != null) {
                price = parsePriceFromText(inCard.text());
                if (price > 0) return price;
            }
            // 4) 형제들까지 범위 조금 확장
            Element parent = card.parent();
            if (parent != null) {
                Element near = parent.selectFirst("*:matchesOwn(\\d[\\d,]*\\s*원), .price, [class*=price], strong, span");
                if (near != null) {
                    price = parsePriceFromText(near.text());
                    if (price > 0) return price;
                }
            }
        }

        return 0;
    }

    private String extractImage(Element img) {
        if (img == null) return "";
        String url = firstNonBlank(
                img.attr("data-src"),
                img.attr("data-original"),
                img.attr("data-lazy"),
                img.attr("src")
        );
        if (url.startsWith("//")) url = "https:" + url;
        return url;
    }

    private String extractImageFromSrcset(Element scope) {
        if (scope == null) return "";
        // fname= 파라미터에서 원본 추출
        Element any = scope.selectFirst("img[srcset], source[srcset], img[src*='fname='], source[src*='fname=']");
        if (any == null) return "";
        String src = firstNonBlank(any.attr("srcset"), any.attr("src"));
        if (src == null) return "";
        // srcset일 경우 첫 URL 취득
        if (src.contains(" ")) src = src.split("\\s+")[0];
        if (src.contains("fname=")) {
            int i = src.indexOf("fname=");
            String tail = src.substring(i + 6);
            int amp = tail.indexOf('&');
            String fname = (amp > 0 ? tail.substring(0, amp) : tail);
            // fname은 URL-encoded일 수 있음
            return fname.startsWith("http") ? fname : "https:" + fname;
        }
        if (src.startsWith("//")) return "https:" + src;
        return src;
    }

    private boolean isFallbackImage(String url) {
        return url == null || url.isBlank()
                || url.contains("default_fallback_thumbnail.png");
    }

    private String firstNonBlank(String... arr) {
        for (String s : arr) {
            if (s != null && !s.isBlank()) return s;
        }
        return "";
    }

    /** 옵션까지 필요할 때 상세 페이지도 Selenium으로 열어서 파싱 */
    private String extractOptionsBySelenium(String productId) {
        try {
            String detailUrl = "https://gift.kakao.com/product/" + productId;
            driver.navigate().to(detailUrl);
            waitUntilDomReady();
            // 상세에서도 lazy-load 약간 대기
            lazyScroll(2, 300);

            String html = driver.getPageSource();
            Document doc = Jsoup.parse(html, detailUrl);

            // 라벨 기반 옵션 추출 (동적 구조 대응 위해 넓게 잡음)
            Elements labels = doc.select("label, button[role=radio], [class*=option] label");
            StringBuilder sb = new StringBuilder();
            for (Element lb : labels) {
                String t = lb.text();
                // 가격/수량 등의 라벨은 제외
                if (t == null) continue;
                t = t.replaceAll("\\[.*?\\]", "").trim();
                if (t.isBlank()) continue;
                // 너무 일반적인 단어는 제외(필요시 보정)
                if (t.contains("선택") || t.contains("옵션") || t.contains("수량")) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(t);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            // 상세에서 돌아가 브랜드 목록 계속
            driver.navigate().back();
            waitUntilDomReady();
        }
    }

    private void waitPriceNodesShort() {
        // 0.8초 내에서 4회 폴링
        for (int i = 0; i < 4; i++) {
            Boolean ok = (Boolean) ((JavascriptExecutor) driver).executeScript(
                    "return !!document.querySelector('[class*=\"price\"], strong, span')");
            if (Boolean.TRUE.equals(ok)) return;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }
}
