package com.revenuecat.purchases.interfaces;

import android.support.annotation.Nullable;

import com.revenuecat.purchases.PurchaserInfo;
import com.revenuecat.purchases.PurchasesError;

public interface ReceivePurchaserInfoListener {
    void onReceived(@Nullable PurchaserInfo purchaserInfo, @Nullable PurchasesError error);
}
