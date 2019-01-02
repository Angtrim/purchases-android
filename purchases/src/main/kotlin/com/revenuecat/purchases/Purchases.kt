package com.revenuecat.purchases

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import android.content.pm.PackageManager.PERMISSION_GRANTED
import com.revenuecat.purchases.interfaces.PurchaseCompletedListener
import com.revenuecat.purchases.interfaces.ReceiveEntitlementsListener
import com.revenuecat.purchases.interfaces.ReceivePurchaserInfoListener

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
    private val application: Application,
    _appUserID: String?,
    private val backend: Backend,
    private val billingWrapper: BillingWrapper,
    private val deviceCache: DeviceCache,
    var allowSharingPlayStoreAccount: Boolean = false,
    internal val postedTokens: HashSet<String> = HashSet(),
    private var cachesLastUpdated: Date? = null,
    private var cachedEntitlements: Map<String, Entitlement>? = null
) : BillingWrapper.PurchasesUpdatedListener, Application.ActivityLifecycleCallbacks {

    /**
     * The passed in or generated app user ID
     */
    var appUserID: String

    private var shouldRefreshCaches = false

    private var purchaseCallbacks: MutableMap<String, PurchaseCompletedListener> = mutableMapOf()

    init {
        if (_appUserID != null) {
            this.appUserID = _appUserID.also {
                identify(it)
            }
        } else {
            this.appUserID = getAnonymousID().also {
                allowSharingPlayStoreAccount = true
                updateCaches()
            }
        }

        billingWrapper.setListener(this)
        application.registerActivityLifecycleCallbacks(this)
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
        } ?: fetchAndCacheEntitlements(handler)
    }

    private fun fetchAndCacheEntitlements(handler: ReceiveEntitlementsListener? = null) {
        backend.getEntitlements(appUserID, object : Backend.EntitlementsResponseHandler() {
            override fun onReceiveEntitlements(entitlements: Map<String, Entitlement>) {
                getSkuDetails(entitlements) { detailsByID ->
                    populateSkuDetailsAndCallHandler(detailsByID, entitlements, handler)
                }
            }

            override fun onError(code: Int, message: String) {
                handler?.onReceived(
                    null, PurchasesError(
                        ErrorDomains.REVENUECAT_BACKEND,
                        code,
                        "Error fetching entitlements: $message"
                    )
                )
            }
        })
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
        }
        billingWrapper.makePurchaseAsync(activity, appUserID, sku, oldSkus, skuType)
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
            object : BillingWrapper.PurchaseHistoryResponseListener {
                override fun onReceivePurchaseHistory(subsPurchasesList: List<Purchase>) {
                    billingWrapper.queryPurchaseHistoryAsync(
                        BillingClient.SkuType.INAPP,
                        object : BillingWrapper.PurchaseHistoryResponseListener {
                            override fun onReceivePurchaseHistory(inAppPurchasesList: List<Purchase>) {
                                val allPurchases = ArrayList(subsPurchasesList)
                                allPurchases.addAll(inAppPurchasesList)
                                if (allPurchases.isEmpty()) {
                                    getPurchaserInfo(completion)
                                } else {
                                    postPurchases(
                                        allPurchases,
                                        true,
                                        { _, info ->
                                            completion.onReceived(info, null)
                                        },
                                        { _, error ->
                                            completion.onReceived(null, error)
                                        }
                                    )
                                }
                            }

                            override fun onReceivePurchaseHistoryError(
                                responseCode: Int,
                                message: String
                            ) {
                                completion.onReceived(
                                    null,
                                    PurchasesError(
                                        ErrorDomains.PLAY_BILLING,
                                        responseCode,
                                        message
                                    )
                                )
                            }
                        })
                }

                override fun onReceivePurchaseHistoryError(responseCode: Int, message: String) {
                    completion.onReceived(
                        null,
                        PurchasesError(
                            ErrorDomains.PLAY_BILLING,
                            responseCode,
                            message
                        )
                    )
                }
            })
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
        deviceCache.clearCachedAppUserID()
        this.appUserID = appUserID
        postedTokens.clear()
        purchaseCallbacks.clear()
        makeCachesOutdatedAndNotifyIfNeeded(completion)
    }

    /**
     * Resets the Purchases client clearing the save appUserID. This will generate a random user id and save it in the cache.
     */
    fun reset() {
        this.appUserID = createRandomIDAndCacheIt()
        allowSharingPlayStoreAccount = true
        postedTokens.clear()
        purchaseCallbacks.clear()
        makeCachesOutdatedAndNotifyIfNeeded()
    }

    /**
     * Call close when you are done with this instance of Purchases
     */
    fun close() {
        purchaseCallbacks.clear()
        this.backend.close()
        billingWrapper.setListener(null)
        application.unregisterActivityLifecycleCallbacks(this)
    }

    fun getPurchaserInfo(
        completion: ReceivePurchaserInfoListener
    ) {
        if (cachesAreValid()) {
            completion.onReceived(deviceCache.getCachedPurchaserInfo(appUserID), null)
        } else {
            updateCaches(completion)
        }
    }

