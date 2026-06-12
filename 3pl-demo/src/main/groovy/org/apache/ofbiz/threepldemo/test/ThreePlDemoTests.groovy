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
package org.apache.ofbiz.threepldemo.test

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

class ThreePlDemoTests extends OFBizTestCase {

    /* Unique per run so re-running against the same loaded DB never trips the
     * externalId dedup on OrderHeader. Base-36 keeps it ~8 chars: OrderHeader.externalId
     * is an `id` field (max 20), so "ACME-HAPPY-${RUN_ID}" must stay within that. */
    private static final String RUN_ID = Long.toString(System.currentTimeMillis(), 36)

    ThreePlDemoTests(String name) {
        super(name)
    }

    void testAcmeLoopShipsWithTracking() {
        Map result = runLoop('W3PL_ACME_STORE', 'ACME-SHIRT-M', 2.0, 'ACME-HAPPY')
        assert ServiceUtil.isSuccess(result)
        assert result.orderId
        // received SKU started at zero, then shipped: order reaches a shipped state
        GenericValue oh = EntityQuery.use(delegator).from('OrderHeader')
                .where('orderId', result.orderId).queryOne()
        assert oh.productStoreId == 'W3PL_ACME_STORE'
        assert oh.externalId == "ACME-HAPPY-${RUN_ID}".toString()
        // shipment created + shipped, with an issuance and a stamped tracking number
        GenericValue shipment = EntityQuery.use(delegator).from('Shipment')
                .where('shipmentId', result.shipmentId).queryOne()
        assert shipment != null
        assert shipment.statusId == 'SHIPMENT_SHIPPED'
        assert EntityQuery.use(delegator).from('ItemIssuance')
                .where('orderId', result.orderId).queryCount() > 0
        GenericValue seg = EntityQuery.use(delegator).from('ShipmentRouteSegment')
                .where('shipmentId', result.shipmentId).queryFirst()
        assert seg.trackingIdNumber == result.trackingIdNumber
        // inventory that was received then issued: the received item exists, owned by the merchant
        GenericValue inv = EntityQuery.use(delegator).from('InventoryItem')
                .where('inventoryItemId', result.receivedInventoryItemId).queryOne()
        assert inv.ownerPartyId == 'W3PL_ACME'
        assert inv.facilityId == 'W3PL_WHSE'
    }

    void testTwoMerchantsAreIsolatedButShareTheWarehouse() {
        Map acme = runLoop('W3PL_ACME_STORE', 'ACME-SHIRT-M', 1.0, 'ACME-ISO')
        Map globex = runLoop('W3PL_GLOBEX_STORE', 'GLOBEX-WIDGET', 1.0, 'GLOBEX-ISO')
        assert ServiceUtil.isSuccess(acme)
        assert ServiceUtil.isSuccess(globex)

        // Order isolation: each store sees only its own order (productStoreId filter).
        List acmeOrders = EntityQuery.use(delegator).from('OrderHeader')
                .where('productStoreId', 'W3PL_ACME_STORE').queryList()*.orderId
        assert acme.orderId in acmeOrders
        assert !(globex.orderId in acmeOrders)

        // Commingled inventory: both merchants' received goods live in the ONE warehouse,
        // distinguished only by ownerPartyId.
        GenericValue acmeInv = EntityQuery.use(delegator).from('InventoryItem')
                .where('inventoryItemId', acme.receivedInventoryItemId).queryOne()
        GenericValue globexInv = EntityQuery.use(delegator).from('InventoryItem')
                .where('inventoryItemId', globex.receivedInventoryItemId).queryOne()
        assert acmeInv.facilityId == 'W3PL_WHSE'
        assert globexInv.facilityId == 'W3PL_WHSE'
        assert acmeInv.ownerPartyId == 'W3PL_ACME'
        assert globexInv.ownerPartyId == 'W3PL_GLOBEX'
    }

    // Public test methods must precede non-public helpers (codenarc).
    private Map runLoop(String storeId, String sku, BigDecimal qty, String extBase) {
        return dispatcher.runSync('runFulfillment3plDemoLoop', [
                productStoreId: storeId, externalId: "${extBase}-${RUN_ID}".toString(),
                sku: sku, quantity: qty,
                userLogin: EntityQuery.use(delegator).from('UserLogin')
                        .where('userLoginId', 'system').queryOne()])
    }

}
