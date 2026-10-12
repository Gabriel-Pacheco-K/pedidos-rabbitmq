package com.gabrielpacheco.pedidos.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findBySkuInAndActiveTrue(Collection<String> skus);
}
