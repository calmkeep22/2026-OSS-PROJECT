package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 계좌 전체 상태. 예수금과 보유 종목을 함께 가진다.
 *
 * <p>불변 객체이며 변경은 새 인스턴스를 반환한다.
 */
public record Account(String accountNo, Balance balance, List<Position> positions) {

    public Account {
        if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("계좌번호는 필수입니다.");
        }
        if (balance == null) {
            throw new IllegalArgumentException("잔고는 필수입니다.");
        }
        positions = List.copyOf(positions == null ? List.of() : positions);
    }

    public static Account of(String accountNo, BigDecimal cash) {
        return new Account(accountNo, Balance.of(cash), List.of());
    }

    public Optional<Position> position(String symbol) {
        if (symbol == null) {
            return Optional.empty();
        }
        return positions.stream().filter(p -> p.symbol().equals(symbol)).findFirst();
    }

    /** 보유 종목 평가 금액 합계. 예수금은 제외한다. */
    public BigDecimal totalMarketValue() {
        return positions.stream().map(Position::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 예수금을 포함한 총 자산. */
    public BigDecimal totalAssets() {
        return balance.cash().add(totalMarketValue());
    }

    public BigDecimal totalProfitLoss() {
        return positions.stream().map(Position::profitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Account withBalance(Balance newBalance) {
        return new Account(accountNo, newBalance, positions);
    }

    /**
     * 종목을 추가하거나 교체한다. 수량이 0이 되면 목록에서 뺀다.
     * 기존 종목의 표시 순서는 유지한다.
     */
    public Account withPosition(Position position) {
        if (position == null) {
            throw new IllegalArgumentException("보유 종목은 필수입니다.");
        }
        List<Position> updated = new ArrayList<>(positions.size() + 1);
        boolean replaced = false;
        for (Position existing : positions) {
            if (existing.symbol().equals(position.symbol())) {
                if (position.quantity() > 0) {
                    updated.add(position);
                }
                replaced = true;
            } else {
                updated.add(existing);
            }
        }
        if (!replaced && position.quantity() > 0) {
            updated.add(position);
        }
        return new Account(accountNo, balance, updated);
    }

    /** 실시간 시세를 보유 종목에 반영한다. 보유하지 않은 종목이면 그대로 돌려준다. */
    public Account applyQuote(Quote quote) {
        if (quote == null) {
            return this;
        }
        return position(quote.symbol())
                .map(p -> withPosition(p.withCurrentPrice(quote.price())))
                .orElse(this);
    }

    /** 로그에 남길 때 쓰는 마스킹된 계좌번호. */
    public String maskedAccountNo() {
        return maskAccountNo(accountNo);
    }

    /** 계좌번호 뒷자리 4개만 남기고 가린다. */
    public static String maskAccountNo(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            return "****";
        }
        String digits = accountNo.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }
}
