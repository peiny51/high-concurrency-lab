
package com.example.high_concurrency_lab.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Auto-increment primary key
    private Long id;

    private String name;

    private Integer stock;

    /** Creates an unsaved product; MySQL assigns its ID when it is inserted. */
    public Product(String name, Integer stock) {
        this.name = name;
        this.stock = stock;
    }
}
