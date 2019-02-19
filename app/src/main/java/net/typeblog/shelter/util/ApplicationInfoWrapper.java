package net.typeblog.shelter.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.List;

public class ApplicationInfoWrapper implements Parcelable {
    public static final Parcelable.Creator<ApplicationInfoWrapper> CREATOR = new Parcelable.Creator<ApplicationInfoWrapper>() {
        @Override
        public ApplicationInfoWrapper[] newArray(int size) {
            return new ApplicationInfoWrapper[size];
        }

        @Override
        public ApplicationInfoWrapper createFromParcel(Parcel source) {
            ApplicationInfoWrapper info = new ApplicationInfoWrapper();
            info.mInfo = source.readParcelable(ApplicationInfo.class.getClassLoader());
            info.mLabel = source.readString();
            info.mIsHidden = source.readByte() != 0;
            return info;
        }
    };

    private ApplicationInfo mInfo = null;
    private String mLabel = null;
    private boolean mIsHidden = false;
    // PackageManager.MATCH_ANY_USER marked as SystemApi, so redefend it here.
    private static final int MATCH_ANY_USER = 0x00400000;

    // ingore packages in work profile
    private static final List<String> mIgnorePackages = Arrays.asList(
            "com.google.android.gms",
            "com.android.settings"
    );

    private ApplicationInfoWrapper() {}

    public ApplicationInfoWrapper(ApplicationInfo info) {
        mInfo = info;
    }

    public ApplicationInfoWrapper loadLabel(PackageManager pm) {
        mLabel = pm.getApplicationLabel(mInfo).toString();
        return this;
    }

    public static boolean canLaunch(Context context, String packageName) {
        final int PER_USER_RANGE = 100000;
        ResolveInfo ri;

        ApplicationInfoWrapper info = null;
        PackageManager pm = context.getPackageManager();
        String myPackageName = context.getPackageName();

        try {
            int myUserId = pm.getApplicationInfo(myPackageName,0).uid / PER_USER_RANGE;

            if (mIgnorePackages.contains(packageName) && myUserId > 0) {
                return false;
            }

            info = new ApplicationInfoWrapper(pm.getApplicationInfo(packageName, 0));
            if (info == null || (info.getInfo().uid / PER_USER_RANGE) != myUserId) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
        intentToResolve.setPackage(packageName);
        ri = pm.resolveActivity(intentToResolve, PackageManager.MATCH_DISABLED_COMPONENTS | MATCH_ANY_USER);
        if (ri == null) {
            intentToResolve.removeCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.addCategory(Intent.CATEGORY_INFO);
            intentToResolve.setPackage(packageName);
            ri = pm.resolveActivity(intentToResolve, PackageManager.MATCH_DISABLED_COMPONENTS | MATCH_ANY_USER);
        }

        if (ri == null) {
            return false;
        }

        return true;
    }

    // Only used from ShelterService
    public ApplicationInfoWrapper setHidden(boolean hidden) {
        mIsHidden = hidden;
        return this;
    }

    public String getPackageName() {
        return mInfo.packageName;
    }

    public String getLabel() {
        return mLabel;
    }

    public String getSourceDir() {
        return mInfo.sourceDir;
    }

    // NOTE: This does not relate to the "freezing" feature in Shelter
    public boolean getEnabled() {
        return mInfo.enabled;
    }

    public boolean isHidden() {
        return mIsHidden;
    }

    public ApplicationInfo getInfo() {
        return mInfo;
    }

    public boolean isSystem() {
        return (mInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mInfo, flags);
        dest.writeString(mLabel);
        dest.writeByte((byte) (mIsHidden ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return mInfo.packageName.hashCode();
    }
}
