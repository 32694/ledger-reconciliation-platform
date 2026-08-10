package io.github.user32694.ledgerplatform.messaging.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.messaging")
public class MessagingProperties {
    private boolean publisherEnabled = true;
    @NotNull
    private Duration publishInterval = Duration.ofSeconds(1);
    @Min(1)
    private int batchSize = 50;
    @NotNull
    private Duration confirmTimeout = Duration.ofSeconds(5);
    @NotNull
    private Duration staleLockTimeout = Duration.ofSeconds(60);

    @AssertTrue(message = "Messaging durations must be positive")
    public boolean isValidDurations() {
        return isPositive(publishInterval)
                && isPositive(confirmTimeout)
                && isPositive(staleLockTimeout);
    }

    public boolean isPublisherEnabled() {
        return publisherEnabled;
    }

    public void setPublisherEnabled(boolean publisherEnabled) {
        this.publisherEnabled = publisherEnabled;
    }

    public Duration getPublishInterval() {
        return publishInterval;
    }

    public void setPublishInterval(Duration publishInterval) {
        this.publishInterval = publishInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    public Duration getStaleLockTimeout() {
        return staleLockTimeout;
    }

    public void setStaleLockTimeout(Duration staleLockTimeout) {
        this.staleLockTimeout = staleLockTimeout;
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
