package org.ossproject.ai;

import java.util.List;

/**
 * 홈 화면 맨 위에 놓을 시장 지표.
 *
 * <p>{@link AiInsightPort}·{@link NewsPort} 와 나눈 이유는 같다 — 실패 범위가 다르다.
 * 지수는 공개 시세 서버에서 받으므로 예측이나 뉴스가 멀쩡해도 혼자 실패할 수 있고,
 * 그 반대도 마찬가지다.
 *
 * <p>일부만 받아도 받은 것을 준다. 코스피는 국내 서버, S&amp;P 는 해외 서버라 함께
 * 죽지 않는데 한 묶음으로 실패시키면 멀쩡한 지표까지 화면에서 사라진다.
 */
public interface MarketOverviewPort {

    /**
     * 받은 지표만 순서대로.
     *
     * <p>못 받은 지표는 목록에 없다. 0 이나 직전 값으로 채우지 않는다 — 화면을 볼 수
     * 없는 사용자는 채운 값과 받은 값을 구별할 방법이 없다.
     *
     * @return 하나도 못 받았으면 빈 목록
     * @throws AiUnavailableException 서비스 자체에 닿지 못했을 때. 지표를 못 받은 것과
     *                                서버가 없는 것은 사용자가 할 일이 다르다
     */
    List<MarketIndex> overview();

    /**
     * 아직 붙이지 않았을 때 쓰는 빈 창구.
     *
     * <p>예외를 던지지 않고 빈 목록을 준다. 화면 갤러리와 검사에서는 서버가 없는 것이
     * 정상이고, 그때 화면은 "받지 못했습니다" 를 적으면 된다 — 지어낸 숫자만 아니면
     * 된다.
     */
    static MarketOverviewPort unavailable() {
        return List::of;
    }
}
