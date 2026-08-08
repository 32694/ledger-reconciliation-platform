# Chinese Administration Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the complete administration interface and its form feedback in Simplified Chinese while retaining English domain, API, route, and database values.

**Architecture:** Keep static copy in the existing Thymeleaf templates. Add Chinese label fields to the existing controller-owned view records so templates never translate internal enums with browser JavaScript, and convert domain failures to stable Chinese messages only at the Web boundary.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring MVC, Thymeleaf, Jakarta Bean Validation, JUnit 5, MockMvc, PostgreSQL 17.

---

### Task 1: Lock Chinese interface behavior with failing Web tests

**Files:**
- Modify: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`

- [ ] **Step 1: Add a failing login-page translation test**

Add a test that requests `/login` anonymously and asserts the response contains `管理员登录`, `用户名`, `密码`, and `登录`.

```java
@Test
void rendersChineseLoginPage() throws Exception {
    mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("管理员登录")))
            .andExpect(content().string(containsString("用户名")))
            .andExpect(content().string(containsString("密码")))
            .andExpect(content().string(containsString("登录")));
}
```

- [ ] **Step 2: Add failing assertions for every administration page**

Extend `rendersAdministrationPages()` so each page proves representative Chinese content is server-rendered.

```java
mockMvc.perform(get("/admin"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("经营概览")))
        .andExpect(content().string(containsString("客户账户总数")));

mockMvc.perform(get("/admin/accounts"))
        .andExpect(content().string(containsString("客户账户")))
        .andExpect(content().string(containsString("可用余额")));

mockMvc.perform(get("/admin/accounts/new"))
        .andExpect(content().string(containsString("新建客户账户")))
        .andExpect(content().string(containsString("账户名称")));

mockMvc.perform(get("/admin/payments/top-up"))
        .andExpect(content().string(containsString("账户充值")))
        .andExpect(content().string(containsString("幂等键")));

mockMvc.perform(get("/admin/ledger"))
        .andExpect(content().string(containsString("账本流水")))
        .andExpect(content().string(containsString("业务流水号")));
```

- [ ] **Step 3: Add a failing dynamic-label test**

Create an account and successful top-up, then assert internal values are displayed through Chinese labels.

```java
@Test
@WithMockUser(roles = "ADMIN")
void rendersChineseBusinessLabels() throws Exception {
    var account = accountsApi.create("中文展示客户");
    paymentsApi.topUp(new TopUpCommand(
            "chinese-label-" + UUID.randomUUID(), account.id(), 12345));

    mockMvc.perform(get("/admin/accounts"))
            .andExpect(content().string(containsString("正常")))
            .andExpect(content().string(containsString("人民币")));
    mockMvc.perform(get("/admin"))
            .andExpect(content().string(containsString("充值")))
            .andExpect(content().string(containsString("成功")));
    mockMvc.perform(get("/admin/payments/top-up"))
            .andExpect(content().string(containsString("成功")));
    mockMvc.perform(get("/admin/ledger"))
            .andExpect(content().string(containsString("充值")));
}
```

- [ ] **Step 4: Add failing Chinese validation-message assertions**

Add content assertions to the existing conflict and unknown-account tests, then add blank owner and invalid amount coverage.

```java
@Test
@WithMockUser(roles = "ADMIN")
void returnsChineseAccountValidationMessage() throws Exception {
    mockMvc.perform(post("/admin/accounts")
                    .with(csrf())
                    .param("ownerName", ""))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("请输入账户名称")));
}

@Test
@WithMockUser(roles = "ADMIN")
void returnsChineseAmountValidationMessage() throws Exception {
    var account = accountsApi.create("金额校验客户");
    mockMvc.perform(post("/admin/payments/top-up")
                    .with(csrf())
                    .param("accountId", account.id().toString())
                    .param("amountCents", "0")
                    .param("idempotencyKey", "invalid-amount-" + UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("充值金额必须大于0")));
}
```

The conflict test must assert `该幂等键已被其他请求使用，请更换后重试`; the unknown-account test must assert `请选择有效的客户账户`.

- [ ] **Step 5: Run the focused test and verify RED**

Run:

```bash
./mvnw -Dtest=AdminWebTest test
```

Expected: FAIL because current templates and validation messages are English. Confirm the failure names a missing Chinese string, not a database or setup error.

### Task 2: Translate static templates and dynamic business labels

**Files:**
- Modify: `src/main/resources/templates/login.html`
- Modify: `src/main/resources/templates/admin/layout.html`
- Modify: `src/main/resources/templates/admin/overview.html`
- Modify: `src/main/resources/templates/admin/accounts.html`
- Modify: `src/main/resources/templates/admin/account-form.html`
- Modify: `src/main/resources/templates/admin/topup-form.html`
- Modify: `src/main/resources/templates/admin/ledger.html`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/OverviewController.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/accounts/web/AccountsWebController.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentsWebController.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/ledger/web/LedgerWebController.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`

- [ ] **Step 1: Translate document metadata and static interface copy**

