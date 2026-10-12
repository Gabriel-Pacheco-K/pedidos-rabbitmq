package com.gabrielpacheco.pedidos.common.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gabrielpacheco.pedidos.common.messaging.EventType;
import com.gabrielpacheco.pedidos.common.messaging.Headers;
import com.gabrielpacheco.pedidos.common.messaging.Producer;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Cliente do broker usado pelos testes, conectado como admin. Simula os outros serviços:
 * publica eventos assinados (ou adulterados, para testar as defesas) e captura o que o
 * serviço testado publica.
 */
public class BrokerClient implements AutoCloseable {

    public static final String EXCHANGE = "pedidos.events";

    private final CachingConnectionFactory connectionFactory;
    private final RabbitTemplate template;
    private final RabbitAdmin admin;
    private final Queue capture;
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    public BrokerClient() {
        this(Infrastructure.ADMIN_USER, Infrastructure.ADMIN_PASSWORD);
    }

    public BrokerClient(String user, String password) {
        connectionFactory = new CachingConnectionFactory(Infrastructure.RABBIT.getHost(), Infrastructure.RABBIT.getAmqpPort());
        connectionFactory.setUsername(user);
        connectionFactory.setPassword(password);
        connectionFactory.setVirtualHost(Infrastructure.VHOST);
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        template = new RabbitTemplate(connectionFactory);
        admin = new RabbitAdmin(connectionFactory);
        // Fila comum (não exclusiva): sobrevive a reconexões; é apagada no close().
        capture = new Queue("test.capture." + UUID.randomUUID(), false, false, false);
        if (Infrastructure.ADMIN_USER.equals(user)) {
            admin.declareQueue(capture);
            Binding all = BindingBuilder.bind(capture).to(new TopicExchange(EXCHANGE)).with("#");
            admin.declareBinding(all);
        }
    }

    /** Publica um evento corretamente assinado pelo produtor legítimo do tipo. */
    public UUID publish(Record payload) {
        EventType type = EventType.of(payload);
        return publish(payload, type.producer(), type.producer(), UUID.randomUUID(), Instant.now(), null);
    }

    /**
     * Publica com controle total, para simular ataques: declarar um produtor e assinar
     * com a chave de outro, reutilizar um id, mandar uma mensagem antiga ou trocar o corpo.
     */
    public UUID publish(Record payload, Producer declared, Producer signedWith, UUID messageId, Instant sentAt, String overrideBody) {
        EventType type = EventType.of(payload);
        byte[] signedBody = json(payload);
        byte[] body = overrideBody == null ? signedBody : overrideBody.getBytes(StandardCharsets.UTF_8);
        long millis = sentAt.toEpochMilli();
        Message message = MessageBuilder.withBody(body)
                .setMessageId(messageId.toString())
                .setType(type.routingKey())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader(Headers.PRODUCER, declared.key())
                .setHeader(Headers.SENT_AT, Long.toString(millis))
                .setHeader(Headers.SIGNATURE, TestKeys.signer(signedWith).sign(messageId.toString(), type.routingKey(), millis, signedBody))
                .build();
        sendConfirmed(type.routingKey(), message);
        return messageId;
    }

    public void sendConfirmed(String routingKey, Message message) {
        template.invoke(ops -> {
            ops.send(EXCHANGE, routingKey, message);
            ops.waitForConfirmsOrDie(5_000);
            return null;
        });
    }

    /** Tudo o que já chegou na fila de captura e ainda não foi reivindicado por um teste. */
    private final List<Message> buffer = new ArrayList<>();

    private synchronized void drain() {
        Message message;
        while ((message = template.receive(capture.getName())) != null) {
            buffer.add(message);
        }
    }

    /** Remove do buffer e devolve os eventos do tipo pedido que satisfazem o filtro. */
    private synchronized <T extends Record> List<CapturedEvent<T>> take(EventType type, Class<T> payloadClass,
                                                                     java.util.function.Predicate<T> filter) throws Exception {
        drain();
        List<CapturedEvent<T>> found = new ArrayList<>();
        var iterator = buffer.iterator();
        while (iterator.hasNext()) {
            Message message = iterator.next();
            if (type.routingKey().equals(message.getMessageProperties().getType())) {
                T payload = mapper.readValue(message.getBody(), payloadClass);
                if (filter.test(payload)) {
                    found.add(new CapturedEvent<>(payload, message));
                    iterator.remove();
                }
            }
        }
        return found;
    }

    /** Espera até o serviço testado publicar um evento do tipo pedido que satisfaça o filtro. */
    public <T extends Record> CapturedEvent<T> awaitEvent(EventType type, Class<T> payloadClass, java.util.function.Predicate<T> filter) {
        List<CapturedEvent<T>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> {
            found.addAll(take(type, payloadClass, filter));
            return !found.isEmpty();
        });
        return found.getFirst();
    }

    /** Coleta, durante o tempo indicado, todos os eventos do tipo pedido que satisfaçam o filtro. */
    public <T extends Record> List<T> collect(EventType type, Class<T> payloadClass, java.util.function.Predicate<T> filter, Duration during) throws Exception {
        List<T> found = new ArrayList<>();
        Instant end = Instant.now().plus(during);
        do {
            take(type, payloadClass, filter).forEach(event -> found.add(event.payload()));
            Thread.sleep(50);
        } while (Instant.now().isBefore(end));
        return found;
    }

    public int messageCount(String queue) {
        return Optional.ofNullable(admin.getQueueInfo(queue)).map(info -> info.getMessageCount()).orElse(0);
    }

    public void awaitMessageCount(String queue, int expected) {
        await().atMost(Duration.ofSeconds(30)).until(() -> messageCount(queue) == expected);
    }

    public void purge(String queue) {
        admin.purgeQueue(queue, false);
    }

    private byte[] json(Record payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void close() {
        if (admin.getQueueInfo(capture.getName()) != null) {
            admin.deleteQueue(capture.getName());
        }
        connectionFactory.destroy();
    }

    public record CapturedEvent<T>(T payload, Message message) {
    }
}
