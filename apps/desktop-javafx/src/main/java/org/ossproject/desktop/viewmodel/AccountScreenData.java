package org.ossproject.desktop.viewmodel;

import org.ossproject.finance.model.account.Account;
import org.ossproject.finance.model.order.Order;

import java.util.List;

/**
 * 계좌 화면이 한 번에 그리는 데 필요한 값.
 *
 * <p>계좌와 주문 내역은 서로 다른 조회에서 온다. 화면이 둘을 따로 기다리면 절반만 채워진
 * 상태가 잠깐 보이고, 그 사이에 스크린리더가 빈 값을 읽는다. 함께 모아 한 번에 넘긴다.
 */
public record AccountScreenData(Account account, List<Order> orders) {

    public AccountScreenData {
        orders = List.copyOf(orders == null ? List.of() : orders);
    }
}
