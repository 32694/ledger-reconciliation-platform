/**
 * 身份模块：提供管理员登录、密码校验和启动时的管理员初始化。
 *
 * <p>演示环境只配置 ADMIN 角色；该角色保护全部管理页面，账户密码从环境变量读取并以哈希形式保存。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package io.github.user32694.ledgerplatform.identity;
