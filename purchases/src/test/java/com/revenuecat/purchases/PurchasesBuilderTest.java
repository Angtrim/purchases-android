package com.revenuecat.purchases;

import android.app.Application;
import android.support.test.runner.AndroidJUnit4;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static android.support.test.InstrumentationRegistry.getInstrumentation;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(manifest=Config.NONE)
public class PurchasesBuilderTest {

    @Mock(answer = RETURNS_DEEP_STUBS) private Application application;
    private Purchases.PurchasesListener listener = new Purchases.PurchasesListener() {
        @Override
        public void onReceiveUpdatedPurchaserInfo(PurchaserInfo purchaserInfo) {

        }
    };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(application.getApplicationContext()).thenReturn(application);
    }


    @Test
    public void canCreateABuilder() {
        createBuilder();
    }

    @Test(expected = IllegalArgumentException.class)
    public void mustHaveContext() {
        String apiKey = "api_key";
        new Purchases.Builder(null, apiKey);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mustHaveAPIKey() {
        new Purchases.Builder(application, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void apiKeyMustBeNonEmpty() {
        new Purchases.Builder(application, "");
    }

    private Purchases.Builder createBuilder() {
        String apiKey = "api_key";
        return new Purchases.Builder(application, apiKey);
    }

    private void runWithLooper(Runnable runnable) {
        getInstrumentation().runOnMainSync(runnable);
    }

    @Test
    public void canSetAppUserID() {
        final String appUserID = "app_user_id";
        final Purchases.Builder builder = createBuilder();
        builder.appUserID(appUserID);
        runWithLooper(new Runnable() {
            @Override
            public void run() {
                Purchases p = builder.build();

                assertEquals(appUserID, p.getAppUserID());

                assertNotNull(p);
            }
        });
    }

    @Test
    public void canBuildAPurchasesObject() {
        runWithLooper(new Runnable() {
            @Override
            public void run() {
                String apiKey = "api_key";

                Purchases.Builder builder = new Purchases.Builder(application, apiKey);

                Purchases purchases = builder.build();

                assertNotNull(purchases);
            }
        });
    }
}
