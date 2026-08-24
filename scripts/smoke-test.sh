#!/usr/bin/env sh
set -eu

order_api="${ORDER_API_URL:-http://localhost:8181}"

attempt=1
health=''
while [ "$attempt" -le 60 ]; do
  health="$(curl --fail --silent "$order_api/actuator/health" 2>/dev/null || true)"
  case "$health" in
    *'"status":"UP"'*) break ;;
  esac
  echo "Waiting for order-service readiness ($attempt/60)..."
  sleep 5
  attempt=$((attempt + 1))
done
case "$health" in
  *'"status":"UP"'*) ;;
  *) echo 'Order service did not become healthy within 300 seconds.' >&2; exit 1 ;;
esac

created="$(curl --fail --silent \
  -H 'Accept: application/vnd.api.v1+json' \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"d215b5f8-0249-4dc5-89a3-51fd148cfb41","restaurantId":"d215b5f8-0249-4dc5-89a3-51fd148cfb45","address":{"street":"Alexanderplatz 1","postalCode":"10178","city":"Berlin"},"price":50.00,"items":[{"productId":"d215b5f8-0249-4dc5-89a3-51fd148cfb48","quantity":1,"price":50.00,"subTotal":50.00}]}' \
  "$order_api/orders")"

tracking_id="$(printf '%s' "$created" | sed -n 's/.*"orderTrackingId":"\([^"]*\)".*/\1/p')"
if [ -z "$tracking_id" ]; then
  echo "Create-order response did not contain orderTrackingId: $created" >&2
  exit 1
fi

attempt=1
while [ "$attempt" -le 24 ]; do
  tracked="$(curl --fail --silent -H 'Accept: application/vnd.api.v1+json' "$order_api/orders/$tracking_id")"
  echo "Attempt $attempt: $tracked"
  case "$tracked" in
    *'"orderStatus":"APPROVED"'*) echo 'Smoke test passed.'; exit 0 ;;
    *'"orderStatus":"CANCELLED"'*) echo 'Order was cancelled.' >&2; exit 1 ;;
  esac
  sleep 5
  attempt=$((attempt + 1))
done

echo "Order $tracking_id did not reach APPROVED within 120 seconds" >&2
exit 1
