package org.ossproject.application.port;

import org.ossproject.finance.model.Account;

/** Supplies the account model shared by order processing and presentation. */
public interface AccountPort {
    Account getAccount();
}
