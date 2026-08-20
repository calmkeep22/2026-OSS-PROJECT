package org.ossproject.kiwoom.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.Trade;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderBook;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiwoomWebSocketProtocolTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-19T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final KiwoomWebSocketProtocol protocol =
            new KiwoomWebSocketProtocol(new ObjectMapper(), CLOCK);

    /** 사건이 하나만 나오는 메시지를 간결하게 검증하기 위한 도우미. */
    private KiwoomStreamEvent first(String message) {
        return protocol.decode(message).get(0);
    }

    @Test
    @DisplayName("로그인 패킷에 토큰을 담는다")
    void buildsLoginPacket() {
        String message = protocol.loginMessage("abc.def.ghi");

        assertTrue(message.contains("\"trnm\":\"LOGIN\""));
        assertTrue(message.contains("\"token\":\"abc.def.ghi\""));
    }

    @Test
    @DisplayName("등록 패킷은 종목과 실시간 종류를 배열로 담는다")
    void buildsRegisterPacket() {
        String message = protocol.registerMessage(
                List.of("005930", "000660"),
                List.of(KiwoomRealtimeType.ORDER_BOOK, KiwoomRealtimeType.TRADE),
                true);

        assertTrue(message.contains("\"trnm\":\"REG\""));
        assertTrue(message.contains("\"item\":[\"005930\",\"000660\"]"));
        assertTrue(message.contains("\"type\":[\"0D\",\"0B\"]"));
        assertTrue(message.contains("\"refresh\":\"1\""));
    }

    @Test
    @DisplayName("구독 복원 시에는 기존 등록을 대체한다")
    void replacesRegistrationsOnRestore() {
        String message = protocol.registerMessage(
                List.of("005930"), List.of(KiwoomRealtimeType.ORDER_BOOK), false);

        assertTrue(message.contains("\"refresh\":\"0\""));
    }

    @Test
    @DisplayName("해제 패킷에는 refresh 를 넣지 않는다")
    void buildsRemovePacket() {
        String message = protocol.removeMessage(
                List.of("005930"), List.of(KiwoomRealtimeType.ORDER_BOOK));

        assertTrue(message.contains("\"trnm\":\"REMOVE\""));
        assertFalse(message.contains("refresh"));
    }

    @Test
    @DisplayName("종목이나 종류가 비면 패킷을 만들지 않는다")
    void rejectsEmptyRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> protocol.registerMessage(List.of(), List.of(KiwoomRealtimeType.ORDER_BOOK), true));
        assertThrows(IllegalArgumentException.class,
                () -> protocol.registerMessage(List.of("005930"), List.of(), true));
    }

    @Test
    @DisplayName("PING 은 받은 패킷을 그대로 되돌려보내야 한다")
    void echoesPing() {
        String raw = "{\"trnm\":\"PING\",\"seq\":\"12\"}";

        KiwoomStreamEvent event = first(raw);

        KiwoomStreamEvent.Ping ping = assertInstanceOf(KiwoomStreamEvent.Ping.class, event);
        assertEquals(raw, ping.echo());
    }

    @Test
    @DisplayName("순수 문자열 PING 도 처리한다")
    void echoesPlainTextPing() {
        assertInstanceOf(KiwoomStreamEvent.Ping.class, first("PING"));
    }

    @Test
    @DisplayName("로그인 성공 응답을 읽는다")
    void decodesLoginSuccess() {
        KiwoomStreamEvent event = first(
                "{\"trnm\":\"LOGIN\",\"return_code\":0,\"return_msg\":\"정상\"}");

        KiwoomStreamEvent.LoginResult result =
                assertInstanceOf(KiwoomStreamEvent.LoginResult.class, event);
        assertTrue(result.success());
    }

    @Test
    @DisplayName("로그인 실패 응답의 사유를 담는다")
    void decodesLoginFailure() {
        KiwoomStreamEvent event = first(
                "{\"trnm\":\"LOGIN\",\"return_code\":3,\"return_msg\":\"토큰이 유효하지 않습니다\"}");

        KiwoomStreamEvent.LoginResult result =
                assertInstanceOf(KiwoomStreamEvent.LoginResult.class, event);
        assertFalse(result.success());
        assertEquals(3, result.returnCode());
        assertTrue(result.message().contains("토큰"));
    }

    @Test
    @DisplayName("REAL 메시지의 0D 를 호가창으로 옮긴다")
    void decodesOrderBookReal() {
        KiwoomStreamEvent event = first("""
                {"trnm":"REAL","data":[{
                  "type":"0D","name":"주식호가잔량","item":"005930",
                  "values":{"41":"-73500","61":"180","51":"73400","71":"310",
                            "42":"-73600","62":"620","52":"73300","72":"890"}
                }]}""");

        KiwoomStreamEvent.OrderBookUpdate update =
                assertInstanceOf(KiwoomStreamEvent.OrderBookUpdate.class, event);
        OrderBook book = update.orderBook();
        assertEquals("005930", book.symbol());
        assertEquals(2, book.depth());
        assertEquals(0, new BigDecimal("73500").compareTo(book.bestAsk().orElseThrow()));
        assertEquals(0, new BigDecimal("73400").compareTo(book.bestBid().orElseThrow()));
    }

    @Test
    @DisplayName("한 메시지에 체결과 호가가 같이 오면 둘 다 사건으로 만든다")
    void decodesEveryEntryInOneMessage() {
        List<KiwoomStreamEvent> events = protocol.decode("""
                {"trnm":"REAL","data":[
                  {"type":"0B","name":"주식체결","item":"005930","values":{"10":"73500","13":"1000"}},
                  {"type":"0D","name":"주식호가잔량","item":"005930",
                   "values":{"41":"73500","61":"180","51":"73400","71":"310"}}
                ]}""");

        assertEquals(2, events.size());
        assertInstanceOf(KiwoomStreamEvent.QuoteUpdate.class, events.get(0));
        assertInstanceOf(KiwoomStreamEvent.OrderBookUpdate.class, events.get(1));
    }

    @Test
    @DisplayName("처리 대상이 아닌 메시지는 무시하되 예외를 던지지 않는다")
    void ignoresUnknownMessages() {
        assertInstanceOf(KiwoomStreamEvent.Ignored.class,
                first("{\"trnm\":\"REG\",\"return_code\":0}"));
        assertInstanceOf(KiwoomStreamEvent.Ignored.class,
                first("{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0B\",\"item\":\"005930\"}]}"));
        assertInstanceOf(KiwoomStreamEvent.Ignored.class, first("깨진 JSON {{{"));
        assertInstanceOf(KiwoomStreamEvent.Ignored.class, first(""));
        assertInstanceOf(KiwoomStreamEvent.Ignored.class, first(null));
    }

    // ------------------------------------------------------------------
    // 체결 건별
    // ------------------------------------------------------------------

    /** FID 15 는 수량인데 부호가 방향이다. 절대값을 취하면 방향이 사라진다. */
    @Test
    @DisplayName("체결량이 양수면 매수 체결로 읽는다")
    void readsAPositiveTradeVolumeAsABuy() {
        Trade trade = onlyTrade("{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0B\",\"item\":\"005930\","
                + "\"values\":{\"10\":\"+73500\",\"15\":\"+20\",\"20\":\"143215\",\"13\":\"1000\"}}]}");

        assertEquals(OrderSide.BUY, trade.side());
        assertEquals(20L, trade.quantity());
        assertEquals(0, new java.math.BigDecimal("73500").compareTo(trade.price()));
    }

    @Test
    @DisplayName("체결량이 음수면 매도 체결로 읽는다")
    void readsANegativeTradeVolumeAsASell() {
        Trade trade = onlyTrade("{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0B\",\"item\":\"005930\","
                + "\"values\":{\"10\":\"-73500\",\"15\":\"-20\",\"20\":\"143215\",\"13\":\"1000\"}}]}");

        assertEquals(OrderSide.SELL, trade.side());
        assertEquals(20L, trade.quantity(), "수량 자체는 절대값입니다");
    }

    /** 같은 메시지에서 현재가 갱신과 체결이 함께 나와야 한다. 쓰는 화면이 다르다. */
    @Test
    @DisplayName("주식체결 하나에서 시세와 체결을 모두 만든다")
    void producesBothAQuoteAndATradeFromOneMessage() {
        List<KiwoomStreamEvent> events = protocol.decode(
                "{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0B\",\"item\":\"005930\","
                + "\"values\":{\"10\":\"+73500\",\"15\":\"+20\",\"20\":\"143215\",\"13\":\"1000\"}}]}");

        assertTrue(events.stream().anyMatch(e -> e instanceof KiwoomStreamEvent.QuoteUpdate));
        assertTrue(events.stream().anyMatch(e -> e instanceof KiwoomStreamEvent.TradeUpdate));
    }

    /** 체결량이 없으면 체결이 아니다. 시세 갱신만 남는다. */
    @Test
    @DisplayName("체결량이 없으면 체결로 만들지 않는다")
    void makesNoTradeWithoutAVolume() {
        List<KiwoomStreamEvent> events = protocol.decode(
                "{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0B\",\"item\":\"005930\","
                + "\"values\":{\"10\":\"+73500\",\"13\":\"1000\"}}]}");

        assertTrue(events.stream().noneMatch(e -> e instanceof KiwoomStreamEvent.TradeUpdate));
    }

    private Trade onlyTrade(String message) {
        return protocol.decode(message).stream()
                .filter(KiwoomStreamEvent.TradeUpdate.class::isInstance)
                .map(KiwoomStreamEvent.TradeUpdate.class::cast)
                .map(KiwoomStreamEvent.TradeUpdate::trade)
                .findFirst()
                .orElseThrow(() -> new AssertionError("체결 사건이 없습니다"));
    }
}
