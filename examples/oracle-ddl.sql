CREATE TABLE customers (
    id NUMBER(10,0) PRIMARY KEY,
    name VARCHAR2(100 CHAR) NOT NULL,
    joined_at DATE
);

CREATE TABLE orders (
    id NUMBER(10,0) PRIMARY KEY,
    customer_id NUMBER(10,0) NOT NULL,
    amount NUMBER(12,2) NOT NULL,
    CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES customers(id)
);
