package com.nukedsc55.android;

public final class NativeBridge {
    static { System.loadLibrary("nuked-sc55"); }

    private NativeBridge() {}

    public static native String load(String romDirectory, String model, boolean oversampling);
    public static native void unload();
    public static native int sampleRate();
    public static native void setOutputSampleRate(int sampleRate);
    public static native int render(short[] stereoSamples, int frames);
    public static native void sendMidi(byte[] data, int offset, int count);
    public static native void sendShortMidi(int status, int data1, int data2);
    public static native void reset(int type); // 0=GM, 1=GS
    public static native long underrunCount();
    public static native boolean isLoaded();
}
