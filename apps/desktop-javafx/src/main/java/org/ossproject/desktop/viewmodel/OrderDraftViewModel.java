package org.ossproject.desktop.viewmodel;

import org.ossproject.desktop.navigation.OrderDraft;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderType;
import org.ossproject.finance.model.Position;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 주문 초안을 들고 있고, 비율 수량과 예상 금액을 셈한다.
 *
 * <p>지금까지는 화면이 초안을 직접 들고 고쳤다. 그러면 "10퍼센트 눌렀을 때 몇 주인가"
 * 같은 셈이 화면 안에 갇혀 검사할 수 없다. 주문은 되돌릴 수 없는 동작이라 그 셈이
 * 틀리면 사용자가 의도하지 않은 수량으로 주문한다.
 *
 * <p>수량을 못 구하면 0 을 돌려주지 않고 이유를 함께 돌려준다. 0 주 주문을 만들어
 * 검증에서 걸리게 하는 것보다, 왜 못 하는지 그 자리에서 말하는 편이 낫다.
 */
public final class OrderDraftViewModel {

    /** 한 번에 낼 수 있는 최대 수량. 화면 스피너와 같은 한계를 쓴다. */
    private static final long MAX_QUANTITY = 1_000_000L;

    /**
     * 비율 단추가 내놓은 수량.
     *
     * <p>{@code reason} 은 수량을 못 구했을 때만 채워진다. 화면은 그것을 그대로 적는다.
     */
    public record Suggestion(int quantity, String reason) {
        public boolean available() {
            return quantity > 0;
        }

        static Suggestion of(long quantity) {
            return new Suggestion((int) Math.min(MAX_QUANTITY, Math.max(1L, quantity)), "");
        }

        static Suggestion unavailable(String reason) {
            return new Suggestion(0, reason);
        }
    }

    /** 시장가일 때 쓸 현재가. 조회가 늦거나 실패하면 비어 있다. */
    private final Supplier<Optional<BigDecimal>> currentPrice;

    private OrderDraft draft;
    private Account account;

    public OrderDraftViewModel(OrderDraft initial, Supplier<Optional<BigDecimal>> currentPrice) {
        this.draft = Objects.requireNonNull(initial, "initial");
        this.currentPrice = Objects.requireNonNull(currentPrice, "currentPrice");
    }

    public OrderDraft draft() {
        return draft;
    }

    /** 계좌가 조회되면 채운다. 그전에는 비율 단추를 쓸 수 없다. */
    public void setAccount(Account loaded) {
        this.account = loaded;
    }

    public boolean hasAccount() {
        return account != null;
    }

    public Optional<BigDecimal> orderableAmount() {
        return account == null ? Optional.empty()
                : Optional.of(account.deposits().orderable());
    }

    /**
     * 초안을 고친다.
     *
     * <p>값이 온전하지 않으면 초안을 바꾸지 않는다. 사용자가 가격을 지우는 도중 빈
     * 문자열이 잠깐 들어오는데, 그때 초안을 비우면 다음 글자에서 되살릴 근거가 없다.
     */
    public void update(OrderSide side, OrderType type, Integer quantity, String price) {
        if (side == null || type == null || quantity == null || quantity <= 0
                || price == null || price.isBlank()) {
            return;
        }
        draft = new OrderDraft(draft.symbol(), draft.name(), side, type, quantity,
                price.trim(), draft.origin());
    }

    /**
     * 주문 예상 금액.
     *
     * <p>가격을 못 읽으면 비어 있다. 0 원으로 적으면 사용자는 공짜로 살 수 있다고 읽는다.
     */
    public Optional<BigDecimal> estimatedAmount(String price, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            return Optional.empty();
        }
        return parse(price).map(unit -> unit.multiply(BigDecimal.valueOf(quantity)));
    }

    /**
     * 비율만큼의 수량.
     *
     * <p>매도는 보유수량을, 매수는 주문 가능 금액을 나눈다. 둘은 다른 값이라 같은 단추가
     * 방향에 따라 다른 것을 센다.
     *
     * @param percent 10, 25, 50, 100
     */
    public Suggestion quantityFor(int percent, OrderSide side, OrderType type, String price) {
        if (account == null) {
            return Suggestion.unavailable("계좌를 아직 조회하지 못했습니다.");
        }
        if (side == OrderSide.SELL) {
            long held = account.position(draft.symbol())
                    .map(Position::availableQuantity).orElse(0L);
            return held < 1 ? Suggestion.unavailable("매도 가능한 보유수량이 없습니다.")
                    : Suggestion.of(held * percent / 100L);
        }
        Optional<BigDecimal> unit = type == OrderType.MARKET ? currentPrice.get() : parse(price);
        if (unit.isEmpty() || unit.get().signum() <= 0) {
            return Suggestion.unavailable("현재 가격으로 주문할 수 있는 수량이 없습니다.");
        }
        long affordable = account.deposits().orderable()
                .divideToIntegralValue(unit.get()).longValue();
        return affordable < 1 ? Suggestion.unavailable("현재 가격으로 주문할 수 있는 수량이 없습니다.")
                : Suggestion.of(affordable * percent / 100L);
    }

    /** 사용자가 친 가격. 천 단위 쉼표는 흔히 섞여 들어온다. */
    private static Optional<BigDecimal> parse(String price) {
        if (price == null || price.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(price.replace(",", "").trim()));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }
}
