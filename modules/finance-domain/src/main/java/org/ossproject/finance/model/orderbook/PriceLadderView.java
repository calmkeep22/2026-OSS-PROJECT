package org.ossproject.finance.model.orderbook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 화면에 그릴 준비가 끝난 고정 가격 격자.
 *
 * <p>화면 계층은 이 객체만 받아서 그리면 된다. 정규화·재조정 판단은 모두 끝나 있다.
 *
 * @param symbol       종목 코드
 * @param rows         위(고가)에서 아래(저가) 순서
 * @param maxSize      막대 정규화 기준이 된 최대 잔량
 * @param recentered   이번 갱신에서 가격 축이 옮겨졌는지 여부
 * @param announcement 축이 옮겨졌을 때 사용자에게 알릴 문장. 아니면 {@code null}
 */
public record PriceLadderView(
        String symbol,
        List<PriceLadderRow> rows,
        long maxSize,
        boolean recentered,
        String announcement,
        Instant timestamp
) {
    public PriceLadderView {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        rows = List.copyOf(rows == null ? List.of() : rows);
        if (maxSize < 0) {
            throw new IllegalArgumentException("최대 잔량은 0 이상이어야 합니다.");
        }
    }

    public Optional<String> announcementIfPresent() {
        return Optional.ofNullable(announcement);
    }

    /** 기준가가 있는 행. 키보드 탐색의 시작 위치로 쓴다. */
    public Optional<PriceLadderRow> currentPriceRow() {
        return rows.stream().filter(PriceLadderRow::currentPriceRow).findFirst();
    }

    public Optional<BigDecimal> highestPrice() {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).price());
    }

    public Optional<BigDecimal> lowestPrice() {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(rows.size() - 1).price());
    }
}
