package io.github.user32694.ledgerplatform.accounts;

import java.util.List;
import java.util.UUID;

/** 客户账户模块暴露给其他模块的稳定端口。 */
public interface AccountsApi {
    /** 创建客户账户及其对应的负债类钱包账务账户。 */
    CustomerAccountView create(String ownerName);
    /** 按业务账户 ID 查询客户账户。 */
    CustomerAccountView get(UUID accountId);
    /** 按稳定顺序查询全部模拟客户账户。 */
    List<CustomerAccountView> findAll();
    /** 根据客户钱包账务账户计算可用余额。 */
    AccountBalance balance(UUID accountId);
}
