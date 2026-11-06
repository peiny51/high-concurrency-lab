-- Run manually in the local demo database. Existing products are not updated.
INSERT INTO products (name, stock)
SELECT demo.name, 0
FROM (
    SELECT 'Avalanche Demo Keyboard' AS name
    UNION ALL SELECT 'Avalanche Demo Mouse'
    UNION ALL SELECT 'Avalanche Demo Monitor'
    UNION ALL SELECT 'Avalanche Demo Headphones'
    UNION ALL SELECT 'Avalanche Demo Webcam'
) AS demo
WHERE NOT EXISTS (
    SELECT 1 FROM products AS existing WHERE existing.name = demo.name
);

SELECT id, name, stock
FROM products
WHERE name LIKE 'Avalanche Demo %'
ORDER BY id;
