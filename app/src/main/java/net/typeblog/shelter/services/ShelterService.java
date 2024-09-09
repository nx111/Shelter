package net.typeblog.shelter.services;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.SyncAdapterType;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;


import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.typeblog.shelter.R;
import net.typeblog.shelter.ShelterApplication;
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver;
import net.typeblog.shelter.ui.DummyActivity;
import net.typeblog.shelter.ui.MainActivity;
import net.typeblog.shelter.util.ApplicationInfoWrapper;
import net.typeblog.shelter.util.FileProviderProxy;
import net.typeblog.shelter.util.UriForwardProxy;
import net.typeblog.shelter.util.Utility;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class ShelterService extends Service {
    private static final String LOG_TAG = "Shelter";
    public static final int RESULT_CANNOT_INSTALL_SYSTEM_APP = 100001;

    private static final int NOTIFICATION_ID = 0x49a11;
    private DevicePolicyManager mPolicyManager = null;
    private boolean mIsProfileOwner = false;
    private PackageManager mPackageManager = null;
    private ComponentName mAdminComponent = null;
    private boolean mPackagesRefreshed = true;

    private static final String[] mGAppsCore = {
        "com.google.android.gsf",
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.backuptransport",
        "com.google.android.feedback",
        "com.google.android.syncadapters.calendar",
        "com.google.android.syncadapters.contacts",
        "com.google.android.gms.setup",

        "com.google.android.apps.restore",
        "com.google.android.configupdater",
        "com.google.android.ext.services",
        "com.google.android.ext.shared",
        "com.google.android.onetimeinitializer",
        "com.google.android.partnersetup",
        "com.google.android.setupwizard",
    };

    // When we need to start an activity, we need something else to do it for us
    // as per background limitation of Android 10
    // We mostly need this for app cloning / installation
    // (we could probably just invoke DummyActivity directly from the other side,
    //  but there are cases where DummyActivity isn't needed, e.g. when cloning
    //  system applications. These cases can be handled without DummyActivity
    //  and without any visual interference.)
    // Note that this proxy can only start activity that is accessible to the
    // main profile and within the application itself.
    private IStartActivityProxy mStartActivityProxy = null;
    private IShelterService.Stub mBinder = new IShelterService.Stub() {
        @Override
        public void ping() {
            // Do nothing, just let the other side know we are alive
        }

        @Override
        public void stopShelterService(boolean kill) {
            // dirty: just wait for some time and kill this service itself
            new Thread(() -> {
                try {
                    Thread.sleep(1);
                } catch (Exception e) {

                }

                ((ShelterApplication) getApplication()).unbindShelterService();

                if (kill && !(mIsProfileOwner && FreezeService.hasPendingAppToFreeze())) {
                    // Just kill the entire process if this signal is received and the process has nothing to do
                    System.exit(0);
                }
            }).start();
        }

        @Override
        public void getApps(IGetAppsCallback callback, boolean showAll) {
            new Thread(() -> {
                int pmFlags = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES;
                List<ApplicationInfoWrapper> list = mPackageManager.getInstalledApplications(pmFlags)
                        .stream()
                        .filter((it) -> !it.packageName.equals(getPackageName()))
                        .filter((it) -> {
                            boolean isSystem = (it.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                            boolean isHidden = isHidden(it.packageName);
                            boolean isInstalled = (it.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
                            boolean canLaunch = ApplicationInfoWrapper.canLaunch(getApplicationContext(), it.packageName, mIsProfileOwner);
                            return showAll || (!isSystem && isInstalled) || (!isSystem && isHidden) || canLaunch;
                        })
                        .map(ApplicationInfoWrapper::new)
                        .map((it) -> it.loadLabel(mPackageManager)
                                .setHidden(isHidden(it.getPackageName())))
                        .sorted((x, y) -> {
                            // Sort hidden apps at the last
                            if (x.isHidden() && !y.isHidden()) {
                                return 1;
                            } else if (!x.isHidden() && y.isHidden()) {
                                return -1;
                            } else {
                                return x.getLabel().compareTo(y.getLabel());
                            }
                        })
                        .collect(Collectors.toList());

                try {
                    callback.callback(list);
                } catch (RemoteException e) {
                    // Do Nothing
                }
            }).start();
        }

        @Override
        public void loadIcon(ApplicationInfoWrapper info, ILoadIconCallback callback) {
            new Thread(() -> {
                Bitmap icon = Utility.drawableToBitmap(info.getInfo().loadUnbadgedIcon(mPackageManager));

                try {
                    callback.callback(icon);
                } catch (RemoteException e) {
                    // Do Nothing
                }
            }).start();
        }

        @Override
        public void installApp(ApplicationInfoWrapper app, IAppInstallCallback callback) throws RemoteException {
            if (!app.isSystem()) {
                // Installing a non-system app requires firing up PackageInstaller
                // Delegate this operation to DummyActivity because
                // Only it can receive a result
                Intent intent = new Intent(DummyActivity.INSTALL_PACKAGE);
                intent.setComponent(new ComponentName(ShelterService.this, DummyActivity.class));
                intent.putExtra("package", app.getPackageName());
                intent.putExtra("apk", app.getSourceDir());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    intent.putExtra("split_apks", app.getSplitApks());

                String logMsg = "installApp: packageName: " + app.getPackageName() + " base: " + app.getSourceDir();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (app.getInfo().splitSourceDirs != null){
                        intent.putExtra("split_apks", app.getInfo().splitSourceDirs);
                        logMsg += " split_apks:" + Arrays.toString(app.getInfo().splitSourceDirs);
                    }
                }

                if (Log.isLoggable(LOG_TAG, Log.VERBOSE)){
                    Log.v(LOG_TAG, logMsg);
                }

                // Send the callback to the DummyActivity
                Bundle callbackExtra = new Bundle();
                callbackExtra.putBinder("callback", callback.asBinder());
                intent.putExtra("callback", callbackExtra);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                DummyActivity.registerSameProcessRequest(intent);
                if (mStartActivityProxy != null)
                    mStartActivityProxy.startActivity(intent);
            } else {
                if (mIsProfileOwner) {
                    // We can only enable system apps in our own profile
                    mPolicyManager.enableSystemApp(
                            mAdminComponent,
                            app.getPackageName());

                    // Also set the hidden state to false.
                    mPolicyManager.setApplicationHidden(
                            mAdminComponent,
                            app.getPackageName(), false);

                    callback.callback(Activity.RESULT_OK);
                } else {
                    callback.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP);
                }
            }
        }

        @Override
        public void installApk(UriForwardProxy uriForwarder, IAppInstallCallback callback) throws RemoteException {
            // Directly install an APK through a given Fd
            // instead of installing an existing one
            Intent intent = new Intent(DummyActivity.INSTALL_PACKAGE);
            intent.setComponent(new ComponentName(ShelterService.this, DummyActivity.class));
            // Generate a content Uri pointing to the Fd
            // DummyActivity is expected to release the Fd after finishing
            Uri uri = FileProviderProxy.setUriForwardProxy(uriForwarder, "apk");
            intent.putExtra("direct_install_apk", uri);

            // Send the callback to the DummyActivity
            Bundle callbackExtra = new Bundle();
            callbackExtra.putBinder("callback", callback.asBinder());
            intent.putExtra("callback", callbackExtra);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            DummyActivity.registerSameProcessRequest(intent);
            if (mStartActivityProxy != null)
                mStartActivityProxy.startActivity(intent);
        }

        @Override
        public void uninstallApp(ApplicationInfoWrapper app, IAppInstallCallback callback) throws RemoteException {
            if (!app.isSystem()) {
                // Similarly, fire up DummyActivity to do uninstallation for us
                Intent intent = new Intent(DummyActivity.UNINSTALL_PACKAGE);
                intent.setComponent(new ComponentName(ShelterService.this, DummyActivity.class));
                intent.putExtra("package", app.getPackageName());

                // Send the callback to the DummyActivity
                Bundle callbackExtra = new Bundle();
                callbackExtra.putBinder("callback", callback.asBinder());
                intent.putExtra("callback", callbackExtra);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                DummyActivity.registerSameProcessRequest(intent);

                if (mStartActivityProxy != null)
                    mStartActivityProxy.startActivity(intent);
            } else {
                if (mIsProfileOwner) {
                    // This is essentially the same as disabling the system app
                    // There is no way to reverse the "enableSystemApp" operation here
                    mPolicyManager.setApplicationHidden(
                            mAdminComponent,
                            app.getPackageName(), true);
                    callback.callback(Activity.RESULT_OK);
                } else {
                    callback.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP);
                }
            }
        }

        @Override
        public void freezeApp(ApplicationInfoWrapper app) {
            if (!mIsProfileOwner)
                throw new IllegalArgumentException("Cannot freeze app without being profile owner");

            mPolicyManager.setApplicationHidden(
                    mAdminComponent,
                    app.getPackageName(), true);
        }

        @Override
        public void unfreezeApp(ApplicationInfoWrapper app) {
            if (!mIsProfileOwner)
                throw new IllegalArgumentException("Cannot unfreeze app without being profile owner");

            mPolicyManager.setApplicationHidden(
                    mAdminComponent,
                    app.getPackageName(), false);
        }

        @Override
        public void setSyncAutomatilly(boolean enabled) {
            if (!mIsProfileOwner) {
                //Log.d(LOG_TAG, "Please set sync automatilly by Settings -> Account on local system");
                return;
            }
            boolean masterSyncAutomatically = ContentResolver.getMasterSyncAutomatically();
            if (!masterSyncAutomatically && enabled) {
                ContentResolver.setMasterSyncAutomatically(true);
                masterSyncAutomatically = ContentResolver.getMasterSyncAutomatically();
            } else if (masterSyncAutomatically && !enabled) {
                ContentResolver.setMasterSyncAutomatically(false);
                return;
            }
            if (!masterSyncAutomatically) {
                Log.d(LOG_TAG, "setMasterSyncAutomatically failed!");
                return;
            }

            SyncAdapterType[] types = ContentResolver.getSyncAdapterTypes();
            AccountManager accmgr = AccountManager.get(getApplicationContext());
            for (SyncAdapterType type : types) {
                Account[] accounts = accmgr.getAccountsByType(type.accountType);
                for (Account account : accounts) {
                    ContentResolver.setIsSyncable(account, type.authority,  1);
                    ContentResolver.setSyncAutomatically(account, type.authority, true);
                    boolean mEnabled = ContentResolver.getSyncAutomatically(account, type.authority);
                    if (!mEnabled) {
                        Log.d(LOG_TAG, "account: " + account.name + " not set to sync automatically.");
                    }
                    if (mEnabled) {
                        Log.d(LOG_TAG, "synching account: " + account.name);
                        // trigger update for next account
                        ContentResolver.requestSync(account, type.authority, new Bundle());
                    }
                }
       		}
        }

        @Override
        public boolean hasUsageStatsPermission() {
            return Utility.checkUsageStatsPermission(ShelterService.this);
        }

        @Override
        public boolean hasSystemAlertPermission() {
            return Utility.checkSystemAlertPermission(ShelterService.this);
        }

        @Override
        public boolean hasAllFileAccessPermission() {
            return Utility.checkAllFileAccessPermission();
        }

        @Override
        public List<String> getCrossProfileWidgetProviders() {
            if (!mIsProfileOwner)
                throw new IllegalStateException("Cannot access cross-profile widget providers without being profile owner");
            return mPolicyManager.getCrossProfileWidgetProviders(mAdminComponent);
        }

        @Override
        public boolean setCrossProfileWidgetProviderEnabled(String pkgName, boolean enabled) {
            if (!mIsProfileOwner)
                throw new IllegalStateException("Cannot access cross-profile widget providers without being profile owner");
            if (enabled) {
                return mPolicyManager.addCrossProfileWidgetProvider(mAdminComponent, pkgName);
            } else {
                return mPolicyManager.removeCrossProfileWidgetProvider(mAdminComponent, pkgName);
            }
        }

       @Override
       public boolean getPackagesRefreshed() {
           return mPackagesRefreshed;
       }

       @Override
       public void setPackagesRefreshed(boolean value) {
           mPackagesRefreshed = value;
       }

       @Override
       public void setStartActivityProxy(IStartActivityProxy proxy) {
           mStartActivityProxy = proxy;
       }
        @Override
        public List<String> getCrossProfilePackages() throws RemoteException {
            if (!mIsProfileOwner)
                throw new IllegalStateException("Cannot access cross-profile packages without being profile owner");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                throw new IllegalStateException("Cross-profile packages support is only available on Android 11 and later");
            return new ArrayList<>(mPolicyManager.getCrossProfilePackages(mAdminComponent));
        }

        @Override
        public void setCrossProfilePackages(List<String> packages) throws RemoteException {
            if (!mIsProfileOwner)
                throw new IllegalStateException("Cannot access cross-profile packages without being profile owner");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                throw new IllegalStateException("Cross-profile packages support is only available on Android 11 and later");
            mPolicyManager.setCrossProfilePackages(mAdminComponent, new HashSet<>(packages));
        }
    };

    // Receiver for packages add/change/remove events
    // used for app changes
    private BroadcastReceiver mPackageInstallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_PACKAGE_INSTALL)
                    || action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || action.equals(Intent.ACTION_PACKAGE_CHANGED)
                    || action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                mPackagesRefreshed = true;
            };
        }
	};

    @Override
    public void onCreate() {
        mPolicyManager = getSystemService(DevicePolicyManager.class);
        mPackageManager = getPackageManager();
        mIsProfileOwner = mPolicyManager.isProfileOwnerApp(getPackageName());
        mAdminComponent = new ComponentName(getApplicationContext(), ShelterDeviceAdminReceiver.class);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_INSTALL);
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        registerReceiver(mPackageInstallReceiver, filter);

        InstallGAppsCore(false);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mPackageInstallReceiver);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (intent.getBooleanExtra("foreground", false)) {
            setForeground();
        }
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Stop our foreground notification (if it was created at all) when
        // all clients have disconnected.
        // This helps to ensure no notification is left when the Shelter activity
        // is closed.
        stopForeground(true);
        return false;
    }

    private boolean isHidden(String packageName) {
        return mIsProfileOwner && mPolicyManager.isApplicationHidden(mAdminComponent, packageName);
    }

    private void setForeground() {
        startForeground(NOTIFICATION_ID, Utility.buildNotification(this,
                getString(R.string.app_name),
                getString(R.string.service_title),
                getString(R.string.service_desc),
                R.drawable.ic_notification_white_24dp));
    }

    private void InstallGAppsCore(boolean force) {
        if (mIsProfileOwner) {
            String PREFS_NAME = "prefs_gapps_core";
            SharedPreferences prefSystemAppEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefSystemAppEnabled.edit();

            ComponentName mAdminComponent = new ComponentName(getApplicationContext(), ShelterDeviceAdminReceiver.class);

            for (String packageName : mGAppsCore) {
                try {
                    if (!prefSystemAppEnabled.getBoolean(packageName, false) || force) {
                        ApplicationInfo mInfo = mPackageManager.getApplicationInfo(packageName, 0);
                        if ((mInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0 && (mInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            mPolicyManager.enableSystemApp(mAdminComponent, packageName);
                            editor.putBoolean(packageName, true);
                        }
                    } else if (Log.isLoggable(LOG_TAG, Log.VERBOSE)){
                        Log.v(LOG_TAG, "installGAppsCore: " + packageName + " always installed in Shelter.");
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // do nothing
                } catch (Exception e) {
                    Log.e(LOG_TAG, "enable System App " + " failed (" + packageName + "), Exception thrown:" + e);
                }
            }
            editor.apply();
        } else if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "installGAppsCore: GApps Core install to Shelter failed,  not profile owner.");
        }
    }
}
