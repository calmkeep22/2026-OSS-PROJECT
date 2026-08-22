package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.AiInsightPort;
import org.ossproject.ai.AiUnavailableException;
import org.ossproject.ai.Confidence;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.Exchange;
import org.ossproject.finance.model.SecurityId;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiInsightViewModelTest {

    private static final SecurityId SAMSUNG = new SecurityId("005930", Exchange.KRX);
    private static final SecurityId HYNIX = new SecurityId("000660", Exchange.KRX);

    private final MarketApplicationService market = new MarketApplicationService(
            new FakeStockQueryAdapter(), new FakeCandleQueryAdapter(),
            new FakeMarketDataStreamAdapter(), Runnable::run);

    /** 봉을 받았는지 확인하려고 마지막 호출을 기록한다. */
    private static final class RecordingAi implements AiInsightPort {
        private final AiInsight answer;
        private final RuntimeException failure;
        int barsSeen = -1;
        boolean similarAsked;

        RecordingAi(AiInsight answer, RuntimeException failure) {
            this.answer = answer;
            this.failure = failure;
        }

        @Override public AiInsight brief(SecurityId security, List<Candle> bars, boolean withSimilar) {
            barsSeen = bars.size();
            similarAsked = withSimilar;
            if (failure != null) throw failure;
            return answer;
        }

        @Override public boolean available() { return failure == null; }
        @Override public String unavailableReason() { return failure == null ? "" : failure.getMessage(); }
    }

    private static AiInsight sample() {
        return AiInsight.of("005930", "삼성전자", "삼성전자가 오늘 크게 움직일 확률 52퍼센트입니다.",
                Confidence.HIGH, true);
    }

    /** 서비스가 스스로 조회하면 증권사 시세와 어긋난다. 우리가 받아 넘긴다. */
    @Test
    @DisplayName("조회한 봉을 함께 넘긴다")
    void sendsTheBarsWeFetched() {
        RecordingAi ai = new RecordingAi(sample(), null);
        AtomicReference<AiInsight> got = new AtomicReference<>();

        new AiInsightViewModel(market, ai, Runnable::run)
                .analyze(SAMSUNG, false, got::set, reason -> fail(reason));

        assertNotNull(got.get());
        assertTrue(ai.barsSeen > 0, "봉을 넘겨야 합니다");
        assertFalse(ai.similarAsked, "목록용 호출에서는 유사종목을 끕니다");
    }

    @Test
    @DisplayName("상세에서는 닮은 종목까지 받는다")
    void asksForSimilarOnlyWhenRequested() {
        RecordingAi ai = new RecordingAi(sample(), null);

        new AiInsightViewModel(market, ai, Runnable::run)
                .analyze(SAMSUNG, true, insight -> { }, reason -> fail(reason));

        assertTrue(ai.similarAsked);
    }

    /** 분석이 없는 것과 "이상 없음" 은 다른 뜻이다. 실패를 조용히 넘기지 않는다. */
    @Test
    @DisplayName("받지 못하면 이유를 그대로 전한다")
    void reportsWhyItFailed() {
        RecordingAi ai = new RecordingAi(null,
                new AiUnavailableException("AI 서비스에 연결하지 못했습니다."));
        AtomicReference<String> reason = new AtomicReference<>();

        new AiInsightViewModel(market, ai, Runnable::run)
                .analyze(SAMSUNG, false, insight -> fail("성공하면 안 됩니다"), reason::set);

        assertEquals("AI 서비스에 연결하지 못했습니다.", reason.get());
    }

    /** 늦게 온 결과가 이미 바뀐 종목의 화면을 덮으면 안 된다. */
    @Test
    @DisplayName("종목이 바뀐 뒤 도착한 결과는 버린다")
    void dropsResultsForAStockTheUserLeft() {
        RecordingAi ai = new RecordingAi(sample(), null);
        AtomicInteger delivered = new AtomicInteger();
        AiInsightViewModel viewModel = new AiInsightViewModel(market, ai, Runnable::run);

        viewModel.analyze(SAMSUNG, false, insight -> delivered.incrementAndGet(), reason -> { });
        int afterFirst = delivered.get();
        viewModel.analyze(HYNIX, false, insight -> delivered.incrementAndGet(), reason -> { });

        assertEquals(afterFirst + 1, delivered.get(), "각 요청이 한 번씩만 전달되어야 합니다");
    }

    @Test
    @DisplayName("취소한 뒤에는 결과를 전하지 않는다")
    void deliversNothingAfterCancel() {
        RecordingAi ai = new RecordingAi(sample(), null);
        AtomicInteger delivered = new AtomicInteger();
        AiInsightViewModel viewModel = new AiInsightViewModel(market, ai, Runnable::run);

        viewModel.cancel();
        viewModel.analyze(SAMSUNG, false, insight -> delivered.incrementAndGet(), reason -> { });

        assertEquals(1, delivered.get(), "취소는 이후 요청을 막지 않습니다");
    }
}
