package io.github.user32694.ledgerplatform.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 账本模块的公开端口；调用方只能提交完整 journal，不能直接改余额。 */
public interface LedgerApi {
    /** 创建平台现金资产账户；充值和退款会使用它。 */
    LedgerAccountView createPlatformCashAccount();
    /** 创建客户钱包对应的负债类账务账户。 */
    LedgerAccountView createCustomerWallet(String customerAccountNumber);
    /** 在事务中校验、锁定账户并过账不可变 journal。 */
    PostedJournal post(Journal journal);
    /** 读取客户钱包的可用余额，单位为分。 */
    long walletBalance(UUID ledgerAccountId);
    /** 查询最近已过账的 ledger transaction。 */
    List<LedgerTransactionView> findRecentTransactions(int limit);

    /** 按业务引用读取完整不可变分录，供只读对账 API 提供证据。 */
    Optional<LedgerTransactionDetailsView> findTransactionByBusinessReference(
            String businessReference);
}
