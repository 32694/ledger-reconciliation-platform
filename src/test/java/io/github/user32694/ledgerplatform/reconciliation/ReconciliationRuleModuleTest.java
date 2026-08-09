package io.github.user32694.ledgerplatform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ReconciliationRuleModuleTest {
    private static final UUID DEFAULT_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ALIPAY_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID WECHAT_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID UNION_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000104");

    @Autowired ReconciliationRulesApi rulesApi;
    @Autowired AuditApi auditApi;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetFixtureBeforeTest() {
        resetFixture();
    }

    @AfterEach
    void resetFixtureAfterTest() {
        resetFixture();
    }

    @Test
    void listsSeededSelectableChannelsAndPermanentlyExcludesLegacySynthetic() {
        assertThat(rulesApi.findChannels(false))
                .extracting(ReconciliationChannelView::code)
                .containsExactly("ALIPAY", "UNION_PAY", "WECHAT_PAY");
        assertThat(rulesApi.findChannels(true))
                .extracting(ReconciliationChannelView::code)
                .containsExactly("ALIPAY", "UNION_PAY", "WECHAT_PAY");

        var disabled = rulesApi.setChannelActive("ALIPAY", false, " channel-admin ");

        assertThat(disabled.active()).isFalse();
        assertThat(disabled.version()).isEqualTo(1);
        assertThat(rulesApi.findChannels(false))
                .extracting(ReconciliationChannelView::code)
                .containsExactly("UNION_PAY", "WECHAT_PAY");
        assertThat(rulesApi.findChannels(true))
                .extracting(ReconciliationChannelView::code)
                .containsExactly("ALIPAY", "UNION_PAY", "WECHAT_PAY");
        assertThat(auditApi.findRecent(
                        AuditAction.RECONCILIATION_CHANNEL_STATUS_CHANGE,
                        AuditOutcome.SUCCEEDED,
                        10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("channel-admin");
                    assertThat(event.aggregateType()).isEqualTo("RECONCILIATION_CHANNEL");
                    assertThat(event.aggregateId()).isEqualTo(disabled.id().toString());
                    assertThat(event.correlationReference()).isEqualTo("ALIPAY");
                });

        assertThatThrownBy(() -> rulesApi.resolvePublishedVersion("ALIPAY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("对账渠道未启用: ALIPAY");
    }

    @Test
    void resolvesDefaultUntilAChannelOverrideIsPublished() {
        var fallback = rulesApi.resolvePublishedVersion("ALIPAY");

        assertThat(fallback.ruleId()).isEqualTo(DEFAULT_RULE_ID);
        assertThat(fallback.sourceScope()).isEqualTo(RuleScopeType.DEFAULT);
        assertThat(fallback.amountToleranceCents()).isZero();
        assertThat(fallback.queryWindowHours()).isZero();

        var firstDraft = rulesApi.saveDraft(
                ALIPAY_RULE_ID, new ReconciliationRuleDraftCommand(25, 48, " rule-editor "));
        var updatedDraft = rulesApi.saveDraft(
                ALIPAY_RULE_ID, new ReconciliationRuleDraftCommand(30, 72, "rule-editor-2"));

        assertThat(updatedDraft.id()).isEqualTo(firstDraft.id());
        assertThat(updatedDraft.versionNumber()).isEqualTo(1);
        assertThat(updatedDraft.status()).isEqualTo(RuleVersionStatus.DRAFT);
        assertThat(updatedDraft.amountToleranceCents()).isEqualTo(30);
        assertThat(updatedDraft.queryWindowHours()).isEqualTo(72);
        assertThat(updatedDraft.createdBy()).isEqualTo("rule-editor-2");
        assertThat(rulesApi.findVersions(ALIPAY_RULE_ID))
                .singleElement()
                .isEqualTo(updatedDraft);

        var published = rulesApi.publish(ALIPAY_RULE_ID, " publisher ");

        assertThat(published.id()).isEqualTo(firstDraft.id());
        assertThat(published.status()).isEqualTo(RuleVersionStatus.PUBLISHED);
        assertThat(published.publishedBy()).isEqualTo("publisher");
        assertThat(published.publishedAt()).isNotNull();
        assertThat(rulesApi.resolvePublishedVersion("ALIPAY")).isEqualTo(published);
        assertThat(rulesApi.getRule(ALIPAY_RULE_ID).activeVersion()).isEqualTo(published);
        assertThat(rulesApi.findRules()).hasSize(4);
        assertThat(auditApi.findRecent(
                        AuditAction.RECONCILIATION_RULE_PUBLISH,
                        AuditOutcome.SUCCEEDED,
                        10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("publisher");
                    assertThat(event.aggregateType()).isEqualTo("RECONCILIATION_RULE");
                    assertThat(event.aggregateId()).isEqualTo(ALIPAY_RULE_ID.toString());
                    assertThat(event.correlationReference()).isEqualTo(published.id().toString());
                });
    }

    @Test
    void keepsPublishedVersionsImmutableAndOnlyTheLatestDraftMutable() {
        var firstDraft = rulesApi.saveDraft(
                WECHAT_RULE_ID, new ReconciliationRuleDraftCommand(10, 24, "editor-1"));
        var firstPublished = rulesApi.publish(WECHAT_RULE_ID, "publisher-1");

        var secondDraft = rulesApi.saveDraft(
                WECHAT_RULE_ID, new ReconciliationRuleDraftCommand(20, 36, "editor-2"));
        var sameSecondDraft = rulesApi.saveDraft(
                WECHAT_RULE_ID, new ReconciliationRuleDraftCommand(40, 60, "editor-3"));

        assertThat(secondDraft.id()).isEqualTo(sameSecondDraft.id());
        assertThat(secondDraft.id()).isNotEqualTo(firstDraft.id());
        assertThat(secondDraft.versionNumber()).isEqualTo(2);
        assertThat(rulesApi.findVersions(WECHAT_RULE_ID))
                .extracting(ReconciliationRuleVersionView::status)
                .containsExactly(RuleVersionStatus.DRAFT, RuleVersionStatus.PUBLISHED);
        assertThat(rulesApi.findVersions(WECHAT_RULE_ID).get(1)).isEqualTo(firstPublished);
        assertThat(rulesApi.resolvePublishedVersion("WECHAT_PAY")).isEqualTo(firstPublished);
        assertThat(auditApi.findRecent(
                        AuditAction.RECONCILIATION_RULE_DRAFT_SAVE,
                        AuditOutcome.SUCCEEDED,
                        10))
                .hasSize(3);
    }

    @Test
    void validatesDraftAndAdministrationInputs() {
        assertThatThrownBy(() -> rulesApi.saveDraft(
                        DEFAULT_RULE_ID,
                        new ReconciliationRuleDraftCommand(-1, 0, "operator")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("金额容差不能小于 0");
        assertThatThrownBy(() -> rulesApi.saveDraft(
                        DEFAULT_RULE_ID,
                        new ReconciliationRuleDraftCommand(0, -1, "operator")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("查询窗口必须在 0 到 168 小时之间");
        assertThatThrownBy(() -> rulesApi.saveDraft(
                        DEFAULT_RULE_ID,
                        new ReconciliationRuleDraftCommand(0, 169, "operator")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("查询窗口必须在 0 到 168 小时之间");
        assertThatThrownBy(() -> rulesApi.saveDraft(
                        DEFAULT_RULE_ID,
                        new ReconciliationRuleDraftCommand(0, 0, " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("操作人不能为空");
        assertThatThrownBy(() -> rulesApi.publish(DEFAULT_RULE_ID, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("操作人不能为空");
        assertThatThrownBy(() -> rulesApi.setChannelActive("ALIPAY", false, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("操作人不能为空");
    }

    @Test
    void serializesConcurrentPublishAttemptsAndReturnsOneDomainFailure() throws Exception {
        rulesApi.saveDraft(
                UNION_RULE_ID, new ReconciliationRuleDraftCommand(15, 12, "editor"));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Object> publish = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return rulesApi.publish(UNION_RULE_ID, "publisher");
            } catch (RuntimeException exception) {
                return exception;
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(publish);
            var second = executor.submit(publish);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(ReconciliationRuleVersionView.class::isInstance).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(IllegalStateException.class::isInstance)
                    .singleElement()
                    .isInstanceOfSatisfying(
                            IllegalStateException.class,
                            failure -> assertThat(failure).hasMessage("没有可发布的规则草稿"));
            assertThat(rulesApi.findVersions(UNION_RULE_ID))
                    .extracting(ReconciliationRuleVersionView::status)
                    .containsExactly(RuleVersionStatus.PUBLISHED);
        } finally {
            executor.shutdownNow();
        }
    }

    private void resetFixture() {
        jdbcTemplate.execute("DELETE FROM audit.audit_event");
        jdbcTemplate.execute(
                "TRUNCATE reconciliation.reconciliation_rule_version, "
                        + "reconciliation.reconciliation_rule, "
                        + "reconciliation.reconciliation_channel CASCADE");
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_channel
                    (id, code, display_name, active, created_at, version)
                VALUES
                    ('00000000-0000-0000-0000-000000000001', 'ALIPAY', '支付宝', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000002', 'WECHAT_PAY', '微信支付', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000003', 'UNION_PAY', '银联', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000004', 'LEGACY_SYNTHETIC', '历史兼容渠道', false, CURRENT_TIMESTAMP, 0)
                """);
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_rule
                    (id, scope_type, channel_id, version)
                VALUES
                    ('00000000-0000-0000-0000-000000000101', 'DEFAULT', NULL, 0),
                    ('00000000-0000-0000-0000-000000000102', 'CHANNEL', '00000000-0000-0000-0000-000000000001', 0),
                    ('00000000-0000-0000-0000-000000000103', 'CHANNEL', '00000000-0000-0000-0000-000000000002', 0),
                    ('00000000-0000-0000-0000-000000000104', 'CHANNEL', '00000000-0000-0000-0000-000000000003', 0)
                """);
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_rule_version
                    (id, rule_id, version_number, status, amount_tolerance_cents,
                     query_window_hours, created_by, created_at, published_by, published_at)
                VALUES
                    ('00000000-0000-0000-0000-000000000201',
                     '00000000-0000-0000-0000-000000000101', 1, 'PUBLISHED', 0,
                     0, 'system', CURRENT_TIMESTAMP, 'system', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update(
                """
                UPDATE reconciliation.reconciliation_rule
                SET active_version_id = '00000000-0000-0000-0000-000000000201'
                WHERE id = '00000000-0000-0000-0000-000000000101'
                """);
    }
}
