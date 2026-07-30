package com.nukedsc55.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements Sc55PanelView.Listener {
    private static final int PICK_ROMS = 100;
    private static final String[] MODEL_LABELS = {
            "SC-55mkII", "SC-55", "SC-55st", "CM-300 / SCC-1", "JV-880", "SCB-55", "RLP-3237", "SC-155", "SC-155mkII"
    };
    private static final String[] MODEL_IDS = {"mk2","mk1","st","cm300","jv880","scb55","rlp3237","sc155","sc155mk2"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<MidiDevice> midiDevices = new ArrayList<>();
    private final List<MidiOutputPort> midiPorts = new ArrayList<>();
    private final Set<Integer> openingDevices = new HashSet<>();
    private TextView statusView;
    private TextView romView;
    private Spinner modelSpinner;
    private CheckBox oversampling;
    private Button powerButton;
    private MidiManager midiManager;

    private final MidiManager.DeviceCallback midiDeviceCallback = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo info) { openMidiDevice(info); }
    };

    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            statusView.setText(AudioEngineService.status);
            statusView.setTextColor(AudioEngineService.running ? Color.rgb(167,216,88) : Color.rgb(205,210,214));
            powerButton.setText(AudioEngineService.running ? "■ 停止" : "● 启动");
            handler.postDelayed(this, 350);
        }
    };

    private final MidiReceiver externalMidiReceiver = new MidiReceiver() {
        @Override public void onSend(byte[] msg, int offset, int count, long timestamp) {
            NativeBridge.sendMidi(msg, offset, count);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(14,16,19));
        buildUi();
        setupMidi();
        refreshRomCount();
        handler.post(statusUpdater);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10,12,15));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(18), dp(10), dp(14), dp(10));
        toolbar.setBackgroundColor(Color.rgb(19,22,27));

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_title); title.setTextColor(Color.WHITE); title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.app_subtitle); subtitle.setTextColor(Color.rgb(143,150,158)); subtitle.setTextSize(10);
        titleGroup.addView(title);
        titleGroup.addView(subtitle);
        toolbar.addView(titleGroup, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button importButton = button("导入 ROM");
        importButton.setOnClickListener(v -> pickRoms());
        toolbar.addView(importButton);
        Button infoButton = button("说明");
        infoButton.setOnClickListener(v -> showInfo());
        toolbar.addView(infoButton);
        root.addView(toolbar);

        LinearLayout config = new LinearLayout(this);
        config.setGravity(Gravity.CENTER_VERTICAL);
        config.setPadding(dp(14), dp(9), dp(14), dp(9));
        config.setBackgroundColor(Color.rgb(25,28,34));

        modelSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_dropdown_item, MODEL_LABELS) {
            private View style(View view, boolean dropdown) {
                if (view instanceof TextView) {
                    TextView text = (TextView) view;
                    text.setTextColor(dropdown ? Color.rgb(28,31,35) : Color.rgb(235,238,240));
                    text.setTextSize(14);
                    text.setPadding(dp(12), 0, dp(12), 0);
                    if (dropdown) text.setBackgroundColor(Color.rgb(245,246,247));
                }
                return view;
            }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return style(super.getView(position, convertView, parent), false);
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return style(super.getDropDownView(position, convertView, parent), true);
            }
        };
        modelSpinner.setAdapter(adapter);
        modelSpinner.setBackground(rounded(Color.rgb(42,46,53), 8, Color.rgb(70,75,83)));
        int savedModel = getPreferences(MODE_PRIVATE).getInt("model", 0);
        modelSpinner.setSelection(savedModel);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        spinnerParams.setMargins(0, 0, dp(8), 0);
        config.addView(modelSpinner, spinnerParams);

        oversampling = new CheckBox(this);
        oversampling.setText(R.string.oversampling_short); oversampling.setTextColor(Color.rgb(199,204,209));
        oversampling.setTextSize(12);
        config.addView(oversampling);
        powerButton = button("● 启动");
        powerButton.setBackground(rounded(Color.rgb(67,111,54), 8, Color.rgb(111,156,74)));
        powerButton.setOnClickListener(v -> toggleEngine());
        config.addView(powerButton);
        root.addView(config);

        LinearLayout stateBar = new LinearLayout(this);
        stateBar.setPadding(dp(18), dp(7), dp(18), dp(7));
        stateBar.setBackgroundColor(Color.rgb(14,16,20));
        statusView = new TextView(this); statusView.setTextSize(12); statusView.setTextColor(Color.LTGRAY);
        stateBar.addView(statusView, new LinearLayout.LayoutParams(0, dp(24), 1));
        romView = new TextView(this); romView.setTextSize(12); romView.setTextColor(Color.rgb(145,150,155)); romView.setGravity(Gravity.END);
        stateBar.addView(romView, new LinearLayout.LayoutParams(0, dp(24), 1));
        root.addView(stateBar);

        Sc55PanelView panel = new Sc55PanelView(this);
        panel.setListener(this);
        root.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text); button.setTextSize(12); button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setBackground(rounded(Color.rgb(47,52,59), 8, Color.rgb(69,75,83)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        p.setMargins(dp(5),0,0,0); button.setLayoutParams(p);
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void pickRoms() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_ROMS);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ROMS || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i=0; i<data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        importRoms(uris);
    }

    private void importRoms(List<Uri> uris) {
        statusView.setText("正在导入并校验文件…");
        ioExecutor.execute(() -> {
            int copied = 0;
            File dir = romDirectory();
            if (!dir.exists() && !dir.mkdirs()) {
                runOnUiThread(() -> Toast.makeText(this, "无法创建 ROM 目录", Toast.LENGTH_LONG).show());
                return;
            }
            ContentResolver resolver = getContentResolver();
            for (Uri uri : uris) {
                String name = displayName(resolver, uri).replaceAll("[^A-Za-z0-9._-]", "_");
                if (name.isEmpty()) name = "rom_" + System.nanoTime() + ".bin";
                try (InputStream in = resolver.openInputStream(uri); FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                    if (in == null) continue;
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                    copied++;
                } catch (Exception ignored) {}
            }
            int result = copied;
            runOnUiThread(() -> {
                refreshRomCount();
                Toast.makeText(this, "已导入 " + result + " 个文件；启动时会按 SHA-256 识别 ROM", Toast.LENGTH_LONG).show();
            });
        });
    }

    private String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "rom.bin" : uri.getLastPathSegment();
    }

    private File romDirectory() { return new File(getFilesDir(), "roms"); }

    private void refreshRomCount() {
        File[] files = romDirectory().listFiles();
        romView.setText(getString(R.string.rom_file_count, files == null ? 0 : files.length));
    }

    private void toggleEngine() {
        if (AudioEngineService.running) {
            Intent stop = new Intent(this, AudioEngineService.class).setAction(AudioEngineService.ACTION_STOP);
            startService(stop);
        } else {
            int index = modelSpinner.getSelectedItemPosition();
            getPreferences(MODE_PRIVATE).edit().putInt("model", index).apply();
            Intent start = new Intent(this, AudioEngineService.class)
                    .setAction(AudioEngineService.ACTION_START)
                    .putExtra(AudioEngineService.EXTRA_ROM_DIR, romDirectory().getAbsolutePath())
                    .putExtra(AudioEngineService.EXTRA_MODEL, MODEL_IDS[index])
                    .putExtra(AudioEngineService.EXTRA_OVERSAMPLING, oversampling.isChecked());
            startForegroundService(start);
            AudioEngineService.status = "正在载入 " + MODEL_LABELS[index] + "…";
        }
    }

    private void showInfo() {
        new AlertDialog.Builder(this)
                .setTitle("关于与 ROM")
                .setMessage("这是 Nuked-SC55 的 Android 非商业前端。应用不包含 Roland 固件或波形 ROM；请只导入你从自有硬件合法转储的完整 ROM 集。\n\n启动后可直接弹奏屏幕键盘。其他 Android 应用可把 MIDI 输出连接到“SC-55 Synth / MIDI IN”，USB MIDI 键盘也会自动接入。\n\n核心按原始 MAME 非商业许可证使用，完整许可证与源代码随项目提供。")
                .setPositiveButton("知道了", null).show();
    }

    private void setupMidi() {
        midiManager = getSystemService(MidiManager.class);
        if (midiManager == null) return;
        midiManager.registerDeviceCallback(midiDeviceCallback, handler);
        for (MidiDeviceInfo info : midiManager.getDevices()) openMidiDevice(info);
    }

    private void openMidiDevice(MidiDeviceInfo info) {
        if (info.getType() == MidiDeviceInfo.TYPE_VIRTUAL || openingDevices.contains(info.getId())) return;
        boolean hasOutput = false;
        for (MidiDeviceInfo.PortInfo port : info.getPorts()) if (port.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) hasOutput = true;
        if (!hasOutput) return;
        openingDevices.add(info.getId());
        midiManager.openDevice(info, device -> {
            openingDevices.remove(info.getId());
            if (device == null) return;
            midiDevices.add(device);
            for (MidiDeviceInfo.PortInfo port : info.getPorts()) {
                if (port.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
                    MidiOutputPort output = device.openOutputPort(port.getPortNumber());
                    if (output != null) { output.connect(externalMidiReceiver); midiPorts.add(output); }
                }
            }
        }, handler);
    }

    @Override public void onMidi(int status, int data1, int data2) {
        NativeBridge.sendShortMidi(status, data1, data2);
    }

    @Override public void onReset(boolean gs) {
        NativeBridge.reset(gs ? 1 : 0);
        Toast.makeText(this, gs ? "GS Reset" : "GM Reset", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusUpdater);
        if (midiManager != null) midiManager.unregisterDeviceCallback(midiDeviceCallback);
        for (MidiOutputPort port : midiPorts) try { port.close(); } catch (Exception ignored) {}
        for (MidiDevice device : midiDevices) try { device.close(); } catch (Exception ignored) {}
        ioExecutor.shutdown();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
