package com.revenuecat.purchases.interfaces;

import com.revenuecat.purchases.PurchaserInfo;

/**
 * Used to handle async updates from Purchases
 */
public interface UpdatedPurchaserInfoListener {
    /**
     * Called when a new purchaser info has been received
     *
     * @param purchaserInfo Updated purchaser info
     */
    void onReceived(PurchaserInfo purchaserInfo);
}