package org.ossproject.application.usecase;
import org.ossproject.finance.model.PortfolioSnapshot;
import org.ossproject.application.port.PortfolioPort;
public final class PortfolioUseCase {
    private final PortfolioPort port;
    public PortfolioUseCase(PortfolioPort port) { this.port = port; }
    public PortfolioSnapshot loadPortfolio() { return port.getPortfolio(); }
}
