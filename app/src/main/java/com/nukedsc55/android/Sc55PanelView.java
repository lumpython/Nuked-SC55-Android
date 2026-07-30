package com.nukedsc55.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

public final class Sc55PanelView extends View {
    public interface Listener {
        void onMidi(int status, int data1, int data2);
        void onReset(boolean gs);
    }

    private static final int WHITE_KEYS = 14;
    private static final int[] WHITE_SEMITONES = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] BLACK_AFTER = {0, 1, 3, 4, 5, 7, 8, 10, 11, 12};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelRect = new RectF();
    private final RectF lcdRect = new RectF();
    private final RectF[] buttonRects = {new RectF(), new RectF(), new RectF(), new RectF()};
    private final SparseIntArray pointerNotes = new SparseIntArray();
    private final int[] activeNoteCounts = new int[128];

    private Shader panelShader;
    private Listener listener;
    private float panelBottom;
    private float keyboardTop;
    private int pressedButton = -1;
    private int program;
    private String programText = "001  PIANO 1";
    private long lastActivity;

    public Sc55PanelView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setMinimumHeight(dpInt(390));
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setProgram(int value) {
        program = Math.max(0, Math.min(127, value));
        programText = String.format(Locale.ROOT, "%03d  TONE", program + 1);
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int dpInt(float value) { return Math.round(dp(value)); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int wantedHeight = Math.max(dpInt(420), Math.round(width * 1.08f));
        setMeasuredDimension(width, resolveSize(wantedHeight, heightSpec));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float maxPanel = Math.max(dp(180), height - dp(170));
        panelBottom = Math.min(maxPanel, Math.max(dp(225), height * .46f));
        keyboardTop = Math.min(height - dp(130), panelBottom + dp(12));
        panelRect.set(dp(8), dp(6), width - dp(8), panelBottom);
        panelShader = new LinearGradient(0, panelRect.top, 0, panelRect.bottom,
                Color.rgb(48, 51, 56), Color.rgb(16, 18, 21), Shader.TileMode.CLAMP);

        lcdRect.set(dp(20), dp(52), width - dp(20), Math.min(dp(145), panelBottom - dp(72)));
        float gap = dp(7);
        float left = dp(20);
        float available = width - dp(40) - gap * 3;
        float buttonWidth = available / 4f;
        float top = lcdRect.bottom + dp(12);
        float bottom = Math.min(panelBottom - dp(18), top + dp(39));
        for (int i = 0; i < buttonRects.length; i++) {
            float x = left + i * (buttonWidth + gap);
            buttonRects[i].set(x, top, x + buttonWidth, bottom);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawRackPanel(canvas);
        drawKeyboard(canvas);
    }

    private void drawRackPanel(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(panelShader);
        paint.setShadowLayer(dp(8), 0, dp(4), 0x88000000);
        canvas.drawRoundRect(panelRect, dp(10), dp(10), paint);
        paint.clearShadowLayer();
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(72, 76, 82));
        canvas.drawRoundRect(panelRect, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setColor(Color.rgb(174, 215, 93));
        paint.setTextSize(dp(10));
        canvas.drawText("NUKED", dp(20), dp(28), paint);
        paint.setColor(Color.rgb(236, 238, 239));
        paint.setTextSize(dp(20));
        canvas.drawText("SOUND Canvas", dp(66), dp(31), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(dp(17));
        canvas.drawText("SC-55", getWidth() - dp(22), dp(30), paint);
        paint.setTextAlign(Paint.Align.LEFT);

        paint.setColor(Color.rgb(2, 4, 4));
        canvas.drawRoundRect(lcdRect.left - dp(5), lcdRect.top - dp(5),
                lcdRect.right + dp(5), lcdRect.bottom + dp(5), dp(5), dp(5), paint);
        paint.setColor(Color.rgb(165, 201, 91));
        canvas.drawRoundRect(lcdRect, dp(2), dp(2), paint);

        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(Color.rgb(31, 54, 24));
        paint.setTextSize(dp(11));
        paint.setFakeBoldText(true);
        canvas.drawText("PART 01     MIDI CH 01", lcdRect.left + dp(10), lcdRect.top + dp(19), paint);
        paint.setTextSize(dp(19));
        canvas.drawText(programText, lcdRect.left + dp(10), lcdRect.top + dp(46), paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(9));
        canvas.drawText("LEVEL", lcdRect.left + dp(10), lcdRect.bottom - dp(10), paint);

        int activity = SystemClock.uptimeMillis() - lastActivity < 140 ? 13 : activeNoteCount() > 0 ? 8 : 1;
        float meterStart = lcdRect.left + dp(52);
        float meterWidth = Math.max(dp(5), (lcdRect.width() - dp(65)) / 16f);
        for (int i = 0; i < 16; i++) {
            paint.setColor(i < activity ? Color.rgb(38, 70, 27) : Color.argb(50, 31, 54, 24));
            float x = meterStart + i * meterWidth;
            canvas.drawRect(x, lcdRect.bottom - dp(18), x + meterWidth - dp(2),
                    lcdRect.bottom - dp(9), paint);
        }
        if (activeNoteCount() > 0) postInvalidateDelayed(90);

        String[] labels = {"GM RESET", "GS RESET", "TONE −", "TONE +"};
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        for (int i = 0; i < buttonRects.length; i++) {
            RectF button = buttonRects[i];
            paint.setColor(i == pressedButton ? Color.rgb(91, 98, 103) : Color.rgb(35, 38, 42));
            paint.setShadowLayer(dp(3), 0, dp(2), Color.BLACK);
            canvas.drawRoundRect(button, dp(5), dp(5), paint);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(93, 98, 103));
            canvas.drawRoundRect(button, dp(5), dp(5), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(9));
            paint.setColor(Color.rgb(221, 224, 226));
            canvas.drawText(labels[i], button.centerX(), button.centerY() + dp(3), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);

        paint.setColor(Color.rgb(226, 61, 48));
        canvas.drawCircle(getWidth() - dp(13), dp(13), dp(3), paint);
        paint.setTextSize(dp(8));
        paint.setColor(Color.rgb(136, 142, 148));
        canvas.drawText("TOUCH KEYBOARD  ·  CH 1  ·  MULTI-TOUCH", dp(20), panelBottom - dp(6), paint);
    }

    private void drawKeyboard(Canvas canvas) {
        float bottom = getHeight() - dp(8);
        if (bottom <= keyboardTop) return;
        float keyWidth = getWidth() / (float) WHITE_KEYS;
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(dp(3), 0, dp(2), 0x66000000);
        for (int i = 0; i < WHITE_KEYS; i++) {
            int note = whiteNote(i);
            paint.setColor(isNoteActive(note) ? Color.rgb(194, 226, 125) : Color.rgb(242, 241, 235));
            float left = i * keyWidth + dp(1);
            float right = (i + 1) * keyWidth - dp(1);
            canvas.drawRoundRect(left, keyboardTop, right, bottom, dp(3), dp(3), paint);
        }
        paint.clearShadowLayer();

        float blackBottom = keyboardTop + (bottom - keyboardTop) * .61f;
        for (int index : BLACK_AFTER) {
            int note = whiteNote(index) + 1;
            float center = (index + 1) * keyWidth;
            paint.setColor(isNoteActive(note) ? Color.rgb(111, 153, 58) : Color.rgb(20, 22, 25));
            paint.setShadowLayer(dp(3), 0, dp(3), 0x99000000);
            canvas.drawRoundRect(center - keyWidth * .31f, keyboardTop,
                    center + keyWidth * .31f, blackBottom, dp(3), dp(3), paint);
            paint.clearShadowLayer();
        }

        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(9));
        paint.setColor(Color.rgb(105, 108, 110));
        canvas.drawText("C3", keyWidth * .5f, bottom - dp(10), paint);
        canvas.drawText("C4", keyWidth * 7.5f, bottom - dp(10), paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private int activeNoteCount() {
        int count = 0;
        for (int value : activeNoteCounts) count += value;
        return count;
    }

    private boolean isNoteActive(int note) {
        return note >= 0 && note < activeNoteCounts.length && activeNoteCounts[note] > 0;
    }

    private int whiteNote(int index) {
        return 48 + (index / 7) * 12 + WHITE_SEMITONES[index % 7];
    }

    private int noteAt(float x, float y) {
        if (y < keyboardTop) return -1;
        float keyWidth = getWidth() / (float) WHITE_KEYS;
        int white = Math.max(0, Math.min(WHITE_KEYS - 1, (int) (x / keyWidth)));
        float blackBottom = keyboardTop + (getHeight() - dp(8) - keyboardTop) * .61f;
        if (y < blackBottom) {
            float local = x / keyWidth;
            int boundary = Math.round(local);
            if (boundary > 0 && boundary < WHITE_KEYS && Math.abs(local - boundary) < .31f) {
                int left = boundary - 1;
                int degree = left % 7;
                if (degree != 2 && degree != 6) return whiteNote(left) + 1;
            }
        }
        return whiteNote(white);
    }

    private int velocityAt(float y) {
        float height = Math.max(1, getHeight() - keyboardTop);
        return Math.max(35, Math.min(127, 35 + Math.round((y - keyboardTop) / height * 92)));
    }

    private void changePointerNote(int pointerId, int nextNote, int velocity) {
        int previous = pointerNotes.get(pointerId, -1);
        if (previous == nextNote) return;
        if (previous >= 0) {
            pointerNotes.delete(pointerId);
            if (--activeNoteCounts[previous] == 0 && listener != null) listener.onMidi(0x80, previous, 0);
        }
        if (nextNote >= 0) {
            pointerNotes.put(pointerId, nextNote);
            if (activeNoteCounts[nextNote]++ == 0 && listener != null) {
                listener.onMidi(0x90, nextNote, velocity);
            }
        }
        lastActivity = SystemClock.uptimeMillis();
        invalidate();
    }

    private int buttonAt(float x, float y) {
        for (int i = 0; i < buttonRects.length; i++) {
            if (buttonRects[i].contains(x, y)) return i;
        }
        return -1;
    }

    private void activateButton(int button) {
        if (listener == null) return;
        if (button == 0 || button == 1) {
            listener.onReset(button == 1);
        } else if (button == 2 || button == 3) {
            setProgram(program + (button == 3 ? 1 : -1));
            listener.onMidi(0xC0, program, 0);
        }
        lastActivity = SystemClock.uptimeMillis();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = event.getX(actionIndex);
            float y = event.getY(actionIndex);
            if (y < keyboardTop) {
                pressedButton = buttonAt(x, y);
                if (pressedButton >= 0) activateButton(pressedButton);
                invalidate();
            } else {
                changePointerNote(event.getPointerId(actionIndex), noteAt(x, y), velocityAt(y));
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i);
                if (pointerNotes.indexOfKey(id) >= 0) {
                    changePointerNote(id, noteAt(event.getX(i), event.getY(i)), velocityAt(event.getY(i)));
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            changePointerNote(event.getPointerId(actionIndex), -1, 0);
            pressedButton = -1;
            performClick();
            invalidate();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            while (pointerNotes.size() > 0) changePointerNote(pointerNotes.keyAt(0), -1, 0);
            pressedButton = -1;
            invalidate();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
