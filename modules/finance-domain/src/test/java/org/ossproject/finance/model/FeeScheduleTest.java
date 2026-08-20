package org.ossproject.finance.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class FeeScheduleTest {

    private final FeeSchedule kiwoom = FeeSchedule.kiwoomMockDefaults();

    /** 거래세는 매도에만 붙는다. 매수에 붙이면 실제보다 비싸게 안내하게 된다. */
    @Test void chargesTheTransactionTaxOnSellsOnly() {
        TradeCosts buy = kiwoom.costsFor(OrderSide.BUY, new BigDecimal("220500"));
        TradeCosts sell = kiwoom.costsFor(OrderSide.SELL, new BigDecimal("220500"));

        assertEquals(0, BigDecimal.ZERO.compareTo(buy.tax()));
        assertTrue(sell.tax().signum() > 0);
    }

    /** 증권사도 원 미만은 절사한다. 올림하면 실제보다 크게 안내된다. */
    @Test void roundsDownToWholeWon() {
        TradeCosts costs = kiwoom.costsFor(OrderSide.BUY, new BigDecimal("220500"));

        assertEquals(0, new BigDecimal("771").compareTo(costs.commission()),
                "220,500 x 0.35% = 771.75 이므로 771 원입니다");
    }

    /** 매수는 더 나가고 매도는 덜 들어온다. 총액만 보면 이 차이를 알 수 없다. */
    @Test void settlementAddsOnBuyAndSubtractsOnSell() {
        BigDecimal amount = new BigDecimal("1000000");

        BigDecimal buy = kiwoom.costsFor(OrderSide.BUY, amount).settlementAmount(OrderSide.BUY, amount);
        BigDecimal sell = kiwoom.costsFor(OrderSide.SELL, amount).settlementAmount(OrderSide.SELL, amount);

        assertTrue(buy.compareTo(amount) > 0, "매수는 주문 금액보다 더 나갑니다");
        assertTrue(sell.compareTo(amount) < 0, "매도는 대금에서 비용을 뺀 만큼 들어옵니다");
    }

    /** 요율을 모르면 지어내지 않는다. */
    @Test void reportsUnknownInsteadOfGuessingARate() {
        TradeCosts costs = FeeSchedule.unknown().costsFor(OrderSide.BUY, new BigDecimal("220500"));

        assertFalse(costs.isKnown());
        assertEquals(0, new BigDecimal("220500")
                .compareTo(costs.settlementAmount(OrderSide.BUY, new BigDecimal("220500"))),
                "모르면 주문 금액을 그대로 둡니다");
    }

    @Test void rejectsANegativeRate() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeeSchedule(new BigDecimal("-0.001"), BigDecimal.ZERO));
    }

    /** 주문 확인은 되돌릴 수 없는 동작 직전이다. 읽어 주는 문장에 비용이 있어야 한다. */
    @Test void spokenPreviewMentionsTheCostAndWhatWillActuallyLeaveTheAccount() {
        OrderCommand command = OrderCommand.limit("005930", "삼성전자", OrderSide.BUY,
                1, new BigDecimal("220500"));
        BigDecimal amount = command.estimatedAmount(null);
        TradePreview preview = new TradePreview(command, new BigDecimal("220500"), amount,
                new BigDecimal("1000000"), new BigDecimal("779500"),
                kiwoom.costsFor(OrderSide.BUY, amount));

        String spoken = preview.describe();

        assertTrue(spoken.contains("수수료와 세금"), spoken);
        assertTrue(spoken.contains("낼 금액"), spoken);
    }

    @Test void spokenPreviewSaysSoWhenTheRateIsUnknown() {
        OrderCommand command = OrderCommand.limit("005930", "삼성전자", OrderSide.BUY,
                1, new BigDecimal("220500"));
        TradePreview preview = new TradePreview(command, new BigDecimal("220500"),
                command.estimatedAmount(null), new BigDecimal("1000000"), new BigDecimal("779500"));

        assertTrue(preview.describe().contains("설정되지 않아"), preview.describe());
    }
}
