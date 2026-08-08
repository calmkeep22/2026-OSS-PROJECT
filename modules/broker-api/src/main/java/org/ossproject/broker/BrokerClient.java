package org.ossproject.broker;

import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.Quote;

import java.util.List;

/**
 * 증권사 공통 인터페이스.
 *
 * <p>키움 외의 증권사를 붙일 때도 이 인터페이스만 구현하면 위 계층은 바뀌지 않는다.
 * 실시간 스트림은 성격이 달라 {@link org.ossproject.application.port.MarketDataStreamPort}
 * 로 분리했다.
 *
 * <p>구현체는 모든 오류를 {@link BrokerException} 계열로 변환해야 한다. 호출부가
 * 재시도 가능 여부를 {@link BrokerException#isRetryable()} 로만 판단할 수 있어야 하기 때문이다.
 */
public interface BrokerClient extends AutoCloseable {

    /** 로그와 화면에 표시할 증권사 식별자. 예: {@code kiwoom}. */
    String brokerId();

    /**
     * 액세스 토큰을 발급받는다. 이미 유효한 토큰이 있으면 아무 일도 하지 않는다.
     *
     * @throws BrokerAuthException API 키가 올바르지 않은 경우
     */
    void authenticate();

    boolean isAuthenticated();

    /** 계좌 잔고와 보유 종목을 조회한다. */
    Account fetchAccount(String accountNo);

    /** 현재가를 조회한다. */
    Quote fetchQuote(String symbol);

    /** 봉 데이터를 오래된 것부터 시간순으로 조회한다. */
    List<Candle> fetchCandles(String symbol, CandleInterval interval, int count);

    /**
     * 주문을 접수한다. 체결은 이후 실시간 스트림으로 통지된다.
     *
     * @return 증권사가 발급한 주문 번호
     */
    String placeOrder(String accountNo, OrderCommand command);

    void cancelOrder(String accountNo, String brokerOrderId);

    /** 당일 주문 내역을 조회한다. */
    List<Order> fetchOrders(String accountNo);

    @Override
    void close();
}
