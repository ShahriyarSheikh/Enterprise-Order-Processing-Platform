CREATE SCHEMA IF NOT EXISTS restaurant;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'approval_status'
          AND n.nspname = 'restaurant'
    ) THEN
        CREATE TYPE restaurant.approval_status AS ENUM ('APPROVED', 'REJECTED');
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'outbox_status'
          AND n.nspname = 'restaurant'
    ) THEN
        CREATE TYPE restaurant.outbox_status AS ENUM ('STARTED', 'COMPLETED', 'FAILED');
    END IF;
END
$migration$;

CREATE TABLE IF NOT EXISTS restaurant.restaurants
(
    id uuid NOT NULL,
    name character varying NOT NULL,
    active boolean NOT NULL,
    CONSTRAINT restaurants_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS restaurant.order_approval
(
    id uuid NOT NULL,
    restaurant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    status restaurant.approval_status NOT NULL,
    CONSTRAINT order_approval_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS restaurant.products
(
    id uuid NOT NULL,
    name character varying NOT NULL,
    price numeric(10,2) NOT NULL,
    available boolean NOT NULL,
    CONSTRAINT products_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS restaurant.restaurant_products
(
    id uuid NOT NULL,
    restaurant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    CONSTRAINT restaurant_products_pkey PRIMARY KEY (id),
    CONSTRAINT restaurant_products_restaurant_fk FOREIGN KEY (restaurant_id)
        REFERENCES restaurant.restaurants (id) ON DELETE RESTRICT,
    CONSTRAINT restaurant_products_product_fk FOREIGN KEY (product_id)
        REFERENCES restaurant.products (id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS restaurant.order_outbox
(
    id uuid NOT NULL,
    saga_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    processed_at timestamp with time zone,
    type character varying NOT NULL,
    payload jsonb NOT NULL,
    outbox_status restaurant.outbox_status NOT NULL,
    approval_status restaurant.approval_status NOT NULL,
    version integer NOT NULL,
    CONSTRAINT order_outbox_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS restaurant_order_outbox_saga_status
    ON restaurant.order_outbox (type, approval_status);

CREATE UNIQUE INDEX IF NOT EXISTS restaurant_order_outbox_saga_id
    ON restaurant.order_outbox (type, saga_id, approval_status, outbox_status);

CREATE MATERIALIZED VIEW IF NOT EXISTS restaurant.order_restaurant_m_view AS
SELECT restaurant.id AS restaurant_id,
       restaurant.name AS restaurant_name,
       restaurant.active AS restaurant_active,
       product.id AS product_id,
       product.name AS product_name,
       product.price AS product_price,
       product.available AS product_available
FROM restaurant.restaurants restaurant
JOIN restaurant.restaurant_products restaurant_product
  ON restaurant.id = restaurant_product.restaurant_id
JOIN restaurant.products product
  ON product.id = restaurant_product.product_id
WITH DATA;

CREATE OR REPLACE FUNCTION restaurant.refresh_order_restaurant_m_view()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    REFRESH MATERIALIZED VIEW restaurant.order_restaurant_m_view;
    RETURN NULL;
END;
$function$;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_trigger
        WHERE tgname = 'refresh_order_restaurant_m_view'
          AND tgrelid = 'restaurant.restaurant_products'::regclass
    ) THEN
        EXECUTE 'CREATE TRIGGER refresh_order_restaurant_m_view
                 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
                 ON restaurant.restaurant_products FOR EACH STATEMENT
                 EXECUTE PROCEDURE restaurant.refresh_order_restaurant_m_view()';
    END IF;
END
$migration$;
