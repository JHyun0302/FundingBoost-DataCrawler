package kcs.funding.crawler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kcs.funding.crawler.entity.BrandTarget;
import kcs.funding.crawler.entity.Item;
import kcs.funding.crawler.repository.BrandTargetRepository;
import kcs.funding.crawler.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItemCrawlService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient OPTION_API_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final int OPTION_STORAGE_MAX_COUNT = 80;
    private static final int OPTION_STORAGE_MAX_CHARS = 1800;

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
        navigateWithWindowRecovery(brandUrl);
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
        int optionExtracted = 0;

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
            final String fOption = extractOptions(r.productId); // 옵션 API 우선, 실패 시 DOM fallback
            if (fOption != null && !fOption.isBlank()) {
                optionExtracted++;
            }

            /**
             * 이미 존재하는 item은 db에 insert하지 않고 pass
             */
            // 1) productId 기준으로 먼저 update 시도
            var existingByPid = itemRepository.findByProductId(fPid);
            if (existingByPid.isPresent()) {
                Item existingItem = existingByPid.get();
                existingItem.update(fName, fPrice, fImage, fOption);
                itemRepository.save(existingItem);
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

        log.info("brand='{}' ({}) -> {} items, options extracted={}", brandName, brandUrl, affected, optionExtracted);
        return affected;
    }

    private String extractOptions(String productId) {
        String optionsFromApi = extractOptionsByApi(productId);
        if (optionsFromApi != null && !optionsFromApi.isBlank()) {
            return optionsFromApi;
        }
        return extractOptionsByDetailInNewTab(productId);
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
                        "function rawSrcOf(el){\n" +
                        "  if(!el) return '';\n" +
                        "  let src = el.currentSrc || el.getAttribute('src') || el.getAttribute('data-src') || el.getAttribute('data-original') || el.getAttribute('data-lazy') || '';\n" +
                        "  const srcset = el.getAttribute('srcset') || '';\n" +
                        "  if(!src && srcset){ src = srcset.split(',')[0].trim().split(/\\s+/)[0]; }\n" +
                        "  if(src.includes('fname=')){\n" +
                        "    const tail = src.split('fname=')[1]||''; const fname = tail.split('&')[0]||'';\n" +
                        "    try { src = decodeURIComponent(fname); } catch(e) { src = fname; }\n" +
                        "    src = src.startsWith('http') ? src : ('https:'+src);\n" +
                        "  }\n" +
                        "  if(/^https?%3a/i.test(src)){\n" +
                        "    try { src = decodeURIComponent(src); } catch(e) {}\n" +
                        "  }\n" +
                        "  return normalize(src);\n" +
                        "}\n" +
                        "function isBadgeCandidate(url, width, height, cls, alt){\n" +
                        "  const s = (url||'').toLowerCase();\n" +
                        "  const c = (cls||'').toLowerCase();\n" +
                        "  const a = (alt||'').toLowerCase();\n" +
                        "  if(!s) return true;\n" +
                        "  if(width > 0 && width < 120) return true;\n" +
                        "  if(height > 0 && height < 120) return true;\n" +
                        "  if(s.includes('badge') || s.includes('icon') || s.includes('sticker') || s.includes('label') || s.includes('tag')) return true;\n" +
                        "  if(c.includes('badge') || c.includes('icon') || c.includes('sticker') || c.includes('label') || c.includes('tag')) return true;\n" +
                        "  if(a.includes('뱃지') || a.includes('배지') || a.includes('태그') || a.includes('아이콘')) return true;\n" +
                        "  return false;\n" +
                        "}\n" +
                        "function imageUrlOf(node){\n" +
                        "  const media = Array.from(node.querySelectorAll('img, source'));\n" +
                        "  let best = '';\n" +
                        "  let bestArea = -1;\n" +
                        "  for(const el of media){\n" +
                        "    const url = rawSrcOf(el);\n" +
                        "    if(!url) continue;\n" +
                        "    const width = Number(el.getAttribute('width') || el.clientWidth || el.naturalWidth || 0);\n" +
                        "    const height = Number(el.getAttribute('height') || el.clientHeight || el.naturalHeight || 0);\n" +
                        "    const cls = el.getAttribute('class') || '';\n" +
                        "    const alt = el.getAttribute('alt') || '';\n" +
                        "    if(isBadgeCandidate(url, width, height, cls, alt)) continue;\n" +
                        "    const area = Math.max(width, 1) * Math.max(height, 1);\n" +
                        "    if(area >= bestArea){ best = url; bestArea = area; }\n" +
                        "  }\n" +
                        "  if(best) return best;\n" +
                        "  let img = node.querySelector('img');\n" +
                        "  if(img){\n" +
                        "    let u = rawSrcOf(img);\n" +
                        "    if(u) return u;\n" +
                        "  }\n" +
                        "  const s = node.querySelector(\"img[srcset],source[srcset],img[src*='fname='],source[src*='fname=']\");\n" +
                        "  if(s){\n" +
                        "    let src = rawSrcOf(s);\n" +
                        "    if(src) return src;\n" +
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
        String original = getCurrentWindowHandleSafely();
        String detailHandle = null;
        try {
            detailHandle = openDetailTab(detailUrl, original);
            if (detailHandle == null) {
                return 0;
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
            closeDetailTabAndRestoreOriginal(detailHandle, original);
        }
    }

    /** 옵션 문자열이 필요할 때만 호출(없으면 null) – 새 탭에서 가볍게 추출 */
    @SuppressWarnings("unchecked")
    private String extractOptionsByDetailInNewTab(String productId) {
        String detailUrl = "https://gift.kakao.com/product/" + productId;
        String original = getCurrentWindowHandleSafely();
        String detailHandle = null;
        try {
            detailHandle = openDetailTab(detailUrl, original);
            if (detailHandle == null) {
                return null;
            }
            waitDomReady();
            lazyScroll(2, 200);

            Object rawOptions = ((JavascriptExecutor) driver).executeScript(
                    "const collected = [];\n" +
                            "const seen = new Set();\n" +
                            "function pushText(value) {\n" +
                            "  if (!value) return;\n" +
                            "  let text = String(value).replace(/\\s+/g, ' ').trim();\n" +
                            "  if (!text) return;\n" +
                            "  const lower = text.toLowerCase();\n" +
                            "  if (lower.includes('상품 옵션을 선택') || lower.includes('선택해주세요')) return;\n" +
                            "  if (lower === '선택' || lower === '옵션' || lower === '수량') return;\n" +
                            "  if (lower.includes('구매하기') || lower.includes('펀딩하기') || lower.includes('gifthub')) return;\n" +
                            "  if (text.length > 80) return;\n" +
                            "  if (!seen.has(text)) {\n" +
                            "    seen.add(text);\n" +
                            "    collected.push(text);\n" +
                            "  }\n" +
                            "}\n" +
                            "document.querySelectorAll('select option').forEach((option) => {\n" +
                            "  if (option.disabled) return;\n" +
                            "  const value = (option.textContent || '').trim();\n" +
                            "  if (!value || !option.value) return;\n" +
                            "  pushText(value);\n" +
                            "});\n" +
                            "document.querySelectorAll('[role=\"option\"], [role=\"radio\"], input[type=\"radio\"] + label').forEach((node) => {\n" +
                            "  pushText(node.innerText || node.textContent || '');\n" +
                            "});\n" +
                            "document.querySelectorAll('[data-option-name], [data-option], [data-testid*=\"option\"], [data-sentry-component*=\"Option\"]').forEach((node) => {\n" +
                            "  pushText(node.getAttribute('data-option-name'));\n" +
                            "  pushText(node.getAttribute('data-option'));\n" +
                            "  pushText(node.innerText || node.textContent || '');\n" +
                            "});\n" +
                            "const decodeEscaped = (value) => {\n" +
                            "  if (!value) return '';\n" +
                            "  try {\n" +
                            "    return JSON.parse('\"' + value.replace(/\\\\/g, '\\\\\\\\').replace(/\"/g, '\\\\\"') + '\"');\n" +
                            "  } catch (e) {\n" +
                            "    return value;\n" +
                            "  }\n" +
                            "};\n" +
                            "document.querySelectorAll('script#__NEXT_DATA__, script[type=\"application/json\"], script[type=\"application/ld+json\"]').forEach((scriptNode) => {\n" +
                            "  const text = (scriptNode.textContent || '').trim();\n" +
                            "  if (!text) return;\n" +
                            "  let match;\n" +
                            "  const regex = /\\\"optionName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"/g;\n" +
                            "  while ((match = regex.exec(text)) !== null) {\n" +
                            "    pushText(decodeEscaped(match[1]));\n" +
                            "  }\n" +
                            "});\n" +
                            "return collected;");

            List<String> normalizedOptions = normalizeOptionValues(rawOptions);
            if (!normalizedOptions.isEmpty()) {
                return compactOptionLines(normalizedOptions);
            }

            return null;
        } catch (Exception e) {
            return null;
        } finally {
            closeDetailTabAndRestoreOriginal(detailHandle, original);
        }
    }

    private String extractOptionsByApi(String productId) {
        String apiUrl = "https://gift.kakao.com/a/product-detail/v1/products/" + productId + "/options";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://gift.kakao.com/product/" + productId)
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) FundingBoostCrawler/1.0")
                    .build();

            HttpResponse<String> response = OPTION_API_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return null;
            }

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            List<String> rawOptions = extractOptionCandidatesFromApi(root);
            List<String> normalizedOptions = normalizeOptionValues(rawOptions);
            if (normalizedOptions.isEmpty()) {
                return null;
            }

            return compactOptionLines(normalizedOptions);
        } catch (Exception e) {
            log.debug("option api fetch failed: pid={}", productId, e);
            return null;
        }
    }

    private List<String> extractOptionCandidatesFromApi(JsonNode root) {
        List<String> candidates = new ArrayList<>();
        collectOptionCandidates(root.path("simpleOptions"), candidates);
        collectOptionCandidates(root.path("combinationOptions"), candidates);
        collectOptionCandidates(root.path("customs"), candidates);
        return candidates;
    }

    private void collectOptionCandidates(JsonNode node, List<String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectOptionCandidates(child, out);
            }
            return;
        }

        if (node.isObject()) {
            appendTextField(out, node, "value");
            appendTextField(out, node, "name");
            appendTextField(out, node, "optionName");
            collectOptionCandidates(node.path("options"), out);
            collectOptionCandidates(node.path("simpleOptions"), out);
            collectOptionCandidates(node.path("combinationOptions"), out);
            return;
        }

        if (node.isTextual()) {
            out.add(node.asText(""));
        }
    }

    private void appendTextField(List<String> out, JsonNode objectNode, String fieldName) {
        JsonNode fieldValue = objectNode.get(fieldName);
        if (fieldValue != null && fieldValue.isTextual()) {
            out.add(fieldValue.asText(""));
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

    private List<String> normalizeOptionValues(Object rawOptions) {
        if (!(rawOptions instanceof List<?> rawList)) {
            return List.of();
        }

        Set<String> deduplicated = new LinkedHashSet<>();
        for (Object rawValue : rawList) {
            if (rawValue == null) {
                continue;
            }

            String normalized = String.valueOf(rawValue)
                    .trim()
                    .replaceAll("\\s+", " ")
                    .replaceFirst("^(option\\s*[=:]\\s*)", "")
                    .replaceFirst("^\\[[^\\]]+\\]\\s*", "")
                    .replaceFirst("^옵션\\s*[=:]\\s*", "")
                    .trim();

            if (normalized.isBlank()) {
                continue;
            }

            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.equals("blank") || lower.equals("none") || lower.equals("null")) {
                continue;
            }
            if (lower.contains("상품 옵션을 선택")
                    || lower.contains("선택해주세요")
                    || lower.equals("옵션")
                    || lower.equals("선택")
                    || lower.equals("수량")) {
                continue;
            }

            if (lower.contains("구매하기") || lower.contains("펀딩하기") || lower.contains("gifthub")) {
                continue;
            }

            if (normalized.length() > 120) {
                continue;
            }

            if (normalized.matches("^[0-9,]+$") || normalized.matches("^[0-9,]+\\s*원$")) {
                continue;
            }

            deduplicated.add(normalized);
        }

        return new ArrayList<>(deduplicated);
    }

    private String compactOptionLines(List<String> options) {
        StringBuilder compacted = new StringBuilder();
        int count = 0;
        for (String option : options) {
            if (option == null || option.isBlank()) {
                continue;
            }
            if (count >= OPTION_STORAGE_MAX_COUNT) {
                break;
            }

            int additionalLength = option.length();
            if (compacted.length() > 0) {
                additionalLength += 1; // newline
            }
            if (compacted.length() + additionalLength > OPTION_STORAGE_MAX_CHARS) {
                break;
            }

            if (compacted.length() > 0) {
                compacted.append('\n');
            }
            compacted.append(option);
            count++;
        }

        return compacted.isEmpty() ? null : compacted.toString();
    }

    private ExpectedCondition<Boolean> numberOfWindowsToBe(int n) {
        return d -> d != null && d.getWindowHandles().size() == n;
    }

    private void navigateWithWindowRecovery(String url) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureWindowContext();
                driver.get(url);
                return;
            } catch (NoSuchWindowException e) {
                last = e;
                log.warn("window context lost while navigating. retry={}", attempt + 1);
                recoverWindowContext();
            }
        }
        if (last != null) {
            throw last;
        }
    }

    private void ensureWindowContext() {
        Set<String> handles = driver.getWindowHandles();
        if (handles.isEmpty()) {
            driver.switchTo().newWindow(WindowType.WINDOW);
            return;
        }

        try {
            driver.getWindowHandle();
        } catch (NoSuchWindowException e) {
            driver.switchTo().window(handles.iterator().next());
        }
    }

    private void recoverWindowContext() {
        try {
            Set<String> handles = driver.getWindowHandles();
            if (handles.isEmpty()) {
                driver.switchTo().newWindow(WindowType.WINDOW);
                return;
            }
            driver.switchTo().window(handles.iterator().next());
        } catch (Exception recoveryError) {
            log.warn("failed to recover window context", recoveryError);
        }
    }

    private String getCurrentWindowHandleSafely() {
        try {
            return driver.getWindowHandle();
        } catch (NoSuchWindowException e) {
            Set<String> handles = driver.getWindowHandles();
            if (handles.isEmpty()) {
                return null;
            }
            String fallback = handles.iterator().next();
            driver.switchTo().window(fallback);
            return fallback;
        }
    }

    private String openDetailTab(String detailUrl, String originalHandle) {
        Set<String> beforeHandles = new HashSet<>(driver.getWindowHandles());
        ((JavascriptExecutor) driver).executeScript("window.open(arguments[0], '_blank');", detailUrl);

        boolean opened = wait.until(d -> d != null && d.getWindowHandles().size() > beforeHandles.size());
        if (!opened) {
            return null;
        }

        for (String handle : driver.getWindowHandles()) {
            if (!beforeHandles.contains(handle)) {
                driver.switchTo().window(handle);
                return handle;
            }
        }

        // 새 핸들을 찾지 못하면 원래 핸들 복귀 시도
        if (originalHandle != null && driver.getWindowHandles().contains(originalHandle)) {
            driver.switchTo().window(originalHandle);
        }
        return null;
    }

    private void closeDetailTabAndRestoreOriginal(String detailHandle, String originalHandle) {
        Set<String> handles = driver.getWindowHandles();
        if (detailHandle != null && handles.contains(detailHandle)) {
            try {
                driver.switchTo().window(detailHandle);
                driver.close();
            } catch (Exception ignored) {}
        }

        Set<String> remainHandles = driver.getWindowHandles();
        try {
            if (originalHandle != null && remainHandles.contains(originalHandle)) {
                driver.switchTo().window(originalHandle);
            } else if (!remainHandles.isEmpty()) {
                driver.switchTo().window(remainHandles.iterator().next());
            }
            waitDomReady();
        } catch (Exception ignored) {}
    }

    // ====== DTO ======
    private static class ProductRow {
        String productId;
        String name;
        int    price;
        String imageUrl;
    }
}