// region Private Methods

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
        cachedEntitlements = entitlements
        handler?.onReceived(entitlements, null)
    }

    private fun getSkus(
        skus: List<String>,
        @BillingClient.SkuType skuType: String,
        handler: GetSkusResponseHandler
    ) {
        billingWrapper.querySkuDetailsAsync(
            skuType,
            skus,
            object : BillingWrapper.SkuDetailsResponseListener {
                override fun onReceiveSkuDetails(skuDetails: List<SkuDetails>) {
                    handler.onReceiveSkus(skuDetails)
                }
            })
    }

    private fun updateCaches(
        completion: ReceivePurchaserInfoListener? = null
    ) {
        fetchAndCachePurchaserInfo(completion)
        fetchAndCacheEntitlements()
    }

    private fun fetchAndCachePurchaserInfo(completion: ReceivePurchaserInfoListener?) {
        backend.getPurchaserInfo(
            appUserID,
            { info ->
                cachePurchaserInfo(info)
                completion?.onReceived(info, null)
            },
            { error ->
                Log.e("Purchases", "Error fetching subscriber data: ${error.message}")
                invalidateCaches()
                completion?.onReceived(null, error)
            })
    }

    private fun cachesAreValid() =
        cachesLastUpdated != null && Date().time - cachesLastUpdated!!.time < 60000

    private fun invalidateCaches() {
        cachesLastUpdated = null
    }

    private fun cachePurchaserInfo(info: PurchaserInfo) {
        cachesLastUpdated = Date()
        deviceCache.cachePurchaserInfo(appUserID, info)
    }

    private fun postPurchases(
        purchases: List<Purchase>,
        allowSharingPlayStoreAccount: Boolean,
        onSuccess: (Purchase, PurchaserInfo) -> Unit,
        onError: (Purchase, PurchasesError) -> Unit
    ) {
        purchases.filter { !postedTokens.contains(it.purchaseToken) }
            .forEach { purchase ->
                postedTokens.add(purchase.purchaseToken)
                backend.postReceiptData(
                    purchase.purchaseToken,
                    appUserID,
                    purchase.sku,
                    allowSharingPlayStoreAccount,
                    { info ->
                        billingWrapper.consumePurchase(purchase.purchaseToken)
                        deviceCache.cachePurchaserInfo(appUserID, info)
                        onSuccess(purchase, info)
                    }, { error ->
                        if (error.code < 500) {
                            billingWrapper.consumePurchase(purchase.purchaseToken)
                            postedTokens.remove(purchase.purchaseToken)
                        }
                        onError(
                            purchase,
                            error
                        )
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
            skus,
            object : BillingWrapper.SkuDetailsResponseListener {
                override fun onReceiveSkuDetails(subscriptionsSKUDetails: List<SkuDetails>) {
                    val detailsByID = HashMap<String, SkuDetails>()

                    val inAPPSkus = skus -
                            subscriptionsSKUDetails
                                .map { details -> details.sku to details }
                                .also { skuToDetails -> detailsByID.putAll(skuToDetails) }
                                .map { skuToDetails -> skuToDetails.first }

                    if (inAPPSkus.isNotEmpty()) {
                        billingWrapper.querySkuDetailsAsync(
                            BillingClient.SkuType.INAPP,
                            inAPPSkus,
                            object : BillingWrapper.SkuDetailsResponseListener {
                                override fun onReceiveSkuDetails(skuDetails: List<SkuDetails>) {
                                    detailsByID.putAll(skuDetails.map { it.sku to it })
                                    onCompleted(detailsByID)
                                }
                            }
                        )
                    } else {
                        onCompleted(detailsByID)
                    }
                }
            }
        )
    }

    private fun makeCachesOutdatedAndNotifyIfNeeded(
        completion: ReceivePurchaserInfoListener? = null
    ) {
        invalidateCaches()
        updateCaches(completion)
    }

// endregion
// region Overriden methods
    /**
     * @suppress
     */
    override fun onPurchasesUpdated(purchases: List<@JvmSuppressWildcards Purchase>) {
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

    /**
     * @suppress
     */
    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {

    }

    /**
     * @suppress
     */
    override fun onActivityStarted(activity: Activity) {

    }

    /**
     * @suppress
     */
    override fun onActivityResumed(activity: Activity) {
        if (shouldRefreshCaches) updateCaches()
        shouldRefreshCaches = false
    }

    /**
     * @suppress
     */
    override fun onActivityPaused(activity: Activity) {
        shouldRefreshCaches = true
    }

    /**
     * @suppress
     */
    override fun onActivityStopped(activity: Activity) {

    }

    /**
     * @suppress
     */
    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle?) {

    }

    /**
     * @suppress
     */
    override fun onActivityDestroyed(activity: Activity) {

    }
// endregion
// region Builder

    /**
     * Used to construct a Purchases object
     * @param context Application context that will be used to communicate with Google
     * @param apiKey RevenueCat apiKey. Make sure you follow the [quickstart](https://docs.revenuecat.com/docs/getting-started-1)
     * guide to setup your RevenueCat account.
     */
    class Builder(
        private val context: Context,
        private val apiKey: String
    ) {
        private val application: Application
            get() = context.applicationContext as Application

        private var appUserID: String? = null

        private var service: ExecutorService? = null

        init {
            if (!hasPermission(context, Manifest.permission.INTERNET))
                throw IllegalArgumentException("Purchases requires INTERNET permission.")

            if (apiKey.isBlank())
                throw IllegalArgumentException("API key must be set. Get this from the RevenueCat web app")

            if (context.applicationContext !is Application)
                throw IllegalArgumentException("Needs an application context.")
        }

        /**
         * Used to set a user identifier. Check out this [guide](https://docs.revenuecat.com/docs/user-ids)
         */
        fun appUserID(appUserID: String) = apply { this.appUserID = appUserID }

        /**
         * Used to set a network executor service for this Purchases instance
         */
        fun networkExecutorService(service: ExecutorService) = apply { this.service = service }

        /**
         * Used to build the Purchases instance
         * @return A Purchases instance. Make sure you set it as the [sharedInstance] if you
         * want to reuse it.
         */
        fun build(): Purchases {

            val service = this.service ?: createDefaultExecutor()

            val backend = Backend(
                this.apiKey,
                Dispatcher(service),
                HTTPClient(),
                PurchaserInfo.Factory,
                Entitlement.Factory
            )

            val billingWrapper = BillingWrapper(
                BillingWrapper.ClientFactory(application.applicationContext),
                Handler(application.mainLooper)
            )

            val prefs = PreferenceManager.getDefaultSharedPreferences(this.application)
            val cache = DeviceCache(prefs, apiKey)

            return Purchases(
                application,
                appUserID,
                backend,
                billingWrapper,
                cache
            )
        }

        private fun hasPermission(context: Context, permission: String): Boolean {
            return context.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED
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
// region Static
    companion object {
        /**
         * Singleton instance of Purchases
         * @return A previously set singleton Purchases instance or null
         */
        @JvmStatic
        var sharedInstance: Purchases? = null
            set(value) {
                field?.close()
                field = value
            }
        /**
         * Current version of the Purchases SDK
         */
        @JvmStatic
        val frameworkVersion = "1.5.0-SNAPSHOT"
    }

    /**
     * Different error domains
     */
    enum class ErrorDomains {
        /**
         * The error is related to the RevenueCat backend
         */
        REVENUECAT_BACKEND,
        /**
         * The error is related to Play Billing
         */
        PLAY_BILLING,
        REVENUECAT_API
    }

    enum class PurchasesAPIError {
        DUPLICATE_MAKE_PURCHASE_CALLS
    }

    /**
     * Different compatible attribution networks available
     * @param serverValue Id of this attribution network in the RevenueCat server
     */
    enum class AttributionNetwork(val serverValue: Int) {
        /**
         * [https://www.adjust.com/]
         */
        ADJUST(1),
        /**
         * [https://www.appsflyer.com/]
         */
        APPSFLYER(2),
        /**
         * [http://branch.io/]
         */
        BRANCH(3)
    }
// endregion
// region Interfaces
    /**
     * Used to handle async updates from Purchases
     */
    interface PurchasesListener {
        /**
         * Called when a new purchaser info has been received
         * @param purchaserInfo Updated purchaser info after a successful purchase
         */
        fun onReceiveUpdatedPurchaserInfo(purchaserInfo: PurchaserInfo)
    }

    /**
     * Used when retrieving subscriptions
     */
    interface GetSkusResponseHandler {
        /**
         * Will be called after fetching subscriptions
         * @param skus List of SkuDetails
         */
        fun onReceiveSkus(skus: @JvmSuppressWildcards List<SkuDetails>)
    }

}

class PurchasesError(
    val domain: Purchases.ErrorDomains,
    val code: Int,
    val message: String?
)
