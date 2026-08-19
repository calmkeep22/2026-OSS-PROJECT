package org.ossproject.desktop.viewmodel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 시장 스캐너의 필터·정렬 규칙.
 *
 * <p>종목 목록을 직접 들고 있지 않는다. 예전에는 화면에 보여 줄 종목과 등락률을 코드에
 * 적어 두었는데, 화면을 볼 수 없는 사용자는 그 값이 실제 시장 순위인지 확인할 방법이 없다.
 * 순위 조회 TR(ka10030 당일거래량상위, ka10032 거래대금상위 등)을 연동하기 전까지는 빈
 * 목록을 돌려주고, 화면이 연동되지 않았음을 안내한다.
 */
public final class ScannerViewModel {
    private final List<ScannerItem> items;

    /** 순위 조회를 연동하기 전의 기본 상태. 결과가 비어 있다. */
    public ScannerViewModel() {
        this(List.of());
    }

    /**
     * 조회 결과로 스캐너를 만든다.
     *
     * @param items 증권사에서 받은 순위 종목
     */
    public ScannerViewModel(List<ScannerItem> items) {
        this.items = List.copyOf(java.util.Objects.requireNonNull(items, "items"));
    }

    /** 보여 줄 순위 데이터가 있는지 여부. */
    public boolean hasData() {
        return !items.isEmpty();
    }

    public List<ScannerItem> filter(String market, String criterion, long minimumVolume) {
        List<ScannerItem> result = new ArrayList<>(items.stream()
                .filter(item -> marketMatches(item, market))
                .filter(item -> item.volume() >= minimumVolume).toList());
        result.sort(comparator(criterion)); return result;
    }

    private boolean marketMatches(ScannerItem item, String market) {
        if (market == null || market.equals("전체")) return true;
        if (market.equals("국내 전체")) return item.market().equals("KOSPI") || item.market().equals("KOSDAQ");
        return item.market().equals(market);
    }

    private Comparator<ScannerItem> comparator(String criterion) {
        Comparator<ScannerItem> comparator = switch (criterion == null ? "거래량" : criterion) {
            case "거래대금" -> Comparator.comparingLong(ScannerItem::tradingValueMillion);
            case "상승률", "하락률" -> Comparator.comparingDouble(ScannerItem::changeRate);
            default -> Comparator.comparingLong(ScannerItem::volume);
        };
        return "하락률".equals(criterion) ? comparator : comparator.reversed();
    }
}
