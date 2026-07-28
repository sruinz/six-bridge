package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

public final class TestUri extends Uri {
    @Override public Builder buildUpon() { return null; }
    @Override public String getAuthority() { return "draft"; }
    @Override public String getEncodedAuthority() { return "draft"; }
    @Override public String getEncodedFragment() { return null; }
    @Override public String getEncodedPath() { return "/selected.crx"; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getEncodedSchemeSpecificPart() { return "//draft/selected.crx"; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getHost() { return "draft"; }
    @Override public String getLastPathSegment() { return "selected.crx"; }
    @Override public String getPath() { return "/selected.crx"; }
    @Override public List<String> getPathSegments() { return Collections.singletonList("selected.crx"); }
    @Override public int getPort() { return -1; }
    @Override public String getQuery() { return null; }
    @Override public String getScheme() { return "content"; }
    @Override public String getSchemeSpecificPart() { return "//draft/selected.crx"; }
    @Override public String getUserInfo() { return null; }
    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) {}
    @Override public String toString() { return "content://draft/selected.crx"; }
}
