package io.github.user32694.ledgerplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ApplicationModuleTest
@ActiveProfiles("test")
@Sql(
        statements = "DELETE FROM audit.audit_event",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
        statements = "DELETE FROM audit.audit_event",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AuditModuleTest {
    @Autowired AuditApi auditApi;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void exposesExactlyTheMilestoneActionsAndOutcomes() {
        assertThat(AuditAction.values())
                .containsExactly(
                        AuditAction.ACCOUNT_CREATE,
                        AuditAction.PAYMENT_TOP_UP,
                        AuditAction.PAYMENT_TRANSFER,
                        AuditAction.PAYMENT_REFUND,
                        AuditAction.PAYMENT_REVERSAL,
                        AuditAction.RECONCILIATION_IMPORT,
                        AuditAction.RECONCILIATION_RUN,
                        AuditAction.RECONCILIATION_CASE_CLAIM,
                        AuditAction.RECONCILIATION_CASE_RELEASE,
                        AuditAction.RECONCILIATION_CASE_RESOLVE,
                        AuditAction.RECONCILIATION_RESOLVE,
                        AuditAction.RECONCILIATION_RULE_DRAFT_SAVE,
                        AuditAction.RECONCILIATION_RULE_PUBLISH,
                        AuditAction.RECONCILIATION_CHANNEL_STATUS_CHANGE);
        assertThat(AuditOutcome.values())
                .containsExactly(AuditOutcome.SUCCEEDED, AuditOutcome.FAILED);
    }

    @Test
    void recordsAndFiltersNewestBusinessEvents() {
        var older = auditApi.record(new AuditCommand(
                " operator-1 ",
                AuditAction.PAYMENT_TOP_UP,
                " PAYMENT ",
                " p-1 ",
                AuditOutcome.SUCCEEDED,
                " top up succeeded ",
                " TOPUP-1 "));
        var newer = auditApi.record(new AuditCommand(
                "operator-2",
                AuditAction.PAYMENT_TRANSFER,
                "PAYMENT",
                "p-2",
                AuditOutcome.FAILED,
                "transfer failed",
                "TRANSFER-1"));
        jdbcTemplate.update(
                "UPDATE audit.audit_event SET occurred_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-08T01:00:00Z")),
                older.id());
        jdbcTemplate.update(
                "UPDATE audit.audit_event SET occurred_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-08T02:00:00Z")),
                newer.id());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit.audit_event", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT action FROM audit.audit_event WHERE id = ?",
                        String.class,
                        older.id()))
                .isEqualTo("PAYMENT_TOP_UP");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT outcome FROM audit.audit_event WHERE id = ?",
                        String.class,
                        older.id()))
                .isEqualTo("SUCCEEDED");
        assertThat(auditApi.findRecent(null, null, 1))
                .extracting(AuditEventView::aggregateId)
                .containsExactly("p-2");
        assertThat(auditApi.findRecent(AuditAction.PAYMENT_TOP_UP, null, 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("operator-1");
                    assertThat(event.action()).isEqualTo(AuditAction.PAYMENT_TOP_UP);
                    assertThat(event.aggregateType()).isEqualTo("PAYMENT");
                    assertThat(event.aggregateId()).isEqualTo("p-1");
                    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
                    assertThat(event.summary()).isEqualTo("top up succeeded");
                    assertThat(event.correlationReference()).isEqualTo("TOPUP-1");
                    assertThat(event.occurredAt()).isNotNull();
                });
        assertThat(auditApi.findRecent(null, AuditOutcome.FAILED, 100))
                .extracting(AuditEventView::aggregateId)
                .containsExactly("p-2");
        assertThat(auditApi.findRecent(
                        AuditAction.PAYMENT_TRANSFER, AuditOutcome.FAILED, 100))
                .extracting(AuditEventView::aggregateId)
                .containsExactly("p-2");
    }

    @Test
    void preservesLegacyReconciliationResolveAuditEvents() {
        var recorded = auditApi.record(new AuditCommand(
                "legacy-operator",
                AuditAction.RECONCILIATION_RESOLVE,
                "RECONCILIATION_RESULT",
                "legacy-result",
                AuditOutcome.SUCCEEDED,
                "legacy resolution",
                "legacy-batch"));

        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RESOLVE, null, 100))
                .singleElement()
                .isEqualTo(recorded);
    }

    @Test
    void usesIdDescendingToBreakEqualTimestampTies() {
        var first = auditApi.record(command(
                AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-1", "first", null));
        var second = auditApi.record(command(
                AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-2", "second", null));
        var third = auditApi.record(command(
                AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-3", "third", null));
        Instant sameTime = Instant.parse("2026-08-08T03:00:00Z");
        jdbcTemplate.update("UPDATE audit.audit_event SET occurred_at = ?", Timestamp.from(sameTime));

        var expectedIds = java.util.stream.Stream.of(first.id(), second.id(), third.id())
                .map(UUID::toString)
                .sorted(Comparator.reverseOrder())
                .toList();

        assertThat(auditApi.findRecent(null, null, 100))
                .extracting(event -> event.id().toString())
                .containsExactlyElementsOf(expectedIds);
    }

    @Test
    @WithMockUser(username = "authenticated-operator")
    void usesAuthenticatedPrincipalWhenCallerDoesNotProvideActor() {
        var event = auditApi.record(new AuditCommand(
                " ",
                AuditAction.RECONCILIATION_RUN,
                "RECONCILIATION_BATCH",
                "batch-1",
                AuditOutcome.SUCCEEDED,
                "reconciliation completed",
                null));

        assertThat(event.actor()).isEqualTo("authenticated-operator");
    }

    @Test
    @WithMockUser(username = "anonymousUser")
    void preservesAuthenticatedPrincipalNamedAnonymousUser() {
        var nullActor = auditApi.record(new AuditCommand(
                null,
                AuditAction.ACCOUNT_CREATE,
                "ACCOUNT",
                "a-null-actor",
                AuditOutcome.SUCCEEDED,
                "account created",
                null));
        var blankActor = auditApi.record(new AuditCommand(
                " ",
                AuditAction.ACCOUNT_CREATE,
                "ACCOUNT",
                "a-blank-actor",
                AuditOutcome.SUCCEEDED,
                "account created",
                null));

        assertThat(java.util.List.of(nullActor, blankActor))
                .extracting(AuditEventView::actor)
                .containsExactly("anonymousUser", "anonymousUser");
    }

    @Test
    void usesSystemActorWhenNoActorOrPrincipalExists() {
        var event = auditApi.record(new AuditCommand(
                null,
                AuditAction.ACCOUNT_CREATE,
                "ACCOUNT",
                "a-1",
                AuditOutcome.SUCCEEDED,
                "account created",
                null));

        assertThat(event.actor()).isEqualTo("SYSTEM");
    }

    @Test
    void doesNotUseAnonymousPrincipalAsActor() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new AnonymousAuthenticationToken(
                "test-key",
                "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        SecurityContextHolder.setContext(context);
        try {
            var event = auditApi.record(new AuditCommand(
                    null,
                    AuditAction.ACCOUNT_CREATE,
                    "ACCOUNT",
                    "a-1",
                    AuditOutcome.SUCCEEDED,
                    "account created",
                    null));

            assertThat(event.actor()).isEqualTo("SYSTEM");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void measuresTextLimitsInUnicodeCodePoints() {
        String emoji = "\uD83D\uDE00";
        var event = auditApi.record(new AuditCommand(
                emoji.repeat(128),
                AuditAction.ACCOUNT_CREATE,
                emoji.repeat(64),
                emoji.repeat(128),
                AuditOutcome.SUCCEEDED,
                emoji.repeat(500),
                emoji.repeat(128)));

        assertThat(event.actor()).hasSize(256);
        assertThat(event.aggregateType()).hasSize(128);
        assertThat(event.aggregateId()).hasSize(256);
        assertThat(event.summary()).hasSize(1000);
        assertThat(event.correlationReference()).hasSize(256);
    }

    @Test
    void rejectsMissingBlankAndOversizedValues() {
        assertThatThrownBy(() -> auditApi.record(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertInvalid(command(null, "ACCOUNT", "a-1", "account created", null));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, " ", "a-1", "account created", null));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "A".repeat(65), "a-1", "account created", null));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "ACCOUNT", " ", "account created", null));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a".repeat(129), "account created", null));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-1", " ", null));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-1", "s".repeat(501), null));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-1", "account created", " "));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-1", "account created", "c".repeat(129)));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "AC\nCOUNT", "a-1", "account created", null));
        assertInvalid(command(AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-1", "account created", "bad\tref"));
        assertInvalid(new AuditCommand(
                "a".repeat(129),
                AuditAction.ACCOUNT_CREATE,
                "ACCOUNT",
                "a-1",
                AuditOutcome.SUCCEEDED,
                "account created",
                null));
        assertInvalid(new AuditCommand(
                "operator",
                AuditAction.ACCOUNT_CREATE,
                "ACCOUNT",
                "a-1",
                null,
                "account created",
                null));
        assertThatThrownBy(() -> auditApi.findRecent(null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> auditApi.findRecent(null, null, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void joinsAnExistingTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            auditApi.record(new AuditCommand(
                    "operator",
                    AuditAction.ACCOUNT_CREATE,
                    "ACCOUNT",
                    "rolled-back",
                    AuditOutcome.SUCCEEDED,
                    "account created",
                    null));
            status.setRollbackOnly();
        });

        assertThat(auditApi.findRecent(null, null, 100)).isEmpty();
    }

    private AuditCommand command(
            AuditAction action,
            String aggregateType,
            String aggregateId,
            String summary,
            String correlationReference) {
        return new AuditCommand(
                "operator",
                action,
                aggregateType,
                aggregateId,
                AuditOutcome.SUCCEEDED,
                summary,
                correlationReference);
    }

    private void assertInvalid(AuditCommand command) {
        assertThatThrownBy(() -> auditApi.record(command))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
