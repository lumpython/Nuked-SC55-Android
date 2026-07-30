package com.nukedsc55.android;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;
import java.io.IOException;

public final class Sc55MidiDeviceService extends MidiDeviceService {
    private final MidiReceiver receiver = new MidiReceiver() {
        @Override public void onSend(byte[] msg, int offset, int count, long timestamp) throws IOException {
            NativeBridge.sendMidi(msg, offset, count);
        }
    };

    @Override public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[] { receiver };
    }
}
