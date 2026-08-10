package io.github.user32694.ledgerplatform.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RabbitMessagingConfigurationTest {
    @Test
    void appliesSimpleListenerAutoStartupPropertyToDedicatedFactory() {
        assertAutoStartup("test", false);
    }

    @Test
    void integrationProfileEnablesDedicatedListenerFactory() {
        assertAutoStartup("test,messaging-integration", true);
    }

    private void assertAutoStartup(String activeProfiles, boolean expected) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        RabbitAutoConfiguration.class, JacksonAutoConfiguration.class))
                .withUserConfiguration(RabbitMessagingConfiguration.class)
                .withPropertyValues("spring.profiles.active=" + activeProfiles)
                .run(context -> {
                    var factory = context.getBean(
                            "notificationListenerContainerFactory",
                            SimpleRabbitListenerContainerFactory.class);
                    var endpoint = new SimpleRabbitListenerEndpoint();
                    endpoint.setId("notification-listener-test");
                    endpoint.setQueueNames("unused-test-queue");
                    endpoint.setMessageListener((MessageListener) message -> {});

                    var container = factory.createListenerContainer(endpoint);

                    assertThat(container.isAutoStartup()).isEqualTo(expected);
                });
    }
}
