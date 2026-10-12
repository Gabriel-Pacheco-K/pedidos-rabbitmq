CREATE TABLE payments (
    order_id    UUID           PRIMARY KEY,
    customer_id VARCHAR(100)   NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    status      VARCHAR(10)    NOT NULL CHECK (status IN ('APPROVED', 'DECLINED')),
    reason      VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
