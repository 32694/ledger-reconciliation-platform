/**
 * 消息模块：实现 Transactional Outbox 到 RabbitMQ 的可靠投递。
 *
 * <p>业务事务先把事件和业务事实写入同一个 PostgreSQL 事务；后台 publisher 再投递到 RabbitMQ，
 * 使用 publisher confirm、重试和 DLQ 处理短暂或永久失败。该模块只负责事件通知，不负责调度对账作业。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package io.github.user32694.ledgerplatform.messaging;
