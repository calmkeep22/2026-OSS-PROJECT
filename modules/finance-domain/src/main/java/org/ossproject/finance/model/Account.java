package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 계좌 전체 상태. 예수금과 보유 종목을 함께 가진다.
 *
 * <p>불변 객체이며 변경은 새 인스턴스를 반환한다.
 *
 * @param deposits        예수금 단계. 증권사가 주지 않으면 {@link Deposits#from(Balance)} 로 채운다
 * @param reported        증권사가 계산해 내려준 계좌 총계. 직접 더한 값보다 이쪽이 우선이다.
 *                        미수·신용·대출·수수료처럼 우리가 세지 않는 항목까지 포함하므로,
 *                        직접 더하면 증권사 화면과 어긋난다
 * @param estimatedAssets 증권사 추정예탁자산. 없으면 {@code null}
 */
public record Account(String accountNo, Balance balance, List<Position> positions,
                      Deposits deposits, ReportedValuation reported, BigDecimal estimatedAssets) {

    public Account {
        if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("계좌번호는 필수입니다.");
        }
        if (balance == null) {
            throw new IllegalArgumentException("잔고는 필수입니다.");
        }
        positions = List.copyOf(positions == null ? List.of() : positions);
        deposits = deposits == null ? Deposits.from(balance) : deposits;
        reported = reported == null ? ReportedValuation.none() : reported;
    }

    /** 증권사 요약 없이 원장만으로 만든 계좌. 모의 거래가 쓴다. */
    public Account(String accountNo, Balance balance, List<Position> positions) {
        this(accountNo, balance, positions, null, null, null);
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

    /** 보유 종목 평가 금액 합계. 예수금은 제외한다. 증권사가 준 총평가금액이 있으면 그것을 쓴다. */
    public BigDecimal totalMarketValue() {
        return reported.or(reported.evaluation(), positions.stream().map(Position::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** 총매입금액. 증권사가 준 값이 있으면 그것을 쓴다. */
    public BigDecimal totalPurchase() {
        return reported.or(reported.purchaseAmount(), positions.stream().map(Position::costBasis)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * 예수금을 포함한 총 자산.
     *
     * <p>증권사가 추정예탁자산을 내려줬으면 그 값을 그대로 쓴다. 실제 증권사 프로그램도
     * 직접 더하지 않고 서버가 계산한 값을 보여 준다. 미수금, 신용융자, 예탁담보대출,
     * 미수령 배당처럼 우리가 세지 않는 항목이 있어서, 직접 더하면 증권사 화면과 어긋난다.
     *
     * <p>증권사 값이 없거나 시세 반영으로 보유 평가액이 바뀐 뒤에는 직접 더한다. 이때
     * 더하는 현금은 예수금이 아니라 <b>D+2 추정예수금</b>이다. 예수금을 쓰면 매수 당일
     * 대금이 현금과 주식 양쪽에서 세어진다.
     */
    public BigDecimal totalAssets() {
        return estimatedAssets != null
                ? estimatedAssets
                : deposits.settledCash().add(totalMarketValue());
    }

    /** 총자산이 증권사가 계산한 값인지. 거짓이면 앱이 직접 더한 값이다. */
    public boolean totalAssetsReportedByBroker() {
        return estimatedAssets != null;
    }

    /**
     * 총 평가손익. 증권사가 준 값이 있으면 그것을 쓴다.
     *
     * <p>직접 더한 값은 매매수수료와 거래세를 세지 않아 증권사 화면보다 이익 쪽으로 치우친다.
     */
    public BigDecimal totalProfitLoss() {
        return reported.or(reported.profitLoss(), positions.stream().map(Position::profitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** 계좌 총계가 증권사가 계산한 값인지. 거짓이면 앱이 직접 더한 값이다. */
    public boolean valuationReportedByBroker() {
        return reported.isPresent() || estimatedAssets != null;
    }

    /** 원장이 바뀌면 증권사 요약은 더 이상 맞지 않는다. 예수금 단계도 원장에서 다시 잡는다. */
    public Account withBalance(Balance newBalance) {
        return new Account(accountNo, newBalance, positions, null, null, null);
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
        // 보유가 바뀌면 증권사가 준 총계는 낡은 값이다. 예수금은 그대로 두고 총계만 버린다.
        return new Account(accountNo, balance, updated, deposits, null, null);
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
