package org.ossproject.application.port;

import org.ossproject.finance.model.Quote;

/** 실시간 시세 수신자. */
@FunctionalInterface
public interface QuoteListener {

    void onQuote(Quote quote);
}
