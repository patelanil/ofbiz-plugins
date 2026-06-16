/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/* ThreePlDemoServices.groovy — the 3PL demo fulfillment loop.
 * Drives stock OFBiz services as the `system` UserLogin to exercise the full
 * warehouse loop the fulfillment-api will sit on. Intake (step 2) and read-back
 * (step 7) are the API seam where fulfillment-api services later substitute. */

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ExecutionServiceException
import org.apache.ofbiz.service.ServiceUtil

Map runFulfillment3plDemoLoop() {
    List stepLog = []
    String productStoreId = parameters.productStoreId
    String externalId = parameters.externalId
    String sku = parameters.sku
    BigDecimal quantity = parameters.quantity
    String carrierPartyId = parameters.carrierPartyId ?: 'W3PL_CARRIER'
    String shipmentMethodTypeId = parameters.shipmentMethodTypeId ?: 'W3PL_GRND'

    GenericValue store = from('ProductStore').where('productStoreId', productStoreId).queryOne()
    if (store == null) {
        return error("STEP_FAILED: unknown productStoreId ${productStoreId}")
    }
    String facilityId = store.inventoryFacilityId
    String ownerPartyId = store.payToPartyId
    String currencyUom = store.defaultCurrencyUomId ?: 'USD'

    String productId = resolveSku(productStoreId, sku)
    if (productId == null) {
        return error("STEP_FAILED: SKU ${sku} not in catalog of ${productStoreId}")
    }

    // ----- Step 1: inbound receiving (stocks the zero-inventory loop SKU) -----
    BigDecimal atpBefore = atpAtFacility(productId, facilityId)
    Map recv = runAsSystem('receiveInventoryProduct', [
            productId: productId, facilityId: facilityId,
            inventoryItemTypeId: 'NON_SERIAL_INV_ITEM',
            quantityAccepted: quantity, quantityRejected: BigDecimal.ZERO,
            ownerPartyId: ownerPartyId, unitCost: BigDecimal.ZERO,
            currencyUomId: currencyUom, datetimeReceived: UtilDateTime.nowTimestamp()])
    if (ServiceUtil.isError(recv)) {
        return recv
    }
    String receivedInventoryItemId = recv.inventoryItemId
    stepLog << "1 RECEIVE: ${sku} +${quantity} at ${facilityId}"\
            + " (ATP ${atpBefore} -> ${atpAtFacility(productId, facilityId)}),"\
            + " inventoryItemId=${receivedInventoryItemId}"

    // ----- Step 2: intake (API SEAM — fulfillment-api createFulfillmentOrder wraps this) -----
    String shipGroupSeqId = '00001'
    List orderItems = []
    List shipGroupInfo = []
    shipGroupInfo << delegator.makeValue('OrderItemShipGroup', [
            shipGroupSeqId: shipGroupSeqId, facilityId: facilityId,
            carrierPartyId: carrierPartyId, carrierRoleTypeId: 'CARRIER',
            shipmentMethodTypeId: shipmentMethodTypeId, maySplit: 'N', isGift: 'N'])
    orderItems << delegator.makeValue('OrderItem', [
            orderItemSeqId: '00001', orderItemTypeId: 'PRODUCT_ORDER_ITEM',
            productId: productId, quantity: quantity, unitPrice: BigDecimal.ZERO,
            isModifiedPrice: 'Y', statusId: 'ITEM_CREATED', isPromo: 'N'])
    shipGroupInfo << delegator.makeValue('OrderItemShipGroupAssoc', [
            orderItemSeqId: '00001', shipGroupSeqId: shipGroupSeqId, quantity: quantity])

    // A 3PL fulfillment order is NOT a sale: typing it FULFILLMENT_ORDER means OFBiz never
    // fires its SALES_ORDER-gated order-to-cash (no auto-payment), and shipping on a custom
    // FULFIL_SHIPMENT type avoids the SALES_SHIPMENT invoice ECA. So no billing roles are
    // needed; partyId records the merchant as the order party.
    Map storeResult = runAsSystem('storeOrder', [
            orderTypeId: 'FULFILLMENT_ORDER',
            productStoreId: productStoreId, externalId: externalId, currencyUom: currencyUom,
            partyId: ownerPartyId,
            orderItems: orderItems, orderItemShipGroupInfo: shipGroupInfo,
            orderAdjustments: [], orderTerms: []])
    if (ServiceUtil.isError(storeResult)) {
        return storeResult
    }
    String orderId = storeResult.orderId
    Map approve = runAsSystem('changeOrderStatus',
            [orderId: orderId, statusId: 'ORDER_APPROVED', setItemStatus: 'Y'])
    if (ServiceUtil.isError(approve)) {
        return approve
    }
    stepLog << "2 INTAKE: orderId=${orderId} externalId=${externalId} -> ORDER_APPROVED"

    // ----- Step 3: reservation (explicit) -----
    // storeOrder auto-reserves only for SALES_ORDER (OrderServices.java), so for a
    // FULFILLMENT_ORDER we reserve explicitly. No double-reservation risk here.
    Map reserve = runAsSystem('reserveStoreInventory', [
            productStoreId: productStoreId, productId: productId, orderId: orderId,
            orderItemSeqId: '00001', shipGroupSeqId: shipGroupSeqId, quantity: quantity])
    if (ServiceUtil.isError(reserve)) {
        return reserve
    }
    long resCount = from('OrderItemShipGrpInvRes').where('orderId', orderId).queryCount()
    stepLog << "3 RESERVE: ${resCount} reservation row(s); ATP now ${atpAtFacility(productId, facilityId)}"

    // ----- Step 4: pick (observe the reservation) -----
    // Stock createPicklistFromOrders is SALES_ORDER-only; for a fulfillment order the
    // reservation itself is the pick instruction, so we surface it rather than build a
    // sales picklist.
    List pickLines = from('OrderItemShipGrpInvRes').where('orderId', orderId).queryList()
            .collect { "item ${it.orderItemSeqId} qty ${it.quantity} from inventoryItem ${it.inventoryItemId}" }
    stepLog << "4 PICK: ${pickLines.join('; ')}"

    // ----- Steps 5-6: pack & ship (hand-rolled on FULFIL_SHIPMENT so no SALES_SHIPMENT invoicing) -----
    // Replicates quickShipEntireOrder's mechanics minus the SALES_SHIPMENT type. Order matters:
    // create the shipment shell (INPUT) first so the carrier route segment + tracking can attach
    // while it is still editable (logged as step 5), issue each item (ItemIssuance + QOH
    // decrement), then walk status INPUT -> PACKED -> SHIPPED (logged as step 6).
    Map shipResult = runAsSystem('createShipment', [
            primaryOrderId: orderId, shipmentTypeId: 'FULFIL_SHIPMENT',
            statusId: 'SHIPMENT_INPUT', originFacilityId: facilityId])
    if (ServiceUtil.isError(shipResult)) {
        return shipResult
    }
    String shipmentId = shipResult.shipmentId

    // Step 5 — tracking: createShipmentRouteSegment is rejected once the shipment is SHIPPED,
    // and carriers assign the tracking number when the label prints (pre-ship), so we stamp it
    // here, before shipping.
    String trackingIdNumber = "1ZDEMO${orderId}"
    Map seg = runAsSystem('createShipmentRouteSegment', [
            shipmentId: shipmentId, carrierPartyId: carrierPartyId,
            shipmentMethodTypeId: shipmentMethodTypeId, originFacilityId: facilityId,
            trackingIdNumber: trackingIdNumber])
    if (ServiceUtil.isError(seg)) {
        return seg
    }
    stepLog << "5 TRACKING: segment ${seg.shipmentRouteSegmentId} trackingIdNumber=${trackingIdNumber}"

    // Issue each reserved item to the shipment. The SALES one-shot
    // (issueOrderItemShipGrpInvResToShipment) refuses non-sales orders, so per reservation we
    // do its non-sales subset: create the shipment item (issueOrderItemToShipment), record the
    // issuance (createItemIssuance), and decrement QOH (createInventoryItemDetail). We do NOT
    // decrement/remove the OrderItemShipGrpInvRes row the SALES service also touches: ATP was
    // already reduced at reservation (step 3) and the goods have now shipped, so leaving the
    // reservation keeps the correct shipped end-state (QOH and ATP both down by the issued qty).
    // A production path would release it via cancelOrderItemShipGrpInvRes; the demo leaves it as
    // a documented simplification.
    for (GenericValue res : from('OrderItemShipGrpInvRes').where('orderId', orderId).queryList()) {
        Map shipItem = runAsSystem('issueOrderItemToShipment', [
                shipmentId: shipmentId, orderId: orderId,
                orderItemSeqId: res.orderItemSeqId, shipGroupSeqId: res.shipGroupSeqId,
                quantity: res.quantity])
        if (ServiceUtil.isError(shipItem)) {
            return shipItem
        }
        // Record the issuance + decrement on-hand directly. Stock OFBiz has no complete
        // non-sales outbound issuance service (issueOrderItemToShipment leaves ItemIssuance
        // creation TODO; issueInventoryItemToShipment is for returns; the complete one is
        // SALES-only), so we do the two effects the SALES service bundles: createItemIssuance
        // (the record of goods leaving) + createInventoryItemDetail (QOH/ATP decrement).
        Map itemIssuance = runAsSystem('createItemIssuance', [
                orderId: orderId, orderItemSeqId: res.orderItemSeqId,
                shipGroupSeqId: res.shipGroupSeqId, shipmentId: shipmentId,
                shipmentItemSeqId: shipItem.shipmentItemSeqId,
                inventoryItemId: res.inventoryItemId, quantity: res.quantity])
        if (ServiceUtil.isError(itemIssuance)) {
            return itemIssuance
        }
        // Decrement on-hand for the issued units. ATP was already reduced at reservation
        // (step 3), so we do NOT reduce it again here — only QOH and accounting quantity move.
        BigDecimal issuedDelta = res.getBigDecimal('quantity').negate()
        Map invDetail = runAsSystem('createInventoryItemDetail', [
                inventoryItemId: res.inventoryItemId, itemIssuanceId: itemIssuance.itemIssuanceId,
                quantityOnHandDiff: issuedDelta, availableToPromiseDiff: BigDecimal.ZERO,
                accountingQuantityDiff: issuedDelta])
        if (ServiceUtil.isError(invDetail)) {
            return invDetail
        }
    }
    // INPUT -> PACKED -> SHIPPED is the only valid status path (direct INPUT->SHIPPED is
    // rejected). PACKED is safe here: the invoice ECA fires only for SALES_SHIPMENT.
    for (String shipStatus : ['SHIPMENT_PACKED', 'SHIPMENT_SHIPPED']) {
        Map upd = runAsSystem('updateShipment', [shipmentId: shipmentId, statusId: shipStatus])
        if (ServiceUtil.isError(upd)) {
            return upd
        }
    }
    GenericValue shipment = from('Shipment').where('shipmentId', shipmentId).queryOne()
    stepLog << "6 SHIP: shipmentId=${shipmentId} status=${shipment?.statusId}"

    // ----- Step 7: read-back (API SEAM — fulfillment-api findFulfillmentOrders/getFulfillmentOrder) -----
    GenericValue oh = from('OrderHeader').where('orderId', orderId).queryOne()
    stepLog << "7 POLL: orderId=${orderId} status=${oh.statusId}"\
            + " shipment=${shipment?.statusId} tracking=${trackingIdNumber}"
    stepLog.each { line -> logInfo("3pl-demo loop | ${line}") }

    return success([
            orderId: orderId, orderStatusId: oh.statusId, shipmentId: shipmentId,
            trackingIdNumber: trackingIdNumber, receivedInventoryItemId: receivedInventoryItemId,
            stepLog: stepLog])
}

