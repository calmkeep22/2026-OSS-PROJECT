package org.ossproject.application.port;
import org.ossproject.finance.model.OrderPreview;
import org.ossproject.finance.model.OrderReceipt;
import org.ossproject.finance.model.OrderRequest;
public interface OrderPort {
    OrderPreview preview(OrderRequest request);
    OrderReceipt submit(OrderRequest request);
}
