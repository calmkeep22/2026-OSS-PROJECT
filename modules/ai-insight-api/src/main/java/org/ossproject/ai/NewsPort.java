package org.ossproject.ai;

import org.ossproject.finance.model.SecurityId;

import java.util.List;

/**
 * 뉴스와 질의응답을 받아 온다.
 *
 * <p>{@link AiInsightPort} 와 나눈 이유는 실패 범위가 다르기 때문이다. 뉴스는 남의
 * 서버(RSS)를 거치므로 예측·이상감지가 멀쩡해도 혼자 실패할 수 있다. 한 창구로 묶으면
 * 뉴스가 안 될 때 분석까지 못 쓰는 것처럼 보인다.
 *
 * <p>받지 못하면 지어내지 않는다. {@link AiUnavailableException} 을 던져 화면이 그
 * 사실을 적게 한다.
 */
public interface NewsPort {

    /** 한 종목의 최근 뉴스와 감성 지수. */
    NewsDigest news(SecurityId security);

    /**
     * 질문 하나에 답한다.
     *
     * @param context 화면이 이미 보여 주고 있는 분석. 서버가 다시 계산하면 그새 값이
     *                바뀌어 사용자가 보고 있는 것과 다른 답을 듣는다
     */
    ChatAnswer ask(SecurityId security, String question, AiInsight context);

    /**
     * 미리 받아 둘 종목을 알려 준다.
     *
     * <p>구글 뉴스 RSS 는 최근 7일까지만 준다. 오늘 안 받으면 그날치는 영영 없다.
     * 실패해도 조용히 넘긴다 — 이것 때문에 화면이 멈출 이유가 없다.
     */
    void track(List<SecurityId> securities);
}
