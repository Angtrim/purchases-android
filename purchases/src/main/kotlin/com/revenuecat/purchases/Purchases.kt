package com.revenuecat.purchases

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.preference.PreferenceManager
import android.util.Log

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails

import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList
import java.util.Date
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.support.annotation.VisibleForTesting
import com.revenuecat.purchases.interfaces.GetSkusResponseHandler
import com.revenuecat.purchases.interfaces.PurchaseCompletedListener
import com.revenuecat.purchases.interfaces.ReceiveEntitlementsListener
import com.revenuecat.purchases.interfaces.ReceivePurchaserInfoListener
import com.revenuecat.purchases.interfaces.UpdatedPurchaserInfoListener

const val CACHE_REFRESH_PERIOD = 60000 * 5

/**
 * Entry point for Purchases. It should be instantiated as soon as your app has a unique user id
 * for your user. This can be when a user logs in if you have accounts or on launch if you can
 * generate a random user identifier.
 * Make sure you follow the [quickstart](https://docs.revenuecat.com/docs/getting-started-1)
 * guide to setup your RevenueCat account.
 * @warning Only one instance of Purchases should be instantiated at a time!
 * Set the [Purchases.sharedInstance] to let the SDK handle the singleton management for you.
 * @property [allowSharingPlayStoreAccount] If it should allow sharing Play Store accounts. False by
 * default. If true treats all purchases as restores, aliasing together appUserIDs that share a
 * Play Store account.
 */
