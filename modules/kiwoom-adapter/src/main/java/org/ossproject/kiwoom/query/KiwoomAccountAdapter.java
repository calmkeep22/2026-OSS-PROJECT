package org.ossproject.kiwoom.query;

import org.ossproject.kiwoom.config.KiwoomRestClient;
import org.ossproject.kiwoom.mapping.KiwoomTr;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.application.port.AccountPort;
import org.ossproject.broker.error.BrokerException;
import org.ossproject.finance.model.Account;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 키움 계좌 조회를 애플리케이션 포트에 연결한다.
 *
 * <p>계좌번호는 접근 토큰에 딸려 있으므로 사용자가 입력하지 않는다. 처음 한 번 조회해
 * 캐시한다. 토큰이 바뀌면 계좌도 바뀔 수 있으나, 한 실행 안에서는 같은 토큰을 쓴다.
 *
 * <p>예수금과 보유 종목은 서로 다른 TR 이라 두 번 호출한다. 호출 간격은 전송 계층이
 * 맞춘다.
 */
public final class KiwoomAccountAdapter implements AccountPort {

    private final KiwoomRestClient client;
    private final AtomicReference<String> cachedAccountNo = new AtomicReference<>();

    public KiwoomAccountAdapter(KiwoomRestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Account getAccount() {
        return client.fetchAccount(accountNo());
    }

    /** 접근 토큰에 연결된 계좌번호. */
    public String accountNo() {
        String cached = cachedAccountNo.get();
        if (cached != null) {
            return cached;
        }
        JsonNode root = client.callRaw("계좌번호 조회", KiwoomTr.ACCOUNT_NUMBERS, "{}");
        JsonNode node = root.get("acctNo");
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new BrokerException("계좌번호 응답에 acctNo 항목이 없습니다.");
        }
        cachedAccountNo.compareAndSet(null, node.asText().trim());
        return cachedAccountNo.get();
    }
}
