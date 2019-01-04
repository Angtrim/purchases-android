package com.revenuecat.purchases_sample

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

import com.android.billingclient.api.SkuDetails
import com.revenuecat.purchases.Entitlement
import com.revenuecat.purchases.PurchaserInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.interfaces.PurchaseCompletedListener
import com.revenuecat.purchases.interfaces.ReceivePurchaserInfoListener

import java.util.ArrayList
import java.util.Date

class MainActivityKotlin : AppCompatActivity(), Purchases.PurchasesListener {

    private var purchases: Purchases? = null
    private var monthlySkuDetails: SkuDetails? = null
    private var consumableSkuDetails: SkuDetails? = null
    private var mButton: Button? = null
    private var mConsumableButton: Button? = null
    private var mRecyclerView: RecyclerView? = null

    private var mLayoutManager: LinearLayoutManager? = null
    private var entitlementMap: Map<String, Entitlement>? = null

    private var useAlternateID = false
    private fun currentAppUserID(): String {
        return if (useAlternateID) "cesar5" else "random1"
    }

    private fun buildPurchases() {
        purchases = Purchases.sharedInstance
        Purchases.frameworkVersion
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mRecyclerView = findViewById(R.id.expirationDates)

        mLayoutManager = LinearLayoutManager(this)
        mRecyclerView!!.layoutManager = mLayoutManager

        buildPurchases()

        mButton = findViewById(R.id.button)
        mButton!!.setOnClickListener {
            purchases!!.makePurchase(
                this@MainActivityKotlin,
                monthlySkuDetails!!.sku,
                monthlySkuDetails!!.type,
                completion = PurchaseCompletedListener { sku, purchaserInfo, error ->
                    if (error == null) {
                        Log.i("Purchases", "Purchase completed: $purchaserInfo")
                        onReceiveUpdatedPurchaserInfo(purchaserInfo!!)
                    } else {
                        purchases!!.allowSharingPlayStoreAccount = true
                    }
                })
        }
        mButton!!.isEnabled = false

        mConsumableButton = findViewById(R.id.button_consumable)
        mConsumableButton!!.setOnClickListener {
            purchases!!.makePurchase(
                this@MainActivityKotlin,
                consumableSkuDetails!!.sku,
                consumableSkuDetails!!.type,
                completion = PurchaseCompletedListener { _, purchaserInfo, error ->
                    if (error == null) {
                        Log.i("Purchases", "Purchase completed: $purchaserInfo")
                        onReceiveUpdatedPurchaserInfo(purchaserInfo!!)
                    } else {
                        purchases!!.allowSharingPlayStoreAccount = true
                    }
                })
        }
        mConsumableButton!!.isEnabled = false

        val restoreButton = findViewById<Button>(R.id.restoreButton)
        restoreButton.setOnClickListener {
            purchases!!.restorePurchases(ReceivePurchaserInfoListener { info, error ->

            })
        }

        val swapButton = findViewById<Button>(R.id.swapUserButton)
        swapButton.setOnClickListener {
            useAlternateID = !useAlternateID
            buildPurchases()
        }

//        this.purchases!!.getEntitlements(object : Purchases.GetEntitlementsHandler {
//            override fun onReceiveEntitlements(entitlementMap: Map<String, Entitlement>) {
//                val pro = entitlementMap["pro"]
//                val monthly = pro?.offerings?.get("monthly")
//
//                this@MainActivityKotlin.entitlementMap = entitlementMap
//
//                monthlySkuDetails = monthly?.skuDetails
//
//                mButton!!.text = "Buy One Month w/ Trial - " + monthlySkuDetails?.price
//                mButton!!.isEnabled = true
//            }
//
//            override fun onReceiveEntitlementsError(
//                domain: Purchases.ErrorDomains,
//                code: Int,
//                message: String
//            ) {
//
//            }
//        })
        val list = ArrayList<String>()
        list.add("consumable")
        this.purchases!!.getNonSubscriptionSkus(list, object : Purchases.GetSkusResponseHandler {
            override fun onReceiveSkus(skus: List<SkuDetails>) {
                var consumable: SkuDetails? = null
                if (!skus.isEmpty()) {
                    consumable = skus[0]
                }
                this@MainActivityKotlin.consumableSkuDetails = consumable
                mConsumableButton!!.isEnabled = true
            }
        })
    }

    override fun onReceiveUpdatedPurchaserInfo(purchaserInfo: PurchaserInfo) {
        Log.i("Purchases", "Got new purchaser info: " + purchaserInfo.activeSubscriptions)
        Log.i("Purchases", "Consumable: " + purchaserInfo.allPurchasedSkus)
        this.runOnUiThread {
            mRecyclerView!!.adapter = ExpirationsAdapter(
                purchaserInfo.activeEntitlements,
                purchaserInfo.allExpirationDatesByEntitlement
            )
            mRecyclerView!!.invalidate()
        }
    }

    fun onRestoreTransactions(purchaserInfo: PurchaserInfo) {
        Log.i("Purchases", "Got new purchaser info: " + purchaserInfo.activeSubscriptions)
        this.runOnUiThread {
            mRecyclerView!!.adapter = ExpirationsAdapter(
                purchaserInfo.activeEntitlements,
                purchaserInfo.allExpirationDatesByEntitlement
            )
            mRecyclerView!!.invalidate()
        }
    }

    fun onRestoreTransactionsFailed(domain: Purchases.ErrorDomains, code: Int, reason: String) {
        Log.i("Purchases", reason)
    }

    class ExpirationsAdapter(
        private val mActiveEntitlements: Set<String>,
        private val mExpirationDates: Map<String, Date?>
    ) : RecyclerView.Adapter<ExpirationsAdapter.ViewHolder>() {
        private val mSortedKeys: ArrayList<String>

        init {
            mSortedKeys = ArrayList(mExpirationDates.keys)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(
                R.layout.text_view,
                parent,
                false
            ) as TextView
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val key = mSortedKeys[position]
            val expiration = mExpirationDates[key]
            val expiredIcon = if (mActiveEntitlements.contains(key)) "✅" else "❌"
            val message = "$key $expiredIcon $expiration"
            holder.mTextView.text = message
        }

        override fun getItemCount(): Int {
            return mSortedKeys.size
        }

        inner class ViewHolder(var mTextView: TextView) :
            RecyclerView.ViewHolder(mTextView)
    }

}
