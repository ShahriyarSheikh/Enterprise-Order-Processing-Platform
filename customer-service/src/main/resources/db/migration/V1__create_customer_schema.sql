CREATE SCHEMA IF NOT EXISTS customer;

CREATE TABLE IF NOT EXISTS customer.customers
(
    id uuid NOT NULL,
    username character varying NOT NULL,
    first_name character varying NOT NULL,
    last_name character varying NOT NULL,
    CONSTRAINT customers_pkey PRIMARY KEY (id)
);

CREATE MATERIALIZED VIEW IF NOT EXISTS customer.order_customer_m_view AS
SELECT id,
       username,
       first_name,
       last_name
FROM customer.customers
WITH DATA;

CREATE OR REPLACE FUNCTION customer.refresh_order_customer_m_view()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    REFRESH MATERIALIZED VIEW customer.order_customer_m_view;
    RETURN NULL;
END;
$function$;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_trigger
        WHERE tgname = 'refresh_order_customer_m_view'
          AND tgrelid = 'customer.customers'::regclass
    ) THEN
        EXECUTE 'CREATE TRIGGER refresh_order_customer_m_view
                 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
                 ON customer.customers FOR EACH STATEMENT
                 EXECUTE PROCEDURE customer.refresh_order_customer_m_view()';
    END IF;
END
$migration$;