/* Cached system UserLogin — the delegation identity for every stock call below. */
private GenericValue systemUserLogin() {
    return EntityQuery.use(delegator).from('UserLogin')
            .where('userLoginId', 'system').cache(true).queryOne()
}

/* Calls a stock service as `system`; converts a nested error/exception into a
 * demo-step error so the loop stops loudly. Returns the result map on success. */
private Map runAsSystem(String serviceName, Map ctx) {
    Map call = [:]
    call.putAll(ctx)
    call.userLogin = systemUserLogin()
    try {
        Map result = dispatcher.runSync(serviceName, call)
        if (ServiceUtil.isError(result)) {
            return error("STEP_FAILED: ${serviceName}: ${ServiceUtil.getErrorMessage(result)}")
        }
        return result
    } catch (ExecutionServiceException e) {
        return error("STEP_FAILED: ${serviceName}: ${e.getMessage()}")
    }
}

/* ATP across all of a product's inventory at a facility (for before/after logging). */
private BigDecimal atpAtFacility(String productId, String facilityId) {
    BigDecimal total = BigDecimal.ZERO
    List items = from('InventoryItem').where('productId', productId, 'facilityId', facilityId).queryList()
    items.each { item ->
        BigDecimal atp = item.getBigDecimal('availableToPromiseTotal')
        if (atp != null) {
            total = total + atp
        }
    }
    return total
}

