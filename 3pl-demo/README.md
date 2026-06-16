# 3pl-demo — OFBiz-as-WMS for 3PL (demo / reference environment)

A loadable OFBiz component that stands up a **two-merchant third-party-logistics
warehouse** on stock entities and runs the **full fulfillment loop** with one
reproducible service. It is the WMS substrate the `fulfillment-api` plugin sits on.

## What it models

- **Operator** `W3PL_OPS` running one warehouse `W3PL_WHSE`.
- **Two merchant clients** — ACME (`W3PL_ACME_STORE`) and Globex (`W3PL_GLOBEX_STORE`)
  — each with its own catalog/SKUs, **commingled inventory** in the shared warehouse
  (distinguished only by `InventoryItem.ownerPartyId`), and a `ProductStoreRole`
  (`FULFILL_API_CLIENT`) grant recording its access to its store. (Authentication —
  the `FULFILLMENT_API` permission and client logins — belongs to the `fulfillment-api`
  plugin; this demo runs as `system` and seeds no API logins.)
- Tenancy is entirely stock entities: merchant = PartyGroup, account = ProductStore
  (`payToPartyId` = merchant), access = `ProductStoreRole(FULFILL_API_CLIENT)`,
  isolation = `OrderHeader.productStoreId`.

## A fulfillment order is NOT a sale

The defining choice of this demo. A 3PL ships on a merchant's behalf — it does **not**
invoice anyone or take payment (the merchant bills its own end consumer, in the
merchant's own system). OFBiz keys its order-to-cash side effects on `SALES_ORDER`
(auto-payment) and `SALES_SHIPMENT` (auto-invoice), so the demo:

- types the order `FULFILLMENT_ORDER` (standalone — **not** a child of `SALES_ORDER`), and
- ships on a custom `FULFIL_SHIPMENT` type.

Result: reserve / pick / issue / ship all run, but **no invoice, no payment, and no GL
posting is ever triggered**. Because `FULFILLMENT_ORDER` falls off the sales rails, the
demo hand-rolls the issuance that `quickShipEntireOrder` would otherwise do (stock OFBiz
has no complete non-sales outbound issuance service).

## Load it (requires a loaded OFBiz DB)

```bash
# from the framework root; loads stock seed/demo once if not already present
./gradlew loadAll
# then load this demo's data on its own reader
./gradlew "ofbiz --load-data readers=seed,seed-initial,threePlDemo"
```

> Co-loading with a built `fulfillment-api` plugin needs no special steps: the demo
> seeds only the `FULFILL_API_CLIENT` role type (a benign upsert), while the plugin owns
> the `FULFILLMENT_API` permission, security group, and client logins.

## Run the loop (the demonstration)

```bash
./gradlew "ofbiz --test component=3pl-demo --test suitename=fulfillment3plDemoTests"
```

The `runFulfillment3plDemoLoop` service performs, per order, as the `system` user:

1. **Receive** inventory (`receiveInventoryProduct`) — stocks the zero-inventory SKU.
2. **Intake** (`storeOrder` as `FULFILLMENT_ORDER` + `changeOrderStatus`) → `ORDER_APPROVED`.  *(API seam)*
3. **Reserve** (`reserveStoreInventory`).
4. **Pick** — surface the reservation (stock `createPicklistFromOrders` is `SALES_ORDER`-only).
5. **Tracking** — create the `ShipmentRouteSegment` with a mock tracking number, before shipping.
6. **Ship** — create a `FULFIL_SHIPMENT`, issue each item (`createItemIssuance` +
   `createInventoryItemDetail` to record the issuance and decrement QOH), then
   `SHIPMENT_INPUT → SHIPMENT_PACKED → SHIPMENT_SHIPPED`.
7. **Poll read-back** — order / shipment / route-segment, as the API's read services would. *(API seam)*

## The API seam

Steps **2** and **7** are exactly what the fulfillment-api wraps: `storeOrder` ↔
`createFulfillmentOrder`; read-back ↔ `findFulfillmentOrders`/`getFulfillmentOrder`.
Once the API plugin is built, swap those two steps for HTTP calls
(`POST/GET /rest/fulfillment/stores/W3PL_ACME_STORE/orders`) and the warehouse loop in
between is unchanged — yielding the full merchant-API → warehouse → merchant-poll round trip.

## Tests

`ThreePlDemoTests` (suite `fulfillment3plDemoTests`):
- `testAcmeLoopShipsWithTracking` — the full loop: order ships, `ItemIssuance` created,
  QOH decremented, tracking stamped, received inventory owned by the merchant.
- `testTwoMerchantsAreIsolatedButShareTheWarehouse` — ACME and Globex both fulfill in the
  one warehouse; orders are `productStoreId`-isolated while inventory is commingled
  (distinguished only by `ownerPartyId`).

## Scope

In: the loop above + inbound receiving + two-merchant isolation. Out: facility bins,
out-of-stock case, in-loop cancel, real carrier APIs (mock tracking only), 3PL billing /
order-to-cash, returns, cycle counting. No new framework entities; no changes to stock
OFBiz services (only a `FULFILLMENT_ORDER` OrderType and `FULFIL_SHIPMENT` ShipmentType
are seeded as data).
