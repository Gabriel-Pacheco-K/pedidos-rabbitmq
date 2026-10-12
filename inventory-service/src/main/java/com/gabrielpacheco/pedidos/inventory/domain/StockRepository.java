package com.gabrielpacheco.pedidos.inventory.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StockRepository extends JpaRepository<Stock, String> {

    /**
     * Trava as linhas (SELECT ... FOR UPDATE) sempre na mesma ordem, por SKU. Se dois pedidos
     * com os mesmos produtos travassem em ordens diferentes, um poderia esperar pelo outro
     * para sempre (deadlock). Com ordem fixa, um simplesmente espera o outro terminar.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.sku in :skus order by s.sku")
    List<Stock> lockAll(@Param("skus") Collection<String> skus);
}
