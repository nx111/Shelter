package net.typeblog.shelter.util;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

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

    private final static String LOG_TAG = "Shelter";
    private ApplicationInfo mInfo = null;
    private String mLabel = null;
    private boolean mIsHidden = false;
    // PackageManager.MATCH_ANY_USER marked as SystemApi, so redefend it here.
    private static final int MATCH_ANY_USER = 0x00400000;

    private ApplicationInfoWrapper() {}

    public ApplicationInfoWrapper(ApplicationInfo info) {
        mInfo = info;
    }

    public ApplicationInfoWrapper loadLabel(PackageManager pm) {
        mLabel = pm.getApplicationLabel(mInfo).toString();
        return this;
    }

    public static boolean canLaunch(Context context, String packageName, boolean isProfileOwner) {
        UserManager userManager = (UserManager)context.getSystemService(Context.USER_SERVICE);
        LauncherApps launcherApps = (LauncherApps)context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        List<UserHandle> profiles = userManager.getUserProfiles();
        UserHandle curUserHandle = null;

        for (UserHandle profile:profiles) {
            if (!isProfileOwner && 0 == profile.hashCode()) {
                curUserHandle = profile;
                break;
            } else if (isProfileOwner && 0 != profile.hashCode()){
                curUserHandle = profile;
                break;
            }
        }

        if (launcherApps.getActivityList(packageName, curUserHandle).isEmpty()) {
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
