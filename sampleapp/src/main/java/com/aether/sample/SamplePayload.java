package com.aether.sample;

import android.os.Parcel;
import android.os.Parcelable;

public final class SamplePayload implements Parcelable {
    public final String value;
    public SamplePayload(String value) { this.value = value; }
    private SamplePayload(Parcel p) { value = p.readString(); }
    @Override public void writeToParcel(Parcel p, int flags) { p.writeString(value); }
    @Override public int describeContents() { return 0; }
    public static final Creator<SamplePayload> CREATOR = new Creator<SamplePayload>() {
        public SamplePayload createFromParcel(Parcel p) { return new SamplePayload(p); }
        public SamplePayload[] newArray(int size) { return new SamplePayload[size]; }
    };
}
