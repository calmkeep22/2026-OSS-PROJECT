package org.ossproject.ai;

import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.SecurityId;

import java.util.List;

/**
 * AI 분석을 받아 온다.
 *
 * <p>봉은 부르는 쪽이 넘긴다. 서비스가 스스로 조회하게 두면 증권사 시세와 다른 값을 쓰게
 * 되어, 사용자가 화면에서 보는 차트와 분석 근거가 어긋난다.
 *
 * <p>연결되지 않았으면 지어내지 않는다. {@link AiUnavailableException} 을 던져 화면이
 * 그 사실을 적게 한다.
 */
public interface AiInsightPort {

    /**
     * 한 종목 요약.
     *
     * @param bars        일봉. 오래된 것부터 시간순
     * @param withSimilar 닮은 종목까지 받을지. 목록 화면에서는 끄고 상세에서만 켠다.
     *                    인덱스를 처음 만들 때 몇 초가 든다
     */
    AiInsight brief(SecurityId security, List<Candle> bars, boolean withSimilar);

    /** 지금 분석을 받을 수 있는지. 거짓이면 화면은 기능을 감추지 않고 이유를 적는다. */
    boolean available();

    /** 왜 쓸 수 없는지. 쓸 수 있으면 빈 문자열. */
    String unavailableReason();
}
