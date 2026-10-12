package com.gabrielpacheco.pedidos.common.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gabrielpacheco.pedidos.common.inbox.Inbox;
import com.gabrielpacheco.pedidos.common.inbox.ProcessedMessageRepository;
import com.gabrielpacheco.pedidos.common.messaging.IncomingMessageReader;
import com.gabrielpacheco.pedidos.common.messaging.InvalidMessageException;
import com.gabrielpacheco.pedidos.common.messaging.MessageSigner;
import com.gabrielpacheco.pedidos.common.outbox.OutboxRelay;
import com.gabrielpacheco.pedidos.common.outbox.OutboxRepository;
import com.gabrielpacheco.pedidos.common.outbox.OutboxWriter;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);

    /** Tentativas para falhas temporárias (banco ocupado, rede) antes de mandar para a DLQ. */
    private static final int MAX_ATTEMPTS = 4;

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * ObjectMapper só para mensagens, separado do usado pela API web (por isso não é um bean,
     * que substituiria o do Spring). É estrito de propósito: campo desconhecido, número faltando
     * num campo primitivo, chave duplicada ou lixo depois do JSON fazem a mensagem ser rejeitada.
     */
    private final ObjectMapper messagingMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    @Bean
    public MessageSigner messageSigner(MessagingProperties properties) {
        return new MessageSigner(properties.self(), properties.privateKey(), properties.publicKeys());
    }

    @Bean
    public IncomingMessageReader incomingMessageReader(MessageSigner signer, Validator validator,
                                                       MessagingProperties properties, Clock clock) {
        return new IncomingMessageReader(signer, messagingMapper, validator, properties, clock);
    }

    @Bean
    public OutboxWriter outboxWriter(OutboxRepository repository, MessagingProperties properties, Clock clock) {
        return new OutboxWriter(repository, messagingMapper, properties, clock);
    }

    @Bean
    public OutboxRelay outboxRelay(OutboxRepository repository, RabbitTemplate rabbitTemplate, MessageSigner signer,
                                   MessagingProperties properties, TransactionTemplate transactionTemplate, Clock clock) {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "Mensagem {} sem fila de destino (routing key {}): verifique as bindings do broker",
                returned.getMessage().getMessageProperties().getMessageId(), returned.getRoutingKey()));
        return new OutboxRelay(repository, rabbitTemplate, signer, properties, transactionTemplate, clock);
    }

    @Bean
    public Inbox inbox(ProcessedMessageRepository repository, Clock clock) {
        return new Inbox(repository, clock);
    }

    /**
     * Consumidores com confirmação depois do processamento (AUTO: o ack só sai se o método
     * terminar sem erro). Falhas temporárias são tentadas de novo com espera crescente;
     * {@link InvalidMessageException} não é tentada de novo. Esgotadas as tentativas, a
     * mensagem é rejeitada sem voltar para a fila e o broker a envia para a DLQ.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000);

        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(10);
        factory.setMissingQueuesFatal(true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS, Map.of(InvalidMessageException.class, false), true, true))
                .backOffPolicy(backOff)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
