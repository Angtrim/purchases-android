//  Purchases
//
//  Copyright © 2019 RevenueCat, Inc. All rights reserved.
//

package com.revenuecat.purchases

import android.util.Log
import com.android.billingclient.api.Purchase
import java.lang.Exception

var shouldShowDebugLogs = false

fun debugLog(message: String) {
    if (shouldShowDebugLogs) {
        Log.d("[Purchases] - DEBUG", message)
    }
}

fun log(message: String) {
    Log.w("[Purchases] - INFO", message)
}

fun errorLog(message: String) {
    Log.e("[Purchases] - ERROR", message)
}

fun Purchase.toHumanReadableDescription() = "${this.sku} ${this.orderId} ${this.purchaseToken}"