CREATE SCHEMA IF NOT EXISTS "order";

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'order_status'
          AND n.nspname = 'order'
    ) THEN
        CREATE TYPE "order".order_status AS ENUM ('PENDING', 'PAID', 'APPROVED', 'CANCELLED', 'CANCELLING');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'saga_status'
          AND n.nspname = 'order'
    ) THEN
        CREATE TYPE "order".saga_status AS ENUM
            ('STARTED', 'FAILED', 'SUCCEEDED', 'PROCESSING', 'COMPENSATING', 'COMPENSATED');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'outbox_status'
          AND n.nspname = 'order'
    ) THEN
        CREATE TYPE "order".outbox_status AS ENUM ('STARTED', 'COMPLETED', 'FAILED');
    END IF;
END
$migration$;

CREATE TABLE IF NOT EXISTS "order".orders
(
    id uuid NOT NULL,
    customer_id uuid NOT NULL,
    restaurant_id uuid NOT NULL,
    tracking_id uuid NOT NULL,
    price numeric(10,2) NOT NULL,
    order_status "order".order_status NOT NULL,
    failure_messages character varying,
    CONSTRAINT orders_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS "order".order_items
(
    id bigint NOT NULL,
    order_id uuid NOT NULL,
    product_id uuid NOT NULL,
    price numeric(10,2) NOT NULL,
    quantity integer NOT NULL,
    sub_total numeric(10,2) NOT NULL,
    CONSTRAINT order_items_pkey PRIMARY KEY (id, order_id),
    CONSTRAINT order_items_order_fk FOREIGN KEY (order_id)
        REFERENCES "order".orders (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "order".order_address
(
    id uuid NOT NULL,
    order_id uuid UNIQUE NOT NULL,
    street character varying NOT NULL,
    postal_code character varying NOT NULL,
    city character varying NOT NULL,
    CONSTRAINT order_address_pkey PRIMARY KEY (id, order_id),
    CONSTRAINT order_address_order_fk FOREIGN KEY (order_id)
        REFERENCES "order".orders (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "order".payment_outbox
(
    id uuid NOT NULL,
    saga_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    processed_at timestamp with time zone,
    type character varying NOT NULL,
    payload jsonb NOT NULL,
    outbox_status "order".outbox_status NOT NULL,
    saga_status "order".saga_status NOT NULL,
    order_status "order".order_status NOT NULL,
    version integer NOT NULL,
    CONSTRAINT payment_outbox_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS payment_outbox_saga_status
    ON "order".payment_outbox (type, outbox_status, saga_status);

CREATE TABLE IF NOT EXISTS "order".restaurant_approval_outbox
(
    id uuid NOT NULL,
    saga_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    processed_at timestamp with time zone,
    type character varying NOT NULL,
    payload jsonb NOT NULL,
    outbox_status "order".outbox_status NOT NULL,
    saga_status "order".saga_status NOT NULL,
    order_status "order".order_status NOT NULL,
    version integer NOT NULL,
    CONSTRAINT restaurant_approval_outbox_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS restaurant_approval_outbox_saga_status
    ON "order".restaurant_approval_outbox (type, outbox_status, saga_status);
