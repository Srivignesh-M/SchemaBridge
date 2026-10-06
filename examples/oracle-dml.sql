INSERT INTO customers (id, name, joined_at) VALUES (1, 'Anita', TIMESTAMP '2026-01-15 09:30:00');
INSERT INTO customers (id, name, joined_at) VALUES (2, 'Sam', NULL);
INSERT INTO orders (id, customer_id, amount) VALUES (100, 1, 2500.50);
INSERT INTO orders (id, customer_id, amount) VALUES (101, 2, 125.00);