class Purchases @JvmOverloads internal constructor(
    _appUserID: String?,
    private val backend: Backend,
    private val billingWrapper: BillingWrapper,
    private val deviceCache: DeviceCache,
    var allowSharingPlayStoreAccount: Boolean = false,
    private var cachesLastUpdated: Date? = null
) : BillingWrapper.PurchasesUpdatedListener {

    /**
     * The passed in or generated app user ID
     */
    var appUserID: String

    private var purchaseCallbacks: MutableMap<String, PurchaseCompletedListener> = mutableMapOf()
    private var lastSentPurchaserInfo: PurchaserInfo? = null
    private var updatedPurchaserInfoListener: UpdatedPurchaserInfoListener? = null
    internal var cachedEntitlements: Map<String, Entitlement>? = null
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        set(value) { field = value }
        @VisibleForTesting(otherwise = VisibleForTesting.NONE)
        get() = field

    init {
        if (_appUserID != null) {
            this.appUserID = _appUserID
            identify(this.appUserID)
        } else {
            this.appUserID = getAnonymousID().also {
                allowSharingPlayStoreAccount = true
            }
            updateCaches()
        }

        billingWrapper.setListener(this)
    }

    /**
     * Add attribution data from a supported network
     * @param [data] JSONObject containing the data to post to the attribution network
     * @param [network] [AttributionNetwork] to post the data to
     */
    fun addAttributionData(data: JSONObject, network: AttributionNetwork) {
        backend.postAttributionData(appUserID, network, data)
    }

    /**
     * Add attribution data from a supported network
     * @param [data] Map containing the data to post to the attribution network
     * @param [network] [AttributionNetwork] to post the data to
     */
    fun addAttributionData(data: Map<String, String>, network: AttributionNetwork) {
        val jsonObject = JSONObject()
        for (key in data.keys) {
            try {
                jsonObject.put(key, data[key])
            } catch (e: JSONException) {
                Log.e("Purchases", "Failed to add key $key to attribution map")
            }
        }
        backend.postAttributionData(appUserID, network, jsonObject)
    }

    /**
     * Fetch the configured entitlements for this user. Entitlements allows you to configure your
     * in-app products via RevenueCat and greatly simplifies management.
     * See [the guide](https://docs.revenuecat.com/v1.0/docs/entitlements) for more info.
     *
     * Entitlements will be fetched and cached on instantiation so that, by the time they are needed,
     * your prices are loaded for your purchase flow. Time is money.
     *
     * @param [handler] Called when entitlements are available. Called immediately if entitlements are cached.
     */
    fun getEntitlements(handler: ReceiveEntitlementsListener? = null) {
        this.cachedEntitlements?.let {
            handler?.onReceived(it, null)
            if (!isCacheStale()) {
                updateCaches()
            }
        } ?: fetchAndCacheEntitlements(handler)
    }

    /**
     * Gets the SKUDetails for the given list of subscription skus.
     * @param [skus] List of skus
     * @param [handler] Response handler
     */
    fun getSubscriptionSkus(skus: List<String>, handler: GetSkusResponseHandler) {
        getSkus(skus, BillingClient.SkuType.SUBS, handler)
    }

    /**
     * Gets the SKUDetails for the given list of non-subscription skus.
     * @param [skus] List of skus
     * @param [handler] Response handler
     */
    fun getNonSubscriptionSkus(skus: List<String>, handler: GetSkusResponseHandler) {
        getSkus(skus, BillingClient.SkuType.INAPP, handler)
    }

    /**
     * Make a purchase.
     * @param [activity] Current activity
     * @param [sku] The sku you wish to purchase
     * @param [skuType] The type of sku, INAPP or SUBS
     * @param [oldSkus] The skus you wish to upgrade from.
     */
    @JvmOverloads
    fun makePurchase(
        activity: Activity,
        sku: String,
        @BillingClient.SkuType skuType: String,
        oldSkus: ArrayList<String> = ArrayList(),
        completion: PurchaseCompletedListener
    ) {
        if (purchaseCallbacks.containsKey(sku)) {
            completion.onCompleted(
                null,
                null,
                PurchasesError(
                    ErrorDomains.REVENUECAT_API,
                    PurchasesAPIError.DUPLICATE_MAKE_PURCHASE_CALLS.ordinal,
                    "Purchase already in progress for this product."
                )
            )
        } else {
            purchaseCallbacks[sku] = completion
            billingWrapper.makePurchaseAsync(activity, appUserID, sku, oldSkus, skuType)
        }
    }

    /**
     * Restores purchases made with the current Play Store account for the current user.
     * If you initialized Purchases with an `appUserID` any receipt tokens currently being used by
     * other users of your app will not be restored. If you used an anonymous id, i.e. you
     * initialized Purchases without an appUserID, any other anonymous users using the same
     * purchases will be merged.
     */
    fun restorePurchases(
        completion: ReceivePurchaserInfoListener
    ) {
        billingWrapper.queryPurchaseHistoryAsync(
            BillingClient.SkuType.SUBS,
            { subsPurchasesList ->
                billingWrapper.queryPurchaseHistoryAsync(
                    BillingClient.SkuType.INAPP,
                    { inAppPurchasesList ->
                        val allPurchases = ArrayList(subsPurchasesList)
                        allPurchases.addAll(inAppPurchasesList)
                        if (allPurchases.isEmpty()) {
                            getPurchaserInfo(completion)
                        } else {
                            postRestoredPurchases(allPurchases, completion)
                        }
                    },
                    { error -> completion.onReceived(null, error) })
            },
            { error -> completion.onReceived(null, error) })
    }

    /**
     * This function will alias two appUserIDs together.
     * @param [newAppUserID] The current user id will be aliased to the app user id passed in this parameter
     * @param [handler] An optional handler to listen for successes or errors.
     */
    @JvmOverloads
    fun createAlias(
        newAppUserID: String,
        handler: ReceivePurchaserInfoListener? = null
    ) {
        backend.createAlias(
            appUserID,
            newAppUserID,
            {
                identify(newAppUserID, handler)
            },
            { error ->
                handler?.onReceived(null, error)
            }
        )
    }

    /**
     * This function will change the current appUserID.
     * Typically this would be used after a log out to identify a new user without calling configure
     * @param appUserID The new appUserID that should be linked to the currently user
     */
    @JvmOverloads
    fun identify(
        appUserID: String,
        completion: ReceivePurchaserInfoListener? = null
    ) {
        clearCaches()
        this.appUserID = appUserID
        purchaseCallbacks.clear()
        updateCaches(completion)
    }

    /**
     * Resets the Purchases client clearing the save appUserID. This will generate a random user id and save it in the cache.
     */
    fun reset() {
        clearCaches()
        this.appUserID = createRandomIDAndCacheIt()
        purchaseCallbacks.clear()
        updateCaches()
    }

    /**
     * Call close when you are done with this instance of Purchases
     */
    fun close() {
        purchaseCallbacks.clear()
        this.backend.close()
        billingWrapper.setListener(null)
    }

    fun getPurchaserInfo(
        completion: ReceivePurchaserInfoListener
    ) {
        val cachedPurchaserInfo = deviceCache.getCachedPurchaserInfo(appUserID)
        if (cachedPurchaserInfo != null) {
            completion.onReceived(cachedPurchaserInfo, null)
            if (!isCacheStale()) {
                updateCaches()
            }
        } else {
            updateCaches(completion)
        }
    }

    fun setUpdatedPurchaserInfoListener(listener: UpdatedPurchaserInfoListener?) {
        this.updatedPurchaserInfoListener = listener
    }

    fun removeUpdatedPurchaserInfoListener() {
        this.updatedPurchaserInfoListener = null
    }

    // region Private Methods
    private fun fetchAndCacheEntitlements(handler: ReceiveEntitlementsListener? = null) {
        backend.getEntitlements(
            appUserID,
            { entitlements ->
                getSkuDetails(entitlements) { detailsByID ->
                    cachedEntitlements = entitlements
                    populateSkuDetailsAndCallHandler(detailsByID, entitlements, handler)
                }
            },
            { error ->
                handler?.onReceived(
                    null,
                    error
                )
            })
    }

    private fun populateSkuDetailsAndCallHandler(
        details: Map<String, SkuDetails>,
        entitlements: Map<String, Entitlement>,
        handler: ReceiveEntitlementsListener?
    ) {
        entitlements.values.flatMap { it.offerings.values }.forEach { o ->
            if (details.containsKey(o.activeProductIdentifier)) {
                o.skuDetails = details[o.activeProductIdentifier]
            } else {
                Log.e("Purchases", "Failed to find SKU for " + o.activeProductIdentifier)
            }
        }
        handler?.onReceived(entitlements, null)
    }

    private fun getSkus(
        skus: List<String>,
        @BillingClient.SkuType skuType: String,
        handler: GetSkusResponseHandler
    ) {
        billingWrapper.querySkuDetailsAsync(
            skuType,
            skus
        ) { skuDetails ->
            handler.onReceiveSkus(skuDetails)

        }
    }

    private fun updateCaches(
        completion: ReceivePurchaserInfoListener? = null
    ) {
        cachesLastUpdated = Date()
        fetchAndCachePurchaserInfo(completion)
        fetchAndCacheEntitlements()
    }

    private fun fetchAndCachePurchaserInfo(completion: ReceivePurchaserInfoListener?) {
        backend.getPurchaserInfo(
            appUserID,
            { info ->
                cachePurchaserInfo(info)
                sendUpdatedPurchaserInfoToDelegateIfChanged(info)
                completion?.onReceived(info, null)
            },
            { error ->
                Log.e("Purchases", "Error fetching subscriber data: ${error.message}")
                clearCaches()
                completion?.onReceived(null, error)
            })
    }

    private fun isCacheStale() =
        cachesLastUpdated == null || Date().time - cachesLastUpdated!!.time > CACHE_REFRESH_PERIOD

    private fun clearCaches() {
        deviceCache.clearCachedPurchaserInfo(appUserID)
        deviceCache.clearCachedAppUserID()
        cachesLastUpdated = null
        cachedEntitlements = null
    }

    private fun cachePurchaserInfo(info: PurchaserInfo) {
        deviceCache.cachePurchaserInfo(appUserID, info)
    }

    private fun postPurchases(
        purchases: List<Purchase>,
        allowSharingPlayStoreAccount: Boolean,
        onSuccess: (Purchase, PurchaserInfo) -> Unit,
        onError: (Purchase, PurchasesError) -> Unit
    ) {
        purchases.forEach { purchase ->
            backend.postReceiptData(
                purchase.purchaseToken,
                appUserID,
                purchase.sku,
                allowSharingPlayStoreAccount,
                { info ->
                    billingWrapper.consumePurchase(purchase.purchaseToken)
                    cachePurchaserInfo(info)
                    sendUpdatedPurchaserInfoToDelegateIfChanged(info)
                    onSuccess(purchase, info)
                }, { error ->
                    if (error.code < 500) {
                        billingWrapper.consumePurchase(purchase.purchaseToken)
                    }
                    onError(purchase, error)
                })
        }
    }

    private fun postRestoredPurchases(
        purchases: List<Purchase>,
        onCompletion: ReceivePurchaserInfoListener
    ) {
        purchases.sortedBy { it.purchaseTime }.let { sortedByTime ->
            postPurchases(
                sortedByTime,
                true,
                { purchase, info ->
                    if (sortedByTime.last() == purchase) {
                        onCompletion.onReceived(info, null)
                    }
                },
                { purchase, error ->
                    if (sortedByTime.last() == purchase) {
                        onCompletion.onReceived(null, error)
                    }
                })
        }
    }

    private fun getAnonymousID(): String {
        return deviceCache.getCachedAppUserID() ?: createRandomIDAndCacheIt()
    }

    private fun createRandomIDAndCacheIt(): String {
        return UUID.randomUUID().toString().also {
            deviceCache.cacheAppUserID(it)
        }
    }

    private fun getSkuDetails(
        entitlements: Map<String, Entitlement>,
        onCompleted: (HashMap<String, SkuDetails>) -> Unit
    ) {
        val skus =
            entitlements.values.flatMap { it.offerings.values }.map { it.activeProductIdentifier }

        billingWrapper.querySkuDetailsAsync(
            BillingClient.SkuType.SUBS,
            skus
        ) { subscriptionsSKUDetails ->
            val detailsByID = HashMap<String, SkuDetails>()
            val inAPPSkus =
                skus - subscriptionsSKUDetails
                    .map { details -> details.sku to details }
                    .also { skuToDetails -> detailsByID.putAll(skuToDetails) }
                    .map { skuToDetails -> skuToDetails.first }

            if (inAPPSkus.isNotEmpty()) {
                billingWrapper.querySkuDetailsAsync(
                    BillingClient.SkuType.INAPP,
                    inAPPSkus
                ) { skuDetails ->
                    detailsByID.putAll(skuDetails.map { it.sku to it })
                    onCompleted(detailsByID)
                }
            } else {
                onCompleted(detailsByID)
            }
        }
    }

    private fun sendUpdatedPurchaserInfoToDelegateIfChanged(info: PurchaserInfo) {
        if (lastSentPurchaserInfo != info) {
            lastSentPurchaserInfo = info
            updatedPurchaserInfoListener?.onReceived(info)
        }
    }
    // endregion

    // region Overriden methods
    /**
     * @suppress
     */
    override fun onPurchasesUpdated(purchases: List<@JvmSuppressWildcards Purchase>) {
        // TODO: if there's no purchaseCallbacks call the delegate
        postPurchases(
            purchases,
            allowSharingPlayStoreAccount,
            { purchase, info ->
                purchaseCallbacks.remove(purchase.sku)?.onCompleted(purchase.sku, info, null)
            },
            { purchase, error ->
                purchaseCallbacks.remove(purchase.sku)?.onCompleted(
                    null,
                    null,
                    PurchasesError(error.domain, error.code, error.message)
                )
            }
        )
    }

    /**
     * @suppress
     */
    override fun onPurchasesFailedToUpdate(
        purchases: List<Purchase>?,
        @BillingClient.BillingResponse responseCode: Int,
        message: String
    ) {
        // TODO: this will send error to all purchases on queue
        purchases?.mapNotNull { purchaseCallbacks.remove(it.sku) }?.forEach {
            it.onCompleted(
                null,
                null,
                PurchasesError(ErrorDomains.PLAY_BILLING, responseCode, message)
            )
        }
    }
    // endregion

    // region Static
    companion object {

        private var _sharedInstance: Purchases? = null
        /**
         * Singleton instance of Purchases
         * @return A previously set singleton Purchases instance or null
         */
        @JvmStatic
        var sharedInstance: Purchases
            get() =
                _sharedInstance
                    ?: throw UninitializedPropertyAccessException("Make sure you call Purchases.configure before")
            @VisibleForTesting(otherwise = VisibleForTesting.NONE)
            internal set(value) {
                _sharedInstance?.close()
                _sharedInstance = value
            }

        /**
         * Current version of the Purchases SDK
         */
        @JvmStatic
        val frameworkVersion = "1.5.0-SNAPSHOT"

        fun configure(
            context: Context,
            apiKey: String,
            appUserID: String? = null,
            service: ExecutorService = createDefaultExecutor()
        ): Purchases {
            if (!context.hasPermission(Manifest.permission.INTERNET))
                throw IllegalArgumentException("Purchases requires INTERNET permission.")

            if (apiKey.isBlank())
                throw IllegalArgumentException("API key must be set. Get this from the RevenueCat web app")

            if (context.applicationContext !is Application)
                throw IllegalArgumentException("Needs an application context.")

            val backend = Backend(
                apiKey,
                Dispatcher(service),
                HTTPClient(),
                PurchaserInfo.Factory,
                Entitlement.Factory
            )

            val billingWrapper = BillingWrapper(
                BillingWrapper.ClientFactory((context.getApplication()).applicationContext),
                Handler((context.getApplication()).mainLooper)
            )

            val prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplication())
            val cache = DeviceCache(prefs, apiKey)

            return Purchases(
                appUserID,
                backend,
                billingWrapper,
                cache
            ).also { sharedInstance = it }
        }

        private fun Context.getApplication() = applicationContext as Application

        private fun Context.hasPermission(permission: String): Boolean {
            return checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED
        }

        private fun createDefaultExecutor(): ExecutorService {
            return ThreadPoolExecutor(
                1,
                2,
                0,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue()
            )
        }
    }
    // endregion

    // endregion
}

data class PurchasesError(
    val domain: ErrorDomains,
    val code: Int,
    val message: String?
)
