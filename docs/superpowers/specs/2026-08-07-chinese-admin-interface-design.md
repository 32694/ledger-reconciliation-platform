# Chinese Administration Interface Design

## Goal

Present every user-visible administration interface string in Simplified Chinese while preserving English identifiers in Java code, HTTP routes, database values, API payloads, logs, and domain exceptions.

## Scope

The change covers all current Thymeleaf administration pages:

- administrator sign-in and sign-out feedback;
- navigation, page titles, headings, actions, table headers, help text, empty states, and result counts;
- account creation and account status display;
- top-up entry, validation feedback, payment type, and payment status display;
- ledger transaction type and amount display;
- document language metadata and browser page titles.

The change does not add language switching, modify CSS layout, change business rules, alter persistence, add Flyway migrations, or translate developer-facing logs and exceptions.

## Approach

Static interface copy will be written directly in Chinese in the existing Thymeleaf templates. Dynamic English values will remain unchanged below the presentation boundary and will be converted to Chinese labels while rendering server-generated HTML.

This is intentionally smaller than introducing Spring `MessageSource` internationalization. The application currently has one interface language, so a message catalog would add indirection without providing a current capability. Browser-side JavaScript translation is excluded because it can flash English content, weakens accessibility, and is harder to test.

## Display Mappings

The current values will use these labels:

| Internal value | Chinese label |
| --- | --- |
| `ACTIVE` | 正常 |
| `FROZEN` | 已冻结 |
| `CLOSED` | 已关闭 |
| `PENDING` | 处理中 |
| `SUCCEEDED` | 成功 |
| `FAILED` | 失败 |
| `TOP_UP` | 充值 |
| `TRANSFER` | 转账 |
| `CNY` | 人民币 |

Unknown future values must remain visible as their original internal value instead of rendering an empty label.

## Validation And Errors

Web form validation annotations will return Chinese messages. Controllers that currently expose domain exception text through `BindingResult` will instead return stable Chinese field messages at the Web boundary. Domain exception messages remain English because they are developer-facing contracts and are tested independently from the interface.

The idempotency conflict will explain that the same idempotency key has already been used for a different request. Unknown account, invalid amount, and invalid owner-name errors will be attached to their existing form fields.

## Testing

`AdminWebTest` will verify that the login page and every authenticated administration page contain representative Chinese content. It will also verify Chinese validation feedback for invalid account creation, unknown top-up accounts, invalid amounts, and idempotency conflicts.

The TDD sequence is:

1. add the Chinese content and validation assertions;
2. run the focused Web test and confirm it fails because the current HTML is English;
3. apply the minimum template and Web-boundary changes;
4. run the focused test until it passes;
5. run `./mvnw clean verify`;
6. restart the packaged application and inspect login, overview, accounts, top-up, and ledger pages at desktop and mobile widths;
7. confirm the browser console has no errors and only table containers scroll horizontally on narrow screens.

## Acceptance Criteria

- No current user-facing administration label or validation message is English.
- Technical identifiers such as account numbers, business references, and idempotency keys remain unchanged.
- Internal enums, database values, routes, API values, and domain errors remain English.
- All automated tests pass.
- Desktop and mobile administration workflows remain usable without incoherent overlap or document-level horizontal scrolling.
