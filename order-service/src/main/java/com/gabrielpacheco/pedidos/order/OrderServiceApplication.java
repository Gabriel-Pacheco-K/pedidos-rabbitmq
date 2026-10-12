package com.gabrielpacheco.pedidos.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Escaneia também o pacote common, onde ficam outbox, inbox e assinatura de mensagens. */
@SpringBootApplication(scanBasePackages = "com.gabrielpacheco.pedidos")
@EntityScan("com.gabrielpacheco.pedidos")
@EnableJpaRepositories("com.gabrielpacheco.pedidos")
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
