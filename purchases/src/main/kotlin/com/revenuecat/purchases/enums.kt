package com.revenuecat.purchases

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
    BRANCH(3),
    /**
     * [http://tenjin.io/]
     */
    TENJIN(4)
}