package com.gabrielpacheco.pedidos.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.gabrielpacheco.pedidos")
@EntityScan("com.gabrielpacheco.pedidos")
@EnableJpaRepositories("com.gabrielpacheco.pedidos")
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
