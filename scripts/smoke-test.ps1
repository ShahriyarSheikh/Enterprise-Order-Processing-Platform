$ErrorActionPreference = 'Stop'

$orderApi = if ($env:ORDER_API_URL) { $env:ORDER_API_URL.TrimEnd('/') } else { 'http://localhost:8181' }
$headers = @{ Accept = 'application/vnd.api.v1+json' }

$health = $null
for ($attempt = 1; $attempt -le 60; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri "$orderApi/actuator/health"
        if ($health.status -eq 'UP') {
            break
        }
    }
    catch {
        Write-Host "Waiting for order-service readiness (${attempt}/60)..."
    }
    Start-Sleep -Seconds 5
}
if (-not $health -or $health.status -ne 'UP') {
    throw 'Order service did not become healthy within 300 seconds'
}

$request = @{
    customerId = 'd215b5f8-0249-4dc5-89a3-51fd148cfb41'
    restaurantId = 'd215b5f8-0249-4dc5-89a3-51fd148cfb45'
    address = @{
        street = 'Alexanderplatz 1'
        postalCode = '10178'
        city = 'Berlin'
    }
    price = 50.00
    items = @(
        @{
            productId = 'd215b5f8-0249-4dc5-89a3-51fd148cfb48'
            quantity = 1
            price = 50.00
            subTotal = 50.00
        }
    )
} | ConvertTo-Json -Depth 5

$created = Invoke-RestMethod -Method Post -Uri "$orderApi/orders" -Headers $headers `
    -ContentType 'application/json' -Body $request
$trackingId = $created.orderTrackingId

if (-not $trackingId) {
    throw 'Create-order response did not contain orderTrackingId'
}

for ($attempt = 1; $attempt -le 24; $attempt++) {
    $tracked = Invoke-RestMethod -Uri "$orderApi/orders/$trackingId" -Headers $headers
    Write-Host "Attempt ${attempt}: order $trackingId is $($tracked.orderStatus)"

    if ($tracked.orderStatus -eq 'APPROVED') {
        Write-Host 'Smoke test passed.'
        exit 0
    }
    if ($tracked.orderStatus -eq 'CANCELLED') {
        throw "Order was cancelled: $($tracked.failureMessages -join ', ')"
    }

    Start-Sleep -Seconds 5
}

throw "Order $trackingId did not reach APPROVED within 120 seconds"
