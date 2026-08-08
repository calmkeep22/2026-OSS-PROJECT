package org.ossproject.application.port;

import org.ossproject.finance.model.Order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 주문·체결 영속화 포트.
 *
 * <p>프로그램이 비정상 종료되어도 주문 이력이 남아야 하므로, 상태가 바뀔 때마다 저장한다.
 * 구현체는 주문과 그에 딸린 체결을 하나의 트랜잭션으로 저장해야 한다.
 */
public interface OrderRepository {

    /** 주문을 저장하거나 갱신한다. 딸린 체결도 함께 저장한다. */
    void save(Order order);

    Optional<Order> findById(String orderId);

    /** 최근 접수 순 전체 주문. */
    List<Order> findAll();

    /** 최근 접수 순 미체결 주문. 프로그램 재시작 시 복구에 쓴다. */
    List<Order> findOpen();

    List<Order> findBySymbol(String symbol);

    /** 보존 기간이 지난 주문을 지운다. 지워진 건수를 돌려준다. */
    int deleteCreatedBefore(Instant cutoff);
}
