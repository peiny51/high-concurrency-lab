
package com.example.high_concurrency_lab.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Returns up to ten matching products in ID order for the avalanche fixture list.
     */
    // SELECT id, name, stock
    // FROM products
    // WHERE name LIKE 'Avalanche Demo %'
    // ORDER BY id ASC
    // LIMIT 10;
    List<Product> findTop10ByNameStartingWithOrderByIdAsc(String prefix);

    /**
     * Atomically checks stock and deducts the full quantity; returns the affected row count.
     * Must run inside the purchase service transaction.
     */
    @Modifying
    @Query(value = "UPDATE products SET stock = stock - :quantity WHERE id = :productId AND stock >= :quantity",
            nativeQuery = true)
    int decreaseStockIfAvailable(@Param("productId") Long productId,
                                 @Param("quantity") int quantity);

    /** Commits a name-only update without overwriting stock; returns zero if the ID is missing. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE products SET name = :name WHERE id = :productId", nativeQuery = true)
    int updateProductName(@Param("productId") Long productId, @Param("name") String name);
}