/* Resolve a SKU to a productId within THIS store's catalog (tenant-scoped, so the two
 * merchants' catalogs never cross). Mirrors the fulfillment-api resolution, trimmed. */
private String resolveSku(String productStoreId, String sku) {
    List catalogIds = from('ProductStoreCatalog')
            .where('productStoreId', productStoreId).filterByDate().queryList()*.prodCatalogId
    if (!catalogIds) {
        return null
    }
    List categoryIds = from('ProdCatalogCategory')
            .where(EntityCondition.makeCondition('prodCatalogId', EntityOperator.IN, catalogIds))
            .filterByDate().queryList()*.productCategoryId
    if (!categoryIds) {
        return null
    }
    List storeProductIds = from('ProductCategoryMember')
            .where(EntityCondition.makeCondition('productCategoryId', EntityOperator.IN, categoryIds))
            .filterByDate().queryList()*.productId
    if (!storeProductIds) {
        return null
    }
    GenericValue gid = from('GoodIdentification')
            .where(EntityCondition.makeCondition('goodIdentificationTypeId', 'SKU'),
                   EntityCondition.makeCondition('idValue', sku),
                   EntityCondition.makeCondition('productId', EntityOperator.IN, storeProductIds))
            .queryFirst()
    return gid?.productId
}
