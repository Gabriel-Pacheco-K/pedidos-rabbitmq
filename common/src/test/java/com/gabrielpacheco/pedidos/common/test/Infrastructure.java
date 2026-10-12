package com.gabrielpacheco.pedidos.common.test;

import com.gabrielpacheco.pedidos.common.messaging.Producer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PostgreSQL e RabbitMQ reais, iniciados uma vez por execução de testes. O broker carrega
 * o mesmo definitions.json usado no Docker Compose, então os testes exercitam as mesmas
 * filas, bindings e permissões de produção.
 */
public final class Infrastructure {

    public static final String VHOST = "pedidos";
    public static final String ADMIN_USER = "admin";
    public static final String ADMIN_PASSWORD = "admin-dev-only";

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    public static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath(infraFile("rabbitmq.conf")), "/etc/rabbitmq/rabbitmq.conf")
            .withCopyFileToContainer(MountableFile.forHostPath(infraFile("definitions.json")), "/etc/rabbitmq/definitions.json");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    private Infrastructure() {
    }

    /** Registra conexões com o usuário do serviço testado, não com o admin: as permissões reais valem nos testes. */
    public static void register(DynamicPropertyRegistry registry, Producer self, String rabbitUser, String rabbitPassword) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> rabbitUser);
        registry.add("spring.rabbitmq.password", () -> rabbitPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> VHOST);
        registry.add("app.messaging.private-key", () -> TestKeys.privateKey(self));
        TestKeys.publicKeys().forEach((producer, key) -> registry.add("app.messaging.public-keys." + producer, () -> key));
    }

    private static Path infraFile(String name) {
        // Os testes rodam a partir da pasta do módulo; o infra/ fica na raiz do projeto.
        Path path = Path.of("..", "infra", "rabbitmq", name).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IllegalStateException("Arquivo de infraestrutura não encontrado: " + path);
        }
        return path;
    }
}
