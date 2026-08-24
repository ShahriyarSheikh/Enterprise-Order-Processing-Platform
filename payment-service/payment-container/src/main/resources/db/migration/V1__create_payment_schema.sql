CREATE SCHEMA IF NOT EXISTS payment;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'payment_status'
          AND n.nspname = 'payment'
    ) THEN
        CREATE TYPE payment.payment_status AS ENUM ('COMPLETED', 'CANCELLED', 'FAILED');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'transaction_type'
          AND n.nspname = 'payment'
    ) THEN
        CREATE TYPE payment.transaction_type AS ENUM ('DEBIT', 'CREDIT');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'outbox_status'
          AND n.nspname = 'payment'
    ) THEN
        CREATE TYPE payment.outbox_status AS ENUM ('STARTED', 'COMPLETED', 'FAILED');
    END IF;
END
$migration$;

CREATE TABLE IF NOT EXISTS payment.payments
(
    id uuid NOT NULL,
    customer_id uuid NOT NULL,
    order_id uuid NOT NULL,
    price numeric(10,2) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    status payment.payment_status NOT NULL,
    CONSTRAINT payments_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS payment.credit_entry
(
    id uuid NOT NULL,
    customer_id uuid NOT NULL,
    total_credit_amount numeric(10,2) NOT NULL,
    CONSTRAINT credit_entry_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS payment.credit_history
(
    id uuid NOT NULL,
    customer_id uuid NOT NULL,
    amount numeric(10,2) NOT NULL,
    type payment.transaction_type NOT NULL,
    CONSTRAINT credit_history_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS payment.order_outbox
(
    id uuid NOT NULL,
    saga_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    processed_at timestamp with time zone,
    type character varying NOT NULL,
    payload jsonb NOT NULL,
    outbox_status payment.outbox_status NOT NULL,
    payment_status payment.payment_status NOT NULL,
    version integer NOT NULL,
    CONSTRAINT order_outbox_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS payment_order_outbox_saga_status
    ON payment.order_outbox (type, payment_status);

CREATE UNIQUE INDEX IF NOT EXISTS payment_order_outbox_saga_id_payment_status_outbox_status
    ON payment.order_outbox (type, saga_id, payment_status, outbox_status);
