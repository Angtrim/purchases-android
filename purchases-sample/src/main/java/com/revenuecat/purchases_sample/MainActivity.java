package com.revenuecat.purchases_sample;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.android.billingclient.api.SkuDetails;
import com.revenuecat.purchases.Entitlement;
import com.revenuecat.purchases.Offering;
import com.revenuecat.purchases.PurchaserInfo;
import com.revenuecat.purchases.Purchases;
import com.revenuecat.purchases.PurchasesError;
import com.revenuecat.purchases.interfaces.PurchaseCompletedListener;
import com.revenuecat.purchases.interfaces.ReceiveEntitlementsListener;
import com.revenuecat.purchases.interfaces.ReceivePurchaserInfoListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements Purchases.PurchasesListener {

    private Purchases purchases;
    private SkuDetails monthlySkuDetails;
    private SkuDetails consumableSkuDetails = null;
    private Button mButton;
    private Button mConsumableButton;
    private RecyclerView mRecyclerView;

    private LinearLayoutManager mLayoutManager;
    private Map<String, Entitlement> entitlementMap;

    private boolean useAlternateID = false;

    private String currentAppUserID() {
        return useAlternateID ? "cesar5" : "random1";
    }

    private void buildPurchases() {
        purchases = Purchases.getSharedInstance();
        Purchases.getFrameworkVersion();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mRecyclerView = findViewById(R.id.expirationDates);

        mLayoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(mLayoutManager);

        buildPurchases();

        mButton = findViewById(R.id.button);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                purchases.makePurchase(
                        MainActivity.this,
                        monthlySkuDetails.getSku(),
                        monthlySkuDetails.getType(),
                        new PurchaseCompletedListener() {
                            @Override
                            public void onCompleted(@Nullable String sku, @Nullable PurchaserInfo purchaserInfo, @Nullable PurchasesError error) {
                                if (error == null) {
                                    Log.i("Purchases", "Purchase completed: " + purchaserInfo);
                                    onReceiveUpdatedPurchaserInfo(purchaserInfo);
                                } else {
                                    purchases.setAllowSharingPlayStoreAccount(true);
                                }
                            }
                        });
            }
        });
        mButton.setEnabled(false);

        mConsumableButton = findViewById(R.id.button_consumable);
        mConsumableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                purchases.makePurchase(
                        MainActivity.this,
                        consumableSkuDetails.getSku(),
                        consumableSkuDetails.getType(),
                        new PurchaseCompletedListener() {
                            @Override
                            public void onCompleted(@Nullable String sku, @Nullable PurchaserInfo purchaserInfo, @Nullable PurchasesError error) {
                                if (error == null) {
                                    Log.i("Purchases", "Purchase completed: " + purchaserInfo);
                                    onReceiveUpdatedPurchaserInfo(purchaserInfo);
                                } else {
                                    purchases.setAllowSharingPlayStoreAccount(true);
                                }
                            }
                        });
            }
        });
        mConsumableButton.setEnabled(false);

        Button restoreButton = findViewById(R.id.restoreButton);
        restoreButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                purchases.restorePurchases(new ReceivePurchaserInfoListener() {
                    @Override
                    public void onReceived(@Nullable final PurchaserInfo purchaserInfo, @Nullable PurchasesError error) {
                        if (error == null) {
                            Log.i("Purchases", "Got new purchaser info: " + purchaserInfo.getActiveSubscriptions());
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mRecyclerView.setAdapter(new ExpirationsAdapter(purchaserInfo.getActiveEntitlements(), purchaserInfo.getAllExpirationDatesByEntitlement()));
                                    mRecyclerView.invalidate();
                                }
                            });
                        } else {
                            Log.e("Purchases", error.getMessage());
                        }
                    }
                });
            }
        });

        Button swapButton = findViewById(R.id.swapUserButton);
        swapButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                useAlternateID = !useAlternateID;
                buildPurchases();
            }
        });

        this.purchases.getEntitlements(new ReceiveEntitlementsListener() {
            @Override
            public void onReceived(@Nullable Map<String, Entitlement> entitlementMap, @Nullable PurchasesError error) {
                if (error == null) {
                    Entitlement pro = entitlementMap.get("pro");
                    Offering monthly = pro.getOfferings().get("monthly");

                    MainActivity.this.entitlementMap = entitlementMap;

                    monthlySkuDetails = monthly.getSkuDetails();

                    mButton.setText("Buy One Month w/ Trial - " + monthlySkuDetails.getPrice());
                    mButton.setEnabled(true);
                } else {
                    Log.e("Purchases", error.getMessage());
                }
            }
        });

        List<String> list = new ArrayList<>();
        list.add("consumable");
        this.purchases.getNonSubscriptionSkus(list, new Purchases.GetSkusResponseHandler() {
            @Override
            public void onReceiveSkus(@NonNull List<SkuDetails> skus) {
                SkuDetails consumable = null;
                if (!skus.isEmpty()) {
                    consumable = skus.get(0);
                }
                MainActivity.this.consumableSkuDetails = consumable;
                mConsumableButton.setEnabled(true);
            }
        });

    }

    @Override
    public void onReceiveUpdatedPurchaserInfo(final PurchaserInfo purchaserInfo) {
        Log.i("Purchases", "Got new purchaser info: " + purchaserInfo.getActiveSubscriptions());
        Log.i("Purchases", "Consumable: " + purchaserInfo.getAllPurchasedSkus());
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mRecyclerView.setAdapter(new ExpirationsAdapter(purchaserInfo.getActiveEntitlements(), purchaserInfo.getAllExpirationDatesByEntitlement()));
                mRecyclerView.invalidate();
            }
        });
    }

    public static class ExpirationsAdapter extends RecyclerView.Adapter<ExpirationsAdapter.ViewHolder> {
        private final Map<String, Date> mExpirationDates;
        private final Set<String> mActiveEntitlements;
        private final ArrayList<String> mSortedKeys;

        public ExpirationsAdapter(Set<String> activeEntitlements, Map<String, Date> expirationDatesByEntitlement) {
            mActiveEntitlements = activeEntitlements;
            mExpirationDates = expirationDatesByEntitlement;
            mSortedKeys = new ArrayList<>(expirationDatesByEntitlement.keySet());
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView v = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.text_view, parent, false);
            ViewHolder vh = new ViewHolder(v);
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String key = mSortedKeys.get(position);
            Date expiration = mExpirationDates.get(key);
            String expiredIcon = mActiveEntitlements.contains(key) ? "✅" : "❌";
            String message = key + " " + expiredIcon + " " + expiration;
            holder.mTextView.setText(message);
        }

        @Override
        public int getItemCount() {
            return mSortedKeys.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView mTextView;

            ViewHolder(TextView view) {
                super(view);
                mTextView = view;
            }
        }
    }

}
