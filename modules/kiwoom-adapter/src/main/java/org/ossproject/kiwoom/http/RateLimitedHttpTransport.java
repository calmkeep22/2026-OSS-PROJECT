package org.ossproject.kiwoom.http;

import java.time.Duration;
import java.util.Objects;

/**
 * 요청 사이에 최소 간격을 두는 전송 데코레이터.
 *
 * <p>키움은 계좌·토큰별로 초당 호출 수를 제한한다. 화면을 열 때처럼 여러 조회가 한꺼번에
 * 나가면 한도에 걸리고, 실패가 쌓이면 회로 차단기까지 열려 한동안 아무 조회도 못 하게 된다.
 * 요청을 조금 벌려 보내는 편이 실패 후 재시도보다 빠르고 안전하다.
 *
 * <p>여러 스레드가 같은 전송을 공유해도 전체 호출 간격이 지켜지도록 잠금을 건다. 대기는
 * 호출 스레드에서 일어나므로 UI 스레드에서 호출하면 안 된다.
 */
public final class RateLimitedHttpTransport implements HttpTransport {

    /**
     * 기본 최소 간격.
     *
     * <p>공식 안내는 조회 TR 초당 5회지만 모의투자 서버는 더 좁게 걸린다. 여유를 두고
     * 초당 4회로 잡는다.
     */
    public static final Duration DEFAULT_MINIMUM_INTERVAL = Duration.ofMillis(250);

    private final HttpTransport delegate;
    private final long minimumIntervalNanos;
    private final Object gate = new Object();
    private long nextAllowedAtNanos;

    public RateLimitedHttpTransport(HttpTransport delegate) {
        this(delegate, DEFAULT_MINIMUM_INTERVAL);
    }

    public RateLimitedHttpTransport(HttpTransport delegate, Duration minimumInterval) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(minimumInterval, "minimumInterval");
        if (minimumInterval.isNegative()) {
            throw new IllegalArgumentException("최소 간격은 음수일 수 없습니다.");
        }
        this.minimumIntervalNanos = minimumInterval.toNanos();
    }

    @Override
    public HttpTextResponse send(HttpTextRequest request) {
        awaitTurn();
        return delegate.send(request);
    }

    /** 앞선 요청과의 간격이 찰 때까지 기다린다. */
    private void awaitTurn() {
        long waitNanos;
        synchronized (gate) {
            long now = System.nanoTime();
            // 첫 호출이거나 간격이 이미 지난 경우 바로 보낸다.
            waitNanos = Math.max(0L, nextAllowedAtNanos - now);
            nextAllowedAtNanos = Math.max(now, nextAllowedAtNanos) + minimumIntervalNanos;
        }
        if (waitNanos <= 0) {
            return;
        }
        try {
            Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new org.ossproject.broker.error.BrokerTransientException(
                    "증권사 요청 간격을 기다리는 중 작업이 중단되었습니다.", interrupted);
        }
    }
}
