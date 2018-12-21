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

    abstract class EntitlementsResponseHandler {
        abstract fun onReceiveEntitlements(entitlements: Map<String, Entitlement>)
        abstract fun onError(code: Int, message: String)
    }

    abstract class AliasResponseHandler {
        abstract fun onSuccess()
        abstract fun onError(code: Int, message: String)
    }

    private abstract inner class PurchaserInfoReceivingCall internal constructor(
        private val onSuccessHandler: (PurchaserInfo) -> Unit,
        private val onErrorHandler: (Int, String?) -> Unit
    ) : Dispatcher.AsyncCall() {

        override fun onCompletion(result: HTTPClient.Result) {
            if (result.responseCode < 300) {
                try {
                    onSuccessHandler(purchaserInfoFactory.build(result.body!!))
                } catch (e: JSONException) {
                    onErrorHandler(result.responseCode, e.message)
                }
            } else {
                onErrorHandler(
                    result.responseCode,
                    try {
                        "Server error: ${result.body!!.getString("message")}"
                    } catch (jsonException: JSONException) {
                        "Unexpected server error ${result.responseCode}"
                    }
                )
            }
        }

        override fun onError(code: Int, message: String) {
            onErrorHandler(code, message)
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

    fun getSubscriberInfo(
        appUserID: String,
        onSuccessHandler: (PurchaserInfo) -> Unit,
        onErrorHandler: (Int, String?) -> Unit
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
        onErrorHandler: (Int, String?) -> Unit
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

    fun getEntitlements(appUserID: String, handler: EntitlementsResponseHandler) {
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
                handler.onError(code, message)
            }

            override fun onCompletion(result: HTTPClient.Result) {
                if (result.responseCode < 300) {
                    try {
                        val entitlementsResponse = result.body!!.getJSONObject("entitlements")
                        val entitlementMap = entitlementFactory.build(entitlementsResponse)
                        handler.onReceiveEntitlements(entitlementMap)
                    } catch (e: JSONException) {
                        handler.onError(
                            result.responseCode,
                            "Error parsing products JSON " + e.localizedMessage
                        )
                    }

                } else {
                    handler.onError(result.responseCode, "Backend error")
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
        onErrorHandler: (Int, String) -> Unit
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
                onErrorHandler(code, message)
            }

            override fun onCompletion(result: HTTPClient.Result) {
                if (result.responseCode < 300) {
                    try {
                        onSuccessHandler()
                    } catch (e: JSONException) {
                        onErrorHandler(result.responseCode, "Backend error")
                    }
                } else {
                    onErrorHandler(result.responseCode, "Backend error")
                }
            }
        })
    }
}
