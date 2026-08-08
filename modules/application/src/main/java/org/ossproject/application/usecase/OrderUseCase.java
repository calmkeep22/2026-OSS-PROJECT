package org.ossproject.application.usecase;
import org.ossproject.finance.model.OrderPreview;
import org.ossproject.finance.model.OrderReceipt;
import org.ossproject.finance.model.OrderRequest;
import org.ossproject.application.port.OrderPort;
public final class OrderUseCase {
    private final OrderPort port;
    public OrderUseCase(OrderPort port) { this.port = port; }
    public OrderPreview preview(OrderRequest request) { return port.preview(request); }
    public OrderReceipt submitConfirmed(OrderRequest request) { return port.submit(request); }
}
