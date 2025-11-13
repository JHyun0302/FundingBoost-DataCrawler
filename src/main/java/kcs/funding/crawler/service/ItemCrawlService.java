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
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
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

    // ====== Public APIs ======

    @PreDestroy
    public void shutdown() {
        try { driver.quit(); } catch (Exception ignored) {}
    }

    /** 전체 브랜드 일괄 적재 */
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

    /** 단일 브랜드 적재 (리스트 → 상세 보강) */
    public int crawlBrandPage(String brandUrl, String brandName, String categoryName, int perBrandLimit) {
        driver.get(brandUrl);
        waitDomReady();
        lazyScroll(5, 500);
        waitPriceHints();

        // 1) 리스트 페이지에서 앵커들을 JS로 스냅샷(문자열 데이터만) 수집
        List<ProductRow> rows = collectListSnapshotJS();
        if (rows.isEmpty()) {
            log.info("no product anchors found: {}", brandUrl);
            return 0;
        }

        // perBrandLimit 적용
        if (perBrandLimit > 0 && rows.size() > perBrandLimit) {
            rows = rows.subList(0, perBrandLimit);
        }

        int affected = 0;

        // 2) 상세 보강(가격 0일 때만 새 탭 열어 보강)
        for (ProductRow r : rows) {
            // 필수값 검증
            if (r.productId == null || r.productId.isBlank()) continue;

            // 가격 보강
            if (r.price <= 0) {
                int v = fetchPriceFromDetailInNewTab(r.productId);
                if (v > 0) r.price = v;
            }

            // 이미지 없으면 스킵(@NotEmpty 회피)
            if (isFallbackImage(r.imageUrl) || r.imageUrl.isBlank()) {
                log.debug("skip item due to empty image; pid={}", r.productId);
                continue;
            }

            final String fPid = r.productId;
            final String fName = r.name;
            final int fPrice = r.price;
            final String fImage = r.imageUrl;
            final String fBrand = brandName;
            final String fCategory = categoryName;
            final String fOption = extractOptionsByDetailInNewTab(r.productId); // 필요 없다면 null 반환 허용

            /**
             * 이미 존재하는 item은 db에 insert하지 않고 pass
             */
            // 1) productId 기준으로 먼저 update 시도
            var existingByPid = itemRepository.findByProductId(fPid);
            if (existingByPid.isPresent()) {
                existingByPid.get().update(fName, fPrice, fImage, fOption);
                // update만 했으므로 신규 건수 증가 없음
                continue;
            }

            // 2) productId는 없지만 brand+category+imageUrl 이 이미 있으면 "같은 아이템"으로 보고 insert 스킵
            boolean duplicated =
                    itemRepository.existsByBrandNameAndCategoryAndAndItemImageUrl(fBrand, fCategory, fImage);

            if (duplicated) {
                log.debug("skip insert due to duplicated brand/category/image. brand={}, category={}, imageUrl={}", fBrand, fCategory, fImage);
                // 신규 insert 안 하고 스킵
                continue;
            }

            // 3) 여기까지 왔으면 완전히 신규 아이템 -> insert
            Item item = Item.createItem(fName, fPrice, fImage, fBrand, fCategory, fOption, fPid);
            itemRepository.save(item);
            affected++;
        }

        log.info("brand='{}' ({}) -> {} items", brandName, brandUrl, affected);
        return affected;
    }

    // ====== Snapshot & Parsing ======

    /** 리스트 페이지에서 pid, name, priceText, imageUrl을 문자열로 수집 (표준 CSS만 사용) */
    @SuppressWarnings("unchecked")
    private List<ProductRow> collectListSnapshotJS() {
        String script =
                "const anchors = Array.from(document.querySelectorAll(\"a[href^='/product/'][aria-label]\"));\n" +
                        "function cleanName(aria){\n" +
                        "  if(!aria) return '';\n" +
                        "  let s = aria.replace('상품명 :', '상품명:').replace('상품명 :', '상품명:').trim();\n" +
                        "  const p = s.indexOf('판매가'); if(p>0) s = s.substring(0,p);\n" +
                        "  s = s.replace('상품명:','').trim();\n" +
                        "  return s;\n" +
                        "}\n" +
                        "function findPriceText(node){\n" +
                        "  const re = /(\\d{1,3}(?:,\\d{3})*)\\s*원/;\n" +
                        "  const seen = new Set();\n" +
                        "  function textOf(n){return ((n&&n.innerText)||'') + ' ' + ((n&&n.textContent)||'');}\n" +
                        "  // 자기 자신과 자손 순회 (깊이 제한)\n" +
                        "  const walker = document.createTreeWalker(node, NodeFilter.SHOW_ELEMENT);\n" +
                        "  let depth=0, cur=node; \n" +
                        "  while(cur){\n" +
                        "    if(!seen.has(cur)){\n" +
                        "      seen.add(cur);\n" +
                        "      const t = textOf(cur).replace(/\\s+/g,' ').trim();\n" +
                        "      const m = re.exec(t); if(m) return m[0];\n" +
                        "    }\n" +
                        "    cur = walker.nextNode(); depth++; if(depth>800) break;\n" +
                        "  }\n" +
                        "  // 가까운 컨테이너(카드)와 부모까지 확장\n" +
                        "  const card = node.closest('li,div,article') || node.parentElement;\n" +
                        "  if(card){\n" +
                        "    let t = textOf(card).replace(/\\s+/g,' ').trim();\n" +
                        "    let m = re.exec(t); if(m) return m[0];\n" +
                        "    if(card.parentElement){\n" +
                        "      t = textOf(card.parentElement).replace(/\\s+/g,' ').trim();\n" +
                        "      m = re.exec(t); if(m) return m[0];\n" +
                        "    }\n" +
                        "  }\n" +
                        "  return '';\n" +
                        "}\n" +
                        "function normalize(u){ if(!u) return ''; u=u.trim(); if(u.startsWith('//')) return 'https:'+u; return u; }\n" +
                        "function imageUrlOf(node){\n" +
                        "  let img = node.querySelector('img');\n" +
                        "  if(img){\n" +
                        "    let u = img.currentSrc || img.getAttribute('src') || img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy');\n" +
                        "    if(u) return normalize(u);\n" +
                        "  }\n" +
                        "  const s = node.querySelector(\"img[srcset],source[srcset],img[src*='fname='],source[src*='fname=']\");\n" +
                        "  if(s){\n" +
                        "    let src = s.getAttribute('srcset') || s.getAttribute('src') || '';\n" +
                        "    if(src.includes(' ')) src = src.split(/\\s+/)[0];\n" +
                        "    if(src.includes('fname=')){\n" +
                        "      const tail = src.split('fname=')[1]||''; const fname = tail.split('&')[0]||'';\n" +
                        "      return fname.startsWith('http') ? fname : ('https:'+fname);\n" +
                        "    }\n" +
                        "    return normalize(src);\n" +
                        "  }\n" +
                        "  return '';\n" +
                        "}\n" +
                        "return anchors.map(a=>{\n" +
                        "  const href = a.getAttribute('href')||'';\n" +
                        "  const m = /\\/product\\/(\\d+)/.exec(href);\n" +
                        "  const pid = m?m[1]:'';\n" +
                        "  const name = cleanName(a.getAttribute('aria-label')||'');\n" +
                        "  const priceText = findPriceText(a);\n" +
                        "  const img = imageUrlOf(a);\n" +
                        "  return [pid,name,priceText,img];\n" +
                        "});";

        Object result = ((JavascriptExecutor) driver).executeScript(script);
        List<ProductRow> out = new ArrayList<>();

        if (result instanceof List) {
            List<?> rows = (List<?>) result;
            for (Object row : rows) {
                if (!(row instanceof List)) continue;
                List<?> cols = (List<?>) row;
                String pid = getString(cols, 0);
                String name = getString(cols, 1);
                String priceText = getString(cols, 2);
                String img = getString(cols, 3);

                int price = parsePriceFromText(priceText);
                if (price <= 0) {
                    // priceText 없었어도 최종 파서는 last resort로 전역 숫자 파싱 시도 가능
                    price = 0;
                }
                if (img != null && img.startsWith("//")) img = "https:" + img;

                ProductRow pr = new ProductRow();
                pr.productId = pid;
                pr.name = name == null ? "" : name.trim();
                pr.price = price;
                pr.imageUrl = img == null ? "" : img.trim();
                out.add(pr);
            }
        }
        log.info("snapshot collected: {} anchors", out.size());
        return out;
    }

    private String getString(List<?> cols, int idx) {
        if (cols.size() <= idx) return "";
        Object v = cols.get(idx);
        return v == null ? "" : String.valueOf(v);
    }

    // ====== Detail (new tab) ======

    /** 상세 페이지에서 가격 보강(새 탭 이용해 원본 탭 DOM 보존) */
    private int fetchPriceFromDetailInNewTab(String productId) {
        String detailUrl = "https://gift.kakao.com/product/" + productId;
        String original = driver.getWindowHandle();
        try {
            ((JavascriptExecutor) driver).executeScript("window.open(arguments[0], '_blank');", detailUrl);

            // 탭 전환 대기
            wait.until(numberOfWindowsToBe(2));
            for (String h : driver.getWindowHandles()) {
                if (!h.equals(original)) {
                    driver.switchTo().window(h);
                    break;
                }
            }

            waitDomReady();
            lazyScroll(1, 200);

            // meta 우선
            String meta = (String) ((JavascriptExecutor) driver).executeScript(
                    "var p=document.querySelector(\"meta[property='og:price:amount'],meta[property='product:price:amount']\");" +
                            "return p?p.content:'';");
            int v = parsePriceFromText(meta == null ? "" : meta + "원");
            if (v > 0) return v;

            // 본문 스캔(표준 셀렉터만)
            WebElement body = driver.findElement(By.tagName("body"));
            String txt = body.getText();
            v = parsePriceFromText(txt);
            return Math.max(v, 0);
        } catch (Exception e) {
            log.debug("detail price fetch failed: {}", productId, e);
            return 0;
        } finally {
            try { driver.close(); } catch (Exception ignored) {}
            driver.switchTo().window(original);
            waitDomReady();
        }
    }

    /** 옵션 문자열이 필요할 때만 호출(없으면 null) – 새 탭에서 가볍게 추출 */
    private String extractOptionsByDetailInNewTab(String productId) {
        String detailUrl = "https://gift.kakao.com/product/" + productId;
        String original = driver.getWindowHandle();
        try {
            ((JavascriptExecutor) driver).executeScript("window.open(arguments[0], '_blank');", detailUrl);
            wait.until(numberOfWindowsToBe(2));
            for (String h : driver.getWindowHandles()) {
                if (!h.equals(original)) {
                    driver.switchTo().window(h);
                    break;
                }
            }
            waitDomReady();
            lazyScroll(2, 200);

            String html = driver.getPageSource();
            Document doc = Jsoup.parse(html, detailUrl);

            Elements labels = doc.select("label, button[role=radio], [class*=option] label");
            StringBuilder sb = new StringBuilder();
            for (Element lb : labels) {
                String t = lb.text();
                if (t == null) continue;
                t = t.replaceAll("\\[.*?\\]", "").trim();
                if (t.isBlank()) continue;
                if (t.contains("선택") || t.contains("옵션") || t.contains("수량")) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(t);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { driver.close(); } catch (Exception ignored) {}
            driver.switchTo().window(original);
            waitDomReady();
        }
    }

    // ====== Wait & Utils ======

    private void waitDomReady() {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> ((JavascriptExecutor) d).executeScript("return document.readyState").equals("complete"));
    }

    private void lazyScroll(int times, long sleepMs) {
        for (int i = 0; i < times; i++) {
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight)");
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
        }
    }

    /** 가격 힌트 노드가 나타날 때까지 짧게 폴링 (표준 셀렉터만) */
    private void waitPriceHints() {
        for (int i = 0; i < 6; i++) {
            Boolean ok = (Boolean) ((JavascriptExecutor) driver).executeScript(
                    "return !!document.querySelector('[class*=\"price\"], strong, span');");
            if (Boolean.TRUE.equals(ok)) return;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    private static final Pattern P_PRICE_ANY = Pattern.compile("(\\d{1,3}(?:,\\d{3})*)(?=\\s*원)");

    private int parsePriceFromText(String text) {
        if (text == null) return 0;
        Matcher m = P_PRICE_ANY.matcher(text);
        if (m.find()) {
            String num = m.group(1).replace(",", "");
            try {
                long v = Long.parseLong(num);
                if (v <= 0) return 0;
                return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private boolean isFallbackImage(String url) {
        return url == null || url.isBlank() || url.contains("default_fallback_thumbnail.png");
    }

    private ExpectedCondition<Boolean> numberOfWindowsToBe(int n) {
        return d -> d != null && d.getWindowHandles().size() == n;
    }

    // ====== DTO ======
    private static class ProductRow {
        String productId;
        String name;
        int    price;
        String imageUrl;
    }
}
