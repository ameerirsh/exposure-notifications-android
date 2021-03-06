/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.google.android.apps.exposurenotification.home;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender.SendIntentException;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.apps.exposurenotification.R;
import com.google.android.apps.exposurenotification.exposure.ExposureHomeViewModel;
import com.google.android.apps.exposurenotification.proto.UiInteraction.EventType;
import com.google.android.apps.exposurenotification.onboarding.OnboardingStartFragment;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationSharedPreferences.BadgeStatus;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationSharedPreferences.OnboardingStatus;
import com.google.android.apps.exposurenotification.utils.RequestCodes;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.Objects;

/**
 * Main Activity for the Exposure Notification Application.
 *
 * <p>This activity uses fragments to show the various screens of the application.
 *
 * <p>Onboarding is handled by {@link OnboardingStartFragment}.
 *
 * <p>The main screen of the application is in @{link HomeFragment}.
 *
 * <p>The fragment stack will always contain HomeFragment as the first item, even when showing the
 * onboarding flow.
 */
@AndroidEntryPoint
public final class ExposureNotificationActivity extends AppCompatActivity {

  private static final String TAG = "ExposureNotifnActivity";

  public static final String ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION =
      "com.google.android.apps.exposurenotification.ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION";

  private static final String SAVED_INSTANCE_STATE_FRAGMENT_KEY =
      "ExposureNotificationActivity.SAVED_INSTANCE_STATE_FRAGMENT_KEY";

  public static final String HOME_FRAGMENT_TAG = "ExposureNotificationActivity.HOME_FRAGMENT_TAG";

  private ExposureNotificationViewModel exposureNotificationViewModel;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_exposure_notification);

    /*
     * On start of the app (but not on screen rotation, thus when savedInstanceState == null):
     * Dismiss "new" badges that have already been seen
     */
    if (savedInstanceState == null) {
      ExposureHomeViewModel exposureHomeViewModel =
          new ViewModelProvider(this).get(ExposureHomeViewModel.class);
      exposureHomeViewModel.tryTransitionExposureClassificationNew(
          BadgeStatus.SEEN, BadgeStatus.DISMISSED);
      exposureHomeViewModel.tryTransitionExposureClassificationDateNew(
          BadgeStatus.SEEN, BadgeStatus.DISMISSED);
    }


    exposureNotificationViewModel =
        new ViewModelProvider(this).get(ExposureNotificationViewModel.class);

    exposureNotificationViewModel
        .getResolutionRequiredLiveEvent()
        .observe(
            this,
            apiException -> {
              try {
                Log.d(TAG, "startResolutionForResult");
                apiException
                    .getStatus()
                    .startResolutionForResult(
                        this, RequestCodes.REQUEST_CODE_START_EXPOSURE_NOTIFICATION);
              } catch (SendIntentException e) {
                Log.w(TAG, "Error calling startResolutionForResult", apiException);
              }
            });

    if (savedInstanceState != null) {
      // If this is a configuration change such as rotation, restore the HomeFragment that was
      // previously saved in onSaveInstanceState
      FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
      fragmentTransaction.replace(
          R.id.home_fragment,
          Objects.requireNonNull(
              getSupportFragmentManager()
                  .getFragment(savedInstanceState, SAVED_INSTANCE_STATE_FRAGMENT_KEY)),
          HOME_FRAGMENT_TAG);
      fragmentTransaction.commit();
      exposureNotificationViewModel.logUiInteraction(EventType.APP_OPENED);
    } else {
      // This is a fresh launch.
      if (exposureNotificationViewModel.getOnboardedState() == OnboardingStatus.UNKNOWN) {
        // If the user has not seen the onboarding flow, show it when the app resumes.
        getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.home_fragment, new OnboardingStartFragment(), HOME_FRAGMENT_TAG)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .commitNow();
      } else {
        // Otherwise transition to the home UI.
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        fragmentTransaction
            .replace(R.id.home_fragment, HomeFragment.newInstance(), HOME_FRAGMENT_TAG);
        fragmentTransaction.commit();
      }
    }

    IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
    intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
    registerReceiver(refreshBroadcastReceiver, intentFilter);
  }

  private final BroadcastReceiver refreshBroadcastReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      refreshState();
    }
  };

  @Override
  public void onResume() {
    super.onResume();
    refreshState();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    unregisterReceiver(refreshBroadcastReceiver);
  }

  private void refreshState() {
    exposureNotificationViewModel.refreshState();
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    // onNewIntent means a new intent triggered (e.g. a notification click). Change the home
    // fragment tab to the exposures tab.
    // TODO: handle different intents separately
    for (Fragment fragment : getSupportFragmentManager().getFragments()) {
      if (fragment instanceof HomeFragment) {
        ((HomeFragment) fragment).setTab(HomeFragment.TAB_EXPOSURES);
      }
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    onResolutionComplete(requestCode, resultCode);
  }

  /**
   * Called when opt-in resolution is completed by user.
   *
   * <p>Modeled after {@code Activity#onActivityResult} as that's how the API sends callback to
   * apps.
   */
  public void onResolutionComplete(int requestCode, int resultCode) {
    if (requestCode != RequestCodes.REQUEST_CODE_START_EXPOSURE_NOTIFICATION) {
      return;
    }
    if (resultCode == Activity.RESULT_OK) {
      exposureNotificationViewModel.startResolutionResultOk();
    } else {
      exposureNotificationViewModel.startResolutionResultNotOk();
    }
  }

  /**
   * Save the {@link HomeFragment} across rotations or other configuration changes.
   *
   * @param outState passed to onCreate when the app finishes the configuration change.
   */
  @Override
  protected void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    getSupportFragmentManager()
        .putFragment(
            outState,
            SAVED_INSTANCE_STATE_FRAGMENT_KEY,
            Objects.requireNonNull(
                getSupportFragmentManager().findFragmentByTag(HOME_FRAGMENT_TAG)));
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    // Propagate to the fragments.
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  @Override
  public void onTitleChanged(CharSequence title, int color) {
    super.onTitleChanged(title, color);
    // Fire a TYPE_WINDOW_STATE_CHANGED event so that the accessibility service will be notified
    // of window title change.
    getWindow().getDecorView().sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
  }
}
