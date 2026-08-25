package org.ossproject.ai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.ai.MarketIndex;
import org.ossproject.ai.MarketOverviewPort;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 지수와 환율을 HTTP 로 받아 온다.
 *
 * <p>값이나 기준일이 빠진 항목은 <b>버린다.</b> 반쪽짜리를 화면에 올리면 사용자는
 * 무엇이 빠졌는지 알 수 없다. 목록에서 아예 없으면 화면이 "받지 못했습니다" 를
 * 적는다 — 그것이 알 수 있는 상태다.
 */
public final class HttpMarketOverviewAdapter implements MarketOverviewPort {

    private final AiServiceHttp service;

    public HttpMarketOverviewAdapter(URI baseUri) {
        this(baseUri, AiServiceHttp.defaultClient(), new ObjectMapper());
    }

    HttpMarketOverviewAdapter(URI baseUri, HttpClient http, ObjectMapper json) {
        this.service = new AiServiceHttp(baseUri, http, json);
    }

    @Override
    public List<MarketIndex> overview() {
        JsonNode root = service.getMarket("/market/overview");
        List<MarketIndex> indices = new ArrayList<>();
        for (JsonNode node : root.path("indices")) {
            MarketIndex index = toIndex(node);
            if (index != null) {
                indices.add(index);
            }
        }
        return List.copyOf(indices);
    }

    /** 온전한 항목만 값으로 만든다. 못 만들면 null 이고, 부르는 쪽이 건너뛴다. */
    private static MarketIndex toIndex(JsonNode node) {
        String code = AiServiceHttp.text(node, "code");
        BigDecimal value = AiServiceHttp.decimal(node, "value");
        LocalDate asOf = AiServiceHttp.date(node, "as_of");
        if (code.isBlank() || value == null || asOf == null) {
            return null;
        }
        return new MarketIndex(code,
                AiServiceHttp.text(node, "name", code),
                AiServiceHttp.text(node, "group"),
                value,
                Optional.ofNullable(AiServiceHttp.decimal(node, "change_percent")),
                asOf);
    }
}
