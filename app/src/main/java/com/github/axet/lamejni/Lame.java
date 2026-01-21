package com.github.axet.lamejni;

public class Lame {
    private long handle;

    public Lame() {
    }

    public native void open(int channels, int sampleRate, int bitRate, int quality);

    public native byte[] encode(short[] buffer, int offset, int length);

    public native byte[] encodeInterleavedMono(short[] buffer, int offset, int length, int channels);

    public native byte[] encode_float(float[] buffer, int offset, int length);

    public native byte[] encodeInterleavedMonoFloat(float[] buffer, int offset, int length, int channels);

    public native byte[] close();

    static {
        if (Config.natives) {
            System.loadLibrary("lamejni");
        }
    }
}
