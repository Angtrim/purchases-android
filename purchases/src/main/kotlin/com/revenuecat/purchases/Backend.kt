package com.revenuecat.purchases

import android.net.Uri

import org.json.JSONException
import org.json.JSONObject

import java.util.HashMap

internal class Backend(
    private val apiKey: String,
    private val dispatcher: Dispatcher,
    private val httpClient: HTTPClient,
    private val purchaserInfoFactory: PurchaserInfo.Factory,
    private val entitlementFactory: Entitlement.Factory
) {

    internal val authenticationHeaders: MutableMap<String, String>

    abstract class BackendResponseHandler {
        abstract fun onReceivePurchaserInfo(info: PurchaserInfo)
        abstract fun onError(code: Int, message: String?)
    }

    private abstract inner class PurchaserInfoReceivingCall internal constructor(
        private val onSuccessHandler: (PurchaserInfo) -> Unit,
        private val onErrorHandler: (PurchasesError) -> Unit
    ) : Dispatcher.AsyncCall() {

        override fun onCompletion(result: HTTPClient.Result) {
            if (result.responseCode < 300) {
                try {
                    onSuccessHandler(purchaserInfoFactory.build(result.body!!))
                } catch (e: JSONException) {
                    onErrorHandler(PurchasesError(
                        Purchases.ErrorDomains.REVENUECAT_BACKEND,
                        result.responseCode,
                        e.message
                    ))
                }
            } else {
                onErrorHandler(
                    PurchasesError(
                        Purchases.ErrorDomains.REVENUECAT_BACKEND,
                        result.responseCode,
                        try {
                            "Server error: ${result.body!!.getString("message")}"
                        } catch (jsonException: JSONException) {
                            "Unexpected server error ${result.responseCode}"
                        }
                    )
                )
            }
        }

        override fun onError(code: Int, message: String) {
            onErrorHandler(PurchasesError(Purchases.ErrorDomains.REVENUECAT_BACKEND, code, message))
        }
    }

    init {

        this.authenticationHeaders = HashMap()
        this.authenticationHeaders["Authorization"] = "Bearer " + this.apiKey
    }

    fun close() {
        this.dispatcher.close()
    }

    private fun enqueue(call: Dispatcher.AsyncCall) {
        if (!dispatcher.isClosed()) {
            dispatcher.enqueue(call)
        }
    }

    fun getPurchaserInfo(
        appUserID: String,
        onSuccessHandler: (PurchaserInfo) -> Unit,
        onErrorHandler: (PurchasesError) -> Unit
    ) {
        enqueue(object : PurchaserInfoReceivingCall(onSuccessHandler, onErrorHandler) {
            @Throws(HTTPClient.HTTPErrorException::class)
            override fun call(): HTTPClient.Result {
                return httpClient.performRequest(
                    "/subscribers/" + encode(appUserID),
                    null as Map<*, *>?,
                    authenticationHeaders
                )
            }
        })
    }

    fun postReceiptData(
        purchaseToken: String,
        appUserID: String,
        productID: String,
        isRestore: Boolean?,
        onSuccessHandler: (PurchaserInfo) -> Unit,
        onErrorHandler: (PurchasesError) -> Unit
    ) {
        val body = HashMap<String, Any?>()

        body["fetch_token"] = purchaseToken
        body["product_id"] = productID
        body["app_user_id"] = appUserID
        body["is_restore"] = isRestore

        enqueue(object : PurchaserInfoReceivingCall(onSuccessHandler, onErrorHandler) {
            @Throws(HTTPClient.HTTPErrorException::class)
            override fun call(): HTTPClient.Result {
                return httpClient.performRequest("/receipts", body, authenticationHeaders)
            }
        })
    }

    fun getEntitlements(
        appUserID: String,
        onSuccess: (Map<String, Entitlement>) -> Unit,
        onError: (PurchasesError) -> Unit
    ) {
        enqueue(object : Dispatcher.AsyncCall() {
            @Throws(HTTPClient.HTTPErrorException::class)
            override fun call(): HTTPClient.Result {
                return httpClient.performRequest(
                    "/subscribers/" + encode(appUserID) + "/products",
                    null as Map<*, *>?,
                    authenticationHeaders
                )
            }

            override fun onError(code: Int, message: String) {
                onError(PurchasesError(Purchases.ErrorDomains.REVENUECAT_BACKEND, code, message))
            }

            override fun onCompletion(result: HTTPClient.Result) {
                if (result.responseCode < 300) {
                    try {
                        val entitlementsResponse = result.body!!.getJSONObject("entitlements")
                        val entitlementMap = entitlementFactory.build(entitlementsResponse)
                        onSuccess(entitlementMap)
                    } catch (e: JSONException) {
                        onError(PurchasesError(
                            Purchases.ErrorDomains.REVENUECAT_BACKEND,
                            result.responseCode,
                            "Error parsing products JSON " + e.localizedMessage
                        ))
                    }

                } else {
                    onError(PurchasesError(
                        Purchases.ErrorDomains.REVENUECAT_BACKEND,
                        result.responseCode,
                        "Backend error"
                    ))
                }
            }
        })
    }

    private fun encode(string: String): String {
        return Uri.encode(string)
    }

    fun postAttributionData(
        appUserID: String,
        network: Purchases.AttributionNetwork,
        data: JSONObject
    ) {
        if (data.length() == 0) return

        val body = JSONObject()
        try {
            body.put("network", network.serverValue)
            body.put("data", data)
        } catch (e: JSONException) {
            return
        }

        enqueue(object : Dispatcher.AsyncCall() {
            @Throws(HTTPClient.HTTPErrorException::class)
            override fun call(): HTTPClient.Result {
                return httpClient.performRequest(
                    "/subscribers/" + encode(appUserID) + "/attribution",
                    body,
                    authenticationHeaders
                )
            }
        })
    }

    fun createAlias(
        appUserID: String,
        newAppUserID: String,
        onSuccessHandler: () -> Unit,
        onErrorHandler: (PurchasesError) -> Unit
    ) {
        val body = mapOf(
            "new_app_user_id" to newAppUserID
        )

        enqueue(object : Dispatcher.AsyncCall() {
            @Throws(HTTPClient.HTTPErrorException::class)
            override fun call(): HTTPClient.Result {
                return httpClient.performRequest(
                    "/subscribers/" + encode(appUserID) + "/alias",
                    body,
                    authenticationHeaders
                )
            }

            override fun onError(code: Int, message: String) {
                onErrorHandler(PurchasesError(Purchases.ErrorDomains.REVENUECAT_BACKEND, code, message))
            }

            override fun onCompletion(result: HTTPClient.Result) {
                if (result.responseCode < 300) {
                    try {
                        onSuccessHandler()
                    } catch (e: JSONException) {
                        onErrorHandler(PurchasesError(
                            Purchases.ErrorDomains.REVENUECAT_BACKEND,
                            result.responseCode,
                            "Backend error"
                        ))
                    }
                } else {
                    onErrorHandler(PurchasesError(
                        Purchases.ErrorDomains.REVENUECAT_BACKEND,
                        result.responseCode,
                        "Backend error"
                    ))
                }
            }
        })
    }
}