Set every template root to `lang="zh-CN"`. Translate all visible titles, navigation, headings, buttons, labels, help text, table headers, empty states, result suffixes, and accessibility labels. Use these consistent product labels:

```text
Ledger Operations -> 交易账本管理平台
Overview -> 经营概览
Accounts -> 客户账户
Payments -> 资金操作
Ledger -> 账本流水
Reconciliation -> 自动对账
Audit Log -> 审计日志
Future -> 后续开放
Refresh -> 刷新
New account -> 新建账户
Sign out -> 退出登录
Administration -> 管理后台
```

Render money with the literal prefix `人民币 ` instead of `CNY ` while retaining the existing exact decimal value.

- [ ] **Step 2: Add controller-owned label fields with unknown-value fallback**

Extend each current view record with display fields and populate them through small private switch methods. Preserve the internal value for CSS and future diagnostics.

```java
private static String paymentStatusLabel(String status) {
    return switch (status) {
        case "PENDING" -> "处理中";
        case "SUCCEEDED" -> "成功";
        case "FAILED" -> "失败";
        default -> status;
    };
}

private static String transactionTypeLabel(String type) {
    return switch (type) {
        case "TOP_UP" -> "充值";
        case "TRANSFER" -> "转账";
        default -> type;
    };
}

private static String accountStatusLabel(String status) {
    return switch (status) {
        case "ACTIVE" -> "正常";
        case "FROZEN" -> "已冻结";
        case "CLOSED" -> "已关闭";
        default -> status;
    };
}
```

Use `statusLabel`, `typeLabel`, and `transactionTypeLabel` in the templates. Do not change `AccountView`, `PaymentView`, `LedgerTransactionView`, JPA entities, or database enums.

- [ ] **Step 3: Run the focused page and label tests**

Run:

```bash
./mvnw -Dtest=AdminWebTest test
```

Expected: static Chinese and dynamic-label assertions pass; validation assertions may still fail until Task 3.

### Task 3: Translate validation feedback at the Web boundary

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/accounts/web/AccountsWebController.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentsWebController.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`

- [ ] **Step 1: Replace Bean Validation messages with Chinese**

Use these exact messages:

```java
@NotBlank(message = "请输入账户名称")

@NotNull(message = "请选择客户账户")
@NotNull(message = "请输入充值金额")
@Positive(message = "充值金额必须大于0")
@NotBlank(message = "请输入幂等键")
@Size(max = 128, message = "幂等键不能超过128个字符")
```

- [ ] **Step 2: Stop exposing English domain exceptions in forms**

Keep existing exception classification, but pass stable Chinese presentation messages to `BindingResult`.

```java
bindingResult.rejectValue(
        "idempotencyKey",
        "topUp.idempotencyKey.conflict",
        "该幂等键已被其他请求使用，请更换后重试");
```

Use `请选择有效的客户账户`, `幂等键格式无效`, and `充值金额必须大于0` for the respective fields. Use `充值失败，请检查输入后重试` for the global fallback. In account creation, use `账户名称需为2到100个字符` for a domain validation failure.

- [ ] **Step 3: Run the complete focused Web test**

Run:

```bash
./mvnw -Dtest=AdminWebTest test
```

Expected: all `AdminWebTest` tests pass, including Chinese validation feedback.

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/resources/templates src/main/java/io/github/user32694/ledgerplatform/OverviewController.java src/main/java/io/github/user32694/ledgerplatform/accounts/web/AccountsWebController.java src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentsWebController.java src/main/java/io/github/user32694/ledgerplatform/ledger/web/LedgerWebController.java src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java
git commit -m "feat: localize administration interface in Chinese"
```

### Task 4: Verify the complete application and rendered interface

**Files:**
- Verify only; no production changes expected.

- [ ] **Step 1: Run the full build**

```bash
./mvnw clean verify
```

Expected: every test passes and Maven reports `BUILD SUCCESS`.

- [ ] **Step 2: Scan for residual user-facing English**

Inspect rendered templates and Web validation annotations. Technical values in expressions, URLs, element IDs, example account references, and internal fallback values are allowed; visible English prose is not.

```bash
rg -n ">[[:space:]]*[A-Za-z][^<]*<|message = \"[A-Za-z]" src/main/resources/templates src/main/java/io/github/user32694/ledgerplatform/accounts/web src/main/java/io/github/user32694/ledgerplatform/payments/web
```

Expected: no current visible English prose or English Bean Validation message remains.

- [ ] **Step 3: Restart the packaged JAR and verify the browser workflow**

Run the packaged application against `ledger_platform`, then inspect login, overview, accounts, account creation, top-up, and ledger pages. Verify desktop and 390px-wide mobile layouts, no document-level horizontal overflow, and no browser console errors.

- [ ] **Step 4: Verify repository state**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and no uncommitted implementation changes.

- [ ] **Step 5: Push the reviewed commits to the existing PR branch**

```bash
git -c credential.helper= -c 'credential.helper=!gh auth git-credential' -c http.version=HTTP/1.1 push origin feature/foundation-ledger-topup
```

Expected: `origin/feature/foundation-ledger-topup` points to the local HEAD and Pull Request #1 updates automatically.
