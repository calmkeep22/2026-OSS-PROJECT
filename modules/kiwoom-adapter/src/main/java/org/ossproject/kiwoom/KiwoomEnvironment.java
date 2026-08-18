package org.ossproject.kiwoom;

import java.net.URI;

/**
 * 키움 접속 환경.
 *
 * <p>모의투자와 실전은 주소도 다르고 앱 키도 다르다. 둘을 섞어 쓰면 인증은 통과하는데
 * 조회가 실패하는 식으로 원인을 찾기 어려운 오류가 난다. 그래서 주소와 키를 환경 단위로
 * 묶어 한 번에 고르게 한다.
 *
 * <p>주소는 키움 공식 저장소의 {@code .env.example} 에 명시된 값이다.
 * WebSocket 이 10000 포트를 쓰는 점에 주의한다.
 */
public enum KiwoomEnvironment {

    /** 모의투자. 기본값이며 개발과 시연에 쓴다. */
    MOCK("모의투자",
            URI.create("https://mockapi.kiwoom.com"),
            URI.create("wss://mockapi.kiwoom.com:10000")),

    /** 실전. 실제 자금이 움직인다. */
    REAL("실전투자",
            URI.create("https://api.kiwoom.com"),
            URI.create("wss://api.kiwoom.com:10000"));

    private final String displayName;
    private final URI restBaseUrl;
    private final URI webSocketUrl;

    KiwoomEnvironment(String displayName, URI restBaseUrl, URI webSocketUrl) {
        this.displayName = displayName;
        this.restBaseUrl = restBaseUrl;
        this.webSocketUrl = webSocketUrl;
    }

    public String displayName() {
        return displayName;
    }

    public URI restBaseUrl() {
        return restBaseUrl;
    }

    public URI webSocketUrl() {
        return webSocketUrl;
    }

    public boolean isPaperTrading() {
        return this == MOCK;
    }

    /** 비밀 저장소에서 앱 키를 찾을 때 쓰는 별칭. 환경별로 다른 키를 보관한다. */
    public String appKeyAlias() {
        return "kiwoom-" + name().toLowerCase(java.util.Locale.ROOT) + "-appkey";
    }

    public String appSecretAlias() {
        return "kiwoom-" + name().toLowerCase(java.util.Locale.ROOT) + "-secretkey";
    }
}
