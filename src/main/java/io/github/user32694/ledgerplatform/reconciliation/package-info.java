/**
 * 对账模块：导入渠道账单，并将渠道记录与内部成功支付进行可恢复匹配。
 *
 * <p>Spring Batch 以分块提交和 checkpoint 支持失败恢复；导入时锁定已发布规则版本，
 * 差异结果进入异常工作台，认领、释放、解决均追加时间线和审计事件。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"payments", "audit", "messaging"})
package io.github.user32694.ledgerplatform.reconciliation;
