CREATE TABLE customers (
    id NUMERIC(10,0) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    joined_at TIMESTAMP(0)
);
INSERT INTO customers (id, name, joined_at) VALUES (1, 'Anita', TIMESTAMP '2026-01-15 09:30:00');
