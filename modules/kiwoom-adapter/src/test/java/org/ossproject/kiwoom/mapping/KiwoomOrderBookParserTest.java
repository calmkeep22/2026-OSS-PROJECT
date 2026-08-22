package org.ossproject.kiwoom.mapping;

import org.ossproject.kiwoom.mapping.KiwoomOrderBookParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.OrderBookLevel;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiwoomOrderBookParserTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    @Test
    @DisplayName("ka10004 응답의 1단계는 최우선 필드명을 쓴다")
    void parsesFirstLevelFromFprFields() throws Exception {
        OrderBook book = KiwoomOrderBookParser.fromRest("005930", json("""
                {
                  "bid_req_base_tm": "162000",
                  "sel_fpr_bid": "-73500", "sel_fpr_req": "180",
                  "buy_fpr_bid": "73400",  "buy_fpr_req": "310",
                  "tot_sel_req": "2040",   "tot_buy_req": "1650"
                }"""), NOW);

        OrderBookLevel first = book.level(1).orElseThrow();
        assertEquals(0, new BigDecimal("73500").compareTo(first.askPrice()));
        assertEquals(180L, first.askSize());
        assertEquals(0, new BigDecimal("73400").compareTo(first.bidPrice()));
        assertEquals(310L, first.bidSize());
    }

    @Test
    @DisplayName("2단계부터 10단계까지 차선 필드명을 읽는다")
    void parsesRemainingLevels() throws Exception {
        StringBuilder body = new StringBuilder("{\"sel_fpr_bid\":\"73500\",\"sel_fpr_req\":\"100\",")
                .append("\"buy_fpr_bid\":\"73400\",\"buy_fpr_req\":\"100\"");
        for (int level = 2; level <= 10; level++) {
            body.append(",\"sel_").append(level).append("th_pre_bid\":\"").append(73500 + (level - 1) * 100).append('"')
                .append(",\"sel_").append(level).append("th_pre_req\":\"").append(level * 100).append('"')
                .append(",\"buy_").append(level).append("th_pre_bid\":\"").append(73400 - (level - 1) * 100).append('"')
                .append(",\"buy_").append(level).append("th_pre_req\":\"").append(level * 100).append('"');
        }
        body.append('}');

        OrderBook book = KiwoomOrderBookParser.fromRest("005930", json(body.toString()), NOW);

        assertEquals(10, book.depth());
        assertEquals(0, new BigDecimal("74400").compareTo(book.level(10).orElseThrow().askPrice()));
        assertEquals(1000L, book.level(10).orElseThrow().askSize());
        assertEquals(0, new BigDecimal("72500").compareTo(book.level(10).orElseThrow().bidPrice()));
    }

    @Test
    @DisplayName("매도 호가의 음수 부호와 천 단위 구분자를 걷어낸다")
    void normalizesSignAndSeparator() throws Exception {
        OrderBook book = KiwoomOrderBookParser.fromRest("005930", json("""
                {"sel_fpr_bid":"-1,234,500","sel_fpr_req":"1,240",
                 "buy_fpr_bid":"+1,234,000","buy_fpr_req":"890"}"""), NOW);

        OrderBookLevel first = book.level(1).orElseThrow();
        assertEquals(0, new BigDecimal("1234500").compareTo(first.askPrice()));
        assertEquals(1240L, first.askSize());
        assertEquals(0, new BigDecimal("1234000").compareTo(first.bidPrice()));
    }

    @Test
    @DisplayName("총잔량 필드를 읽는다")
    void readsTotals() throws Exception {
        OrderBook book = KiwoomOrderBookParser.fromRest("005930", json("""
                {"sel_fpr_bid":"73500","sel_fpr_req":"180",
                 "buy_fpr_bid":"73400","buy_fpr_req":"310",
                 "tot_sel_req":"20400","tot_buy_req":"16500"}"""), NOW);

        assertEquals(20400L, book.totalAskSize());
        assertEquals(16500L, book.totalBidSize());
    }

    @Test
    @DisplayName("직전 대비 증감을 부호까지 읽는다")
    void readsDelta() throws Exception {
        OrderBook book = KiwoomOrderBookParser.fromRest("005930", json("""
                {"sel_fpr_bid":"73500","sel_fpr_req":"180","sel_1th_pre_req_pre":"-40",
                 "buy_fpr_bid":"73400","buy_fpr_req":"310","buy_1th_pre_req_pre":"25"}"""), NOW);

        OrderBookLevel first = book.level(1).orElseThrow();
        assertEquals(-40L, first.askDelta());
        assertEquals(25L, first.bidDelta());
    }

    @Test
    @DisplayName("비어 있는 단계는 건너뛴다")
    void skipsEmptyLevels() throws Exception {
        OrderBook book = KiwoomOrderBookParser.fromRest("005930", json("""
                {"sel_fpr_bid":"73500","sel_fpr_req":"180",
                 "buy_fpr_bid":"73400","buy_fpr_req":"310",
                 "sel_5th_pre_bid":"0","sel_5th_pre_req":"0"}"""), NOW);

        assertEquals(1, book.depth());
    }

    @Test
    @DisplayName("실시간 0D 의 FID 규칙대로 10단계를 읽는다")
    void parsesRealtimeFids() throws Exception {
        StringBuilder values = new StringBuilder("{\"21\":\"162000\"");
        for (int level = 1; level <= 10; level++) {
            values.append(",\"").append(40 + level).append("\":\"-").append(73500 + (level - 1) * 100).append('"')
                  .append(",\"").append(50 + level).append("\":\"").append(73400 - (level - 1) * 100).append('"')
                  .append(",\"").append(60 + level).append("\":\"").append(level * 10).append('"')
                  .append(",\"").append(70 + level).append("\":\"").append(level * 20).append('"');
        }
        values.append(",\"121\":\"550\",\"125\":\"1100\"}");

        OrderBook book = KiwoomOrderBookParser.fromRealtime("005930", json(values.toString()), NOW);

        assertEquals(10, book.depth());
        assertEquals(0, new BigDecimal("73500").compareTo(book.level(1).orElseThrow().askPrice()));
        assertEquals(0, new BigDecimal("74400").compareTo(book.level(10).orElseThrow().askPrice()));
        assertEquals(100L, book.level(10).orElseThrow().askSize());
        assertEquals(200L, book.level(10).orElseThrow().bidSize());
        assertEquals(550L, book.totalAskSize());
        assertEquals(1100L, book.totalBidSize());
    }

    @Test
    @DisplayName("실시간 직전 대비 FID(81~100)를 읽는다")
    void parsesRealtimeDelta() throws Exception {
        OrderBook book = KiwoomOrderBookParser.fromRealtime("005930", json("""
                {"41":"-73500","61":"180","81":"-40",
                 "51":"73400","71":"310","91":"25"}"""), NOW);

        OrderBookLevel first = book.level(1).orElseThrow();
        assertEquals(-40L, first.askDelta());
        assertEquals(25L, first.bidDelta());
    }

    @Test
    @DisplayName("일부 단계만 오는 응답도 그대로 다룬다")
    void handlesPartialDepth() throws Exception {
        OrderBook book = KiwoomOrderBookParser.fromRealtime("005930", json("""
                {"41":"73500","61":"180","51":"73400","71":"310",
                 "42":"73600","62":"620","52":"73300","72":"890"}"""), NOW);

        assertEquals(2, book.depth());
        assertTrue(book.spread().isPresent());
    }

    @Test
    @DisplayName("값이 없는 응답도 예외 없이 빈 호가창이 된다")
    void handlesEmptyPayload() throws Exception {
        OrderBook book = KiwoomOrderBookParser.fromRest("005930", json("{}"), NOW);

        assertTrue(book.isEmpty());
        assertEquals(0L, book.totalAskSize());
    }
}
