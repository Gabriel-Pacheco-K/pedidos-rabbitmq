package com.gabrielpacheco.pedidos.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Estoque de um produto. "available" é o que pode ser vendido; "reserved" é o que está
 * separado para pedidos aguardando pagamento. O banco também impede valores negativos.
 */
@Entity
@Table(name = "stock")
public class Stock {

    @Id
    @Column(length = 30)
    private String sku;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    protected Stock() {
    }

    public boolean canReserve(int quantity) {
        return available >= quantity;
    }

    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Estoque insuficiente para " + sku);
        }
        available -= quantity;
        reserved += quantity;
    }

    /** Pagamento aprovado: o item reservado sai de vez do estoque. */
    public void commit(int quantity) {
        reserved -= quantity;
    }

    /** Pagamento recusado: o item reservado volta a ficar disponível. */
    public void release(int quantity) {
        reserved -= quantity;
        available += quantity;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }
}
