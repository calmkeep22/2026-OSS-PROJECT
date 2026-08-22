package org.ossproject.kiwoom.query;

import org.ossproject.kiwoom.config.KiwoomRestClient;
import org.ossproject.kiwoom.mapping.KiwoomTr;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 키움 종목 조회를 애플리케이션 포트에 연결한다.
 *
 * <p>키움에는 이름 조각으로 찾는 검색 TR 이 없다. 시장별 종목 목록(ka10099)을 받아 두고
 * 그 안에서 거른다. 목록은 장중에 바뀌지 않으므로 한 번 받아 캐시한다. 호출 유량이 매우
 * 좁아 검색할 때마다 부르면 곧바로 한도에 걸린다.
 *
 * <p>목록 조회는 현재가를 주지 않는다. 전일 종가를 현재가 자리에 넣지 않고 시세 없음으로
 * 둔다. 실제 시세는 상세를 열 때 {@link #getDetail(String)} 이 조회한다.
 */
public final class KiwoomStockQueryAdapter implements StockQueryPort {

    /** 코스피와 코스닥만 받는다. ETF·ELW 까지 넣으면 목록이 커지고 검색이 산만해진다. */
    private static final List<MarketSegment> SEGMENTS = List.of(
            new MarketSegment("0", "국내", "KRX"),
            new MarketSegment("10", "국내", "KRX"));

    private final KiwoomRestClient client;
    private final AtomicReference<List<SecuritySummary>> cachedUniverse = new AtomicReference<>();

    public KiwoomStockQueryAdapter(KiwoomRestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<SecuritySummary> search(String query, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("조회 개수는 1 이상이어야 합니다.");
        }
        return universe().stream()
                .filter(summary -> summary.matches(query))
                .sorted(Comparator.comparing(SecuritySummary::symbol))
                .limit(limit)
                .toList();
    }

    @Override
    public StockDetail getDetail(String symbol) {
        return client.fetchStockDetail(symbol);
    }

    /** 캐시된 종목 목록. 처음 호출할 때만 증권사에서 받아 온다. */
    private List<SecuritySummary> universe() {
        List<SecuritySummary> cached = cachedUniverse.get();
        if (cached != null) {
            return cached;
        }
        List<SecuritySummary> loaded = new ArrayList<>();
        for (MarketSegment segment : SEGMENTS) {
            loaded.addAll(loadSegment(segment));
        }
        List<SecuritySummary> universe = List.copyOf(loaded);
        cachedUniverse.compareAndSet(null, universe);
        return cachedUniverse.get();
    }

    private List<SecuritySummary> loadSegment(MarketSegment segment) {
        JsonNode root = client.callRaw("종목 목록 조회", KiwoomTr.SECURITY_LIST,
                "{\"mrkt_tp\":\"" + segment.code() + "\"}");
        JsonNode list = root.get("list");
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<SecuritySummary> securities = new ArrayList<>(list.size());
        for (JsonNode node : list) {
            String code = textOf(node, "code");
            String name = textOf(node, "name");
            if (code == null || name == null) {
                continue;
            }
            // 상장폐지나 거래정지 종목은 검색에 올리지 않는다.
            String state = textOf(node, "state");
            if (state != null && state.contains("폐지")) {
                continue;
            }
            securities.add(SecuritySummary.withoutQuote(
                    code.trim(), name.trim(), segment.market(), segment.exchange(), "KRW"));
        }
        return securities;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    /** 시장 구분 코드와 화면 표기. */
    private record MarketSegment(String code, String market, String exchange) {
    }
}
