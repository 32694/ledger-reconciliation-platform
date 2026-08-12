/**
 * 通知模块：消费支付成功和对账完成事件，生成管理员可查看的站内通知。
 *
 * <p>消费者先用 eventId 做数据库去重，再写入通知；因此 RabbitMQ 的 at-least-once 重投不会产生重复通知。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"messaging"})
package io.github.user32694.ledgerplatform.notifications;
