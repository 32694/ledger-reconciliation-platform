package io.github.user32694.ledgerplatform.messaging.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.messaging.EventType;
import io.github.user32694.ledgerplatform.messaging.RabbitTopology;
import java.time.Clock;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MessagingProperties.class)
class RabbitMessagingConfiguration {
    @Bean
    TopicExchange eventExchange() {
        return new TopicExchange(RabbitTopology.EVENT_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange deadLetterExchange() {
        return new TopicExchange(RabbitTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue notificationQueue() {
        return QueueBuilder.durable(RabbitTopology.NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitTopology.DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "notification.dead.v1")
                .build();
    }

    @Bean
    Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(RabbitTopology.NOTIFICATION_DLQ).build();
    }

    @Bean
    Binding paymentSucceededBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("eventExchange") TopicExchange eventExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(eventExchange)
                .with(EventType.PAYMENT_SUCCEEDED.routingKey());
    }

    @Bean
    Binding reconciliationCompletedBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("eventExchange") TopicExchange eventExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(eventExchange)
                .with(EventType.RECONCILIATION_COMPLETED.routingKey());
    }

    @Bean
    Binding deadLetterBinding(
            @Qualifier("notificationDeadLetterQueue") Queue notificationDeadLetterQueue,
            @Qualifier("deadLetterExchange") TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(notificationDeadLetterQueue)
                .to(deadLetterExchange)
                .with("notification.dead.v1");
    }

    @Bean
    Jackson2JsonMessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    RabbitTemplateCustomizer rabbitTemplateCustomizer(Jackson2JsonMessageConverter converter) {
        return template -> {
            template.setMessageConverter(converter);
            template.setMandatory(true);
        };
    }

    @Bean
    SimpleRabbitListenerContainerFactory notificationListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        RetryOperationsInterceptor retryAdvice = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1_000, 2.0, 2_000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();

        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryAdvice);
        return factory;
    }

    @Bean
    Clock messagingClock() {
        return Clock.systemUTC();
    }
}
