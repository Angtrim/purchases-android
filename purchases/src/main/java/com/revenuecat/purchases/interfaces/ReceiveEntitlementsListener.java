//  Purchases
//
//  Copyright © 2019 RevenueCat, Inc. All rights reserved.
//

package com.revenuecat.purchases.interfaces;

import android.support.annotation.Nullable;

import com.revenuecat.purchases.Entitlement;
import com.revenuecat.purchases.PurchasesError;

import java.util.Map;

/**
 * Used when retrieving entitlements
 */
public interface ReceiveEntitlementsListener {

    /**
     * Will be called after a successful fetch of entitlements
     * @param entitlementMap Map of entitlements keyed by name
     */
    void onReceived(@Nullable Map<String, Entitlement> entitlementMap, @Nullable PurchasesError error);

}
