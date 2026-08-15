package org.ossproject.fake;

import org.ossproject.application.contract.MarketDataStreamPortContract;
import org.ossproject.application.port.MarketDataStreamPort;

class FakeMarketDataStreamContractTest extends MarketDataStreamPortContract {
    @Override
    protected MarketDataStreamPort createStream() {
        return new FakeMarketDataStreamAdapter();
    }
}
