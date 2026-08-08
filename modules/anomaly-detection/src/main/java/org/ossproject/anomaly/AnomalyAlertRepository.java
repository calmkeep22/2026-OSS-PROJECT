package org.ossproject.anomaly;

import java.time.Instant;
import java.util.List;

/**
 * 이상 감지 이력 저장소.
 *
 * <p>알림은 소리와 음성으로 한 번 스쳐 지나가기 때문에, 사용자가 나중에 "아까 뭐라고 했지"
 * 하고 다시 확인할 수 있어야 한다. 그래서 감지된 알림은 반드시 남긴다.
 */
public interface AnomalyAlertRepository {

    void save(AnomalyAlert alert);

    /** 최근 감지 순으로 돌려준다. */
    List<AnomalyAlert> findRecent(int limit);

    List<AnomalyAlert> findBySymbol(String symbol, int limit);

    /** 보존 기간이 지난 알림을 지운다. 지워진 건수를 돌려준다. */
    int deleteDetectedBefore(Instant cutoff);
}
