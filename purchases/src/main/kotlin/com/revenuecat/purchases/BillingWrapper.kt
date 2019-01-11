//  Purchases
//
//  Copyright © 2019 RevenueCat, Inc. All rights reserved.
//

package com.revenuecat.purchases

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.support.annotation.VisibleForTesting
import android.support.annotation.VisibleForTesting.NONE
import android.util.Log

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams

import java.util.ArrayList
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

internal class BillingWrapper internal constructor(
    private val clientFactory: ClientFactory,
    private val mainHandler: Handler
) : PurchasesUpdatedListener, BillingClientStateListener {

    internal var billingClient: BillingClient? = null
    internal var purchasesUpdatedListener: PurchasesUpdatedListener? = null

    private var clientConnected: Boolean = false
    private val serviceRequests = ConcurrentLinkedQueue<Runnable>()

    internal class ClientFactory(private val context: Context) {
        fun buildClient(listener: com.android.billingclient.api.PurchasesUpdatedListener): BillingClient {
            return BillingClient.newBuilder(context).setListener(listener).build()
        }
    }

    interface PurchasesUpdatedListener {
        fun onPurchasesUpdated(purchases: List<Purchase>)
        fun onPurchasesFailedToUpdate(purchases: List<Purchase>?, @BillingClient.BillingResponse responseCode: Int, message: String)
    }

    internal fun setListener(purchasesUpdatedListener: PurchasesUpdatedListener?) {
        this.purchasesUpdatedListener = purchasesUpdatedListener
        if (purchasesUpdatedListener != null) {
            if (billingClient == null) {
                billingClient = clientFactory.buildClient(this)
            }
            Log.d("Purchases", "starting connection for " + billingClient!!.toString())
            billingClient!!.startConnection(this)
        } else {
            billingClient?.takeIf { it.isReady }?.let {
                Log.d("Purchases", "ending connection for " + billingClient!!.toString())
                it.endConnection()
            }
            billingClient = null
            clientConnected = false
        }
    }

    private fun executePendingRequests() {
        while (clientConnected && !serviceRequests.isEmpty()) {
            val request = serviceRequests.remove()
            request.run()
        }
    }

    private fun executeRequest(request: Runnable) {
        if (purchasesUpdatedListener != null) {
            serviceRequests.add(request)
            if (!clientConnected) {
                if (billingClient == null) {
                    billingClient = clientFactory.buildClient(this)
                }
                Log.d("Purchases", "starting connection for " + billingClient!!.toString())
                billingClient!!.startConnection(this)
            } else {
                executePendingRequests()
            }
        } else {
            Log.e(
                "Purchases",
                "There is no listener set. Skipping. " + "Make sure you set a listener before calling anything else."
            )
        }
    }

    private fun executeRequestOnUIThread(request: Runnable) {
        executeRequest(Runnable { mainHandler.post(request) })
    }

    fun querySkuDetailsAsync(
        @BillingClient.SkuType itemType: String,
        skuList: List<String>,
        onReceiveSkuDetails: (List<SkuDetails>) -> Unit
    ) {
        executeRequest(Runnable {
            val params = SkuDetailsParams.newBuilder()
                .setType(itemType).setSkusList(skuList).build()
            billingClient!!.querySkuDetailsAsync(params) { _, skuDetailsList ->
                onReceiveSkuDetails(skuDetailsList?: emptyList())
            }
        })
    }

    fun makePurchaseAsync(
        activity: Activity,
        appUserID: String,
        sku: String,
        oldSkus: ArrayList<String>,
        @BillingClient.SkuType skuType: String
    ) {

        executeRequestOnUIThread(Runnable {
            val builder = BillingFlowParams.newBuilder()
                .setSku(sku)
                .setType(skuType)
                .setAccountId(appUserID)

            if (oldSkus.size > 0) {
                builder.setOldSkus(oldSkus)
            }

            val params = builder.build()

            @BillingClient.BillingResponse val response =
                billingClient!!.launchBillingFlow(activity, params)
            if (response != BillingClient.BillingResponse.OK) {
                Log.e("Purchases", "Failed to launch billing intent $response")
            }
        })
    }

    fun queryPurchaseHistoryAsync(
        @BillingClient.SkuType skuType: String,
        onReceivePurchaseHistory: (List<Purchase>) -> Unit,
        onReceivePurchaseHistoryError: (PurchasesError) -> Unit
    ) {
        executeRequest(Runnable {
            billingClient!!.queryPurchaseHistoryAsync(skuType) { responseCode, purchasesList ->
                if (responseCode == BillingClient.BillingResponse.OK) {
                    onReceivePurchaseHistory(purchasesList)
                } else {
                    onReceivePurchaseHistoryError(
                        PurchasesError(
                            Purchases.ErrorDomains.PLAY_BILLING,
                            responseCode,
                            "Error receiving purchase history"
                        )
                    )
                }
            }
        })
    }

    fun consumePurchase(token: String) =
        executeRequest(Runnable { billingClient!!.consumeAsync(token) { _, _ -> } })

    override fun onPurchasesUpdated(responseCode: Int, purchases: List<Purchase>?) {
        if (responseCode == BillingClient.BillingResponse.OK && purchases != null) {
            purchasesUpdatedListener!!.onPurchasesUpdated(purchases)
        } else {
            purchasesUpdatedListener!!.onPurchasesFailedToUpdate(
                purchases,
                if (purchases == null && responseCode == BillingClient.BillingResponse.OK)
                    BillingClient.BillingResponse.ERROR
                else
                    responseCode,
                "Error updating purchases $responseCode"
            )
        }
    }

    override fun onBillingSetupFinished(responseCode: Int) {
        if (responseCode == BillingClient.BillingResponse.OK) {
            Log.d("Purchases", "Billing Service Setup finished for " + billingClient!!.toString())
            clientConnected = true
            executePendingRequests()
        } else {
            Log.w("Purchases", "Billing Service Setup finished with error code: $responseCode")
        }
    }

    override fun onBillingServiceDisconnected() {
        clientConnected = false
        Log.w("Purchases", "Billing Service disconnected for " + billingClient!!.toString())
    }
}
