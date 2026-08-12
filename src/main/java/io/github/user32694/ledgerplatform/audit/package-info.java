/**
 * 审计模块：以只追加方式记录管理员操作、业务引用和处理结果。
 *
 * <p>审计事件用于回答“谁在什么时候对哪条业务事实做了什么”，应用不提供修改和删除入口。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package io.github.user32694.ledgerplatform.audit;
