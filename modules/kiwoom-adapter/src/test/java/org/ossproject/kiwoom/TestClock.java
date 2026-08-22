package org.ossproject.kiwoom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 테스트에서 시간을 임의로 밀 수 있는 시계. */
public class TestClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    public TestClock(Instant now) {
        this(now, ZoneId.of("Asia/Seoul"));
    }

    private TestClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new TestClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
