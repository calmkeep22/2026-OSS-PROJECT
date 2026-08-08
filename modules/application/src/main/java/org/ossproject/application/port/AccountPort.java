package org.ossproject.application.port;

import org.ossproject.finance.model.Account;

/**
 * 계좌 조회 포트.
 *
 * <p>기존 {@link PortfolioPort} 는 예수금과 보유 종목만 돌려주는 화면용 스냅샷이다.
 * 이 포트는 주문 대기 금액·매도 대기 수량까지 포함한 계좌 전체 상태를 준다.
 */
public interface AccountPort {

    Account getAccount();
}
