/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.util.GamepadUtils;
import org.mozilla.gecko.util.ThreadUtils;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.Math;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;


public class AndroidGamepadManager {
    // This is completely arbitrary.
    private static final float TRIGGER_PRESSED_THRESHOLD = 0.25f;
    private static final long POLL_TIMER_PERIOD = 1000; // milliseconds

    private static enum Axis {
        X(MotionEvent.AXIS_X),
        Y(MotionEvent.AXIS_Y),
        Z(MotionEvent.AXIS_Z),
        RZ(MotionEvent.AXIS_RZ);

        public final int axis;

        private Axis(int axis) {
            this.axis = axis;
        }
    };

    // A list of gamepad button mappings. Axes are determined at
    // runtime, as they vary by Android version.
    private static enum Trigger {
        Left(6),
        Right(7);

        public final int button;

        private Trigger(int button) {
            this.button = button;
        }
    };

    private static final int FIRST_DPAD_BUTTON = 12;
    // A list of axis number, gamepad button mappings for negative, positive.
    // Button mappings are added to FIRST_DPAD_BUTTON.
    private static enum DpadAxis {
        UpDown(MotionEvent.AXIS_HAT_Y, 0, 1),
        LeftRight(MotionEvent.AXIS_HAT_X, 2, 3);

        public final int axis;
        public final int negativeButton;
        public final int positiveButton;

        private DpadAxis(int axis, int negativeButton, int positiveButton) {
            this.axis = axis;
            this.negativeButton = negativeButton;
            this.positiveButton = positiveButton;
        }
    };

    private static enum Button {
        A(KeyEvent.KEYCODE_BUTTON_A),
        B(KeyEvent.KEYCODE_BUTTON_B),
        X(KeyEvent.KEYCODE_BUTTON_X),
        Y(KeyEvent.KEYCODE_BUTTON_Y),
        L1(KeyEvent.KEYCODE_BUTTON_L1),
        R1(KeyEvent.KEYCODE_BUTTON_R1),
        L2(KeyEvent.KEYCODE_BUTTON_L2),
        R2(KeyEvent.KEYCODE_BUTTON_R2),
        SELECT(KeyEvent.KEYCODE_BUTTON_SELECT),
        START(KeyEvent.KEYCODE_BUTTON_START),
        THUMBL(KeyEvent.KEYCODE_BUTTON_THUMBL),
        THUMBR(KeyEvent.KEYCODE_BUTTON_THUMBR),
        DPAD_UP(KeyEvent.KEYCODE_DPAD_UP),
        DPAD_DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
        DPAD_LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
        DPAD_RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT);

        public final int button;

        private Button(int button) {
            this.button = button;
        }
    };

    private static class Gamepad {
        // ID from GamepadService
        public int id;
        // Retain axis state so we can determine changes.
        public float axes[];
        public boolean dpad[];
        public int triggerAxes[];
        public float triggers[];

        public Gamepad(int serviceId, int deviceId) {
            id = serviceId;
            axes = new float[Axis.values().length];
            dpad = new boolean[4];
            triggers = new float[2];

            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null) {
                // LTRIGGER/RTRIGGER don't seem to be exposed on older
                // versions of Android.
                if (device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null && device.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null) {
                    triggerAxes = new int[]{MotionEvent.AXIS_LTRIGGER,
                                            MotionEvent.AXIS_RTRIGGER};
                } else if (device.getMotionRange(MotionEvent.AXIS_BRAKE) != null && device.getMotionRange(MotionEvent.AXIS_GAS) != null) {
                    triggerAxes = new int[]{MotionEvent.AXIS_BRAKE,
                                            MotionEvent.AXIS_GAS};
                } else {
                    triggerAxes = null;
                }
            }
        }
    }

    private static boolean sStarted = false;
    private static HashMap<Integer, Gamepad> sGamepads = null;
    private static HashMap<Integer, List<KeyEvent>> sPendingGamepads = null;
    private static InputManager.InputDeviceListener sListener = null;
    private static Timer sPollTimer = null;

    private AndroidGamepadManager() {
    }

    public static void startup() {
        ThreadUtils.assertOnUiThread();
        if (!sStarted) {
            sGamepads = new HashMap<Integer, Gamepad>();
            sPendingGamepads = new HashMap<Integer, List<KeyEvent>>();
            scanForGamepads();
            addDeviceListener();
            sStarted = true;
        }
    }

    public static void shutdown() {
        ThreadUtils.assertOnUiThread();
        if (sStarted) {
            removeDeviceListener();
            sPendingGamepads = null;
            sGamepads = null;
            sStarted = false;
        }
    }

    public static void gamepadAdded(int deviceId, int serviceId) {
        ThreadUtils.assertOnUiThread();
        if (!sStarted) {
            return;
        }
        if (!sPendingGamepads.containsKey(deviceId)) {
            removeGamepad(deviceId);
            return;
        }

        List<KeyEvent> pending = sPendingGamepads.get(deviceId);
        sPendingGamepads.remove(deviceId);
        sGamepads.put(deviceId, new Gamepad(serviceId, deviceId));
        // Handle queued KeyEvents
        for (KeyEvent ev : pending) {
            handleKeyEvent(ev);
        }
    }

    private static float deadZone(MotionEvent ev, int axis) {
        if (GamepadUtils.isValueInDeadZone(ev, axis)) {
            return 0.0f;
        }
        return ev.getAxisValue(axis);
    }

    private static void mapDpadAxis(Gamepad gamepad,
                                    boolean pressed,
                                    float value,
                                    int which) {
        if (pressed != gamepad.dpad[which]) {
            gamepad.dpad[which] = pressed;
            GeckoAppShell.sendEventToGecko(GeckoEvent.createGamepadButtonEvent(gamepad.id, FIRST_DPAD_BUTTON + which, pressed, Math.abs(value)));
        }
    }

    public static boolean handleMotionEvent(MotionEvent ev) {
        ThreadUtils.assertOnUiThread();
        if (!sStarted) {
            return false;
        }

        if (!sGamepads.containsKey(ev.getDeviceId())) {
            // Not a device we care about.
            return false;
        }

        Gamepad gamepad = sGamepads.get(ev.getDeviceId());
        // First check the analog stick axes
        boolean[] valid = new boolean[Axis.values().length];
        float[] axes = new float[Axis.values().length];
        boolean anyValidAxes = false;
        for (Axis axis : Axis.values()) {
            float value = deadZone(ev, axis.axis);
            int i = axis.ordinal();
            if (value != gamepad.axes[i]) {
                axes[i] = value;
                gamepad.axes[i] = value;
                valid[i] = true;
                anyValidAxes = true;
            }
        }
        if (anyValidAxes) {
            // Send an axismove event.
            GeckoAppShell.sendEventToGecko(GeckoEvent.createGamepadAxisEvent(gamepad.id, valid, axes));
        }

        // Map triggers to buttons.
        if (gamepad.triggerAxes != null) {
            for (Trigger trigger : Trigger.values()) {
                int i = trigger.ordinal();
                int axis = gamepad.triggerAxes[i];
                float value = deadZone(ev, axis);
                if (value != gamepad.triggers[i]) {
                    gamepad.triggers[i] = value;
                    boolean pressed = value > TRIGGER_PRESSED_THRESHOLD;
                    GeckoAppShell.sendEventToGecko(GeckoEvent.createGamepadButtonEvent(gamepad.id, trigger.button, pressed, value));
                }
            }
        }
        // Map d-pad to buttons.
        for (DpadAxis dpadaxis : DpadAxis.values()) {
            float value = deadZone(ev, dpadaxis.axis);
            mapDpadAxis(gamepad, value < 0.0f, value, dpadaxis.negativeButton);
            mapDpadAxis(gamepad, value > 0.0f, value, dpadaxis.positiveButton);
        }
        return true;
    }

    public static boolean handleKeyEvent(KeyEvent ev) {
        ThreadUtils.assertOnUiThread();
        if (!sStarted) {
            return false;
        }

        int deviceId = ev.getDeviceId();
        if (sPendingGamepads.containsKey(deviceId)) {
            // Queue up key events for pending devices.
            sPendingGamepads.get(deviceId).add(ev);
            return true;
        } else if (!sGamepads.containsKey(deviceId)) {
            InputDevice device = ev.getDevice();
            if (device != null &&
                (device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                // This is a gamepad we haven't seen yet.
                addGamepad(device);
                sPendingGamepads.get(deviceId).add(ev);
                return true;
            }
            // Not a device we care about.
            return false;
        }

        int key = -1;
        for (Button button : Button.values()) {
            if (button.button == ev.getKeyCode()) {
                key = button.ordinal();
                break;
            }
        }
        if (key == -1) {
            // Not a key we know how to handle.
            return false;
        }
        if (ev.getRepeatCount() > 0) {
            // We would handle this key, but we're not interested in
            // repeats. Eat it.
            return true;
        }

        Gamepad gamepad = sGamepads.get(deviceId);
        boolean pressed = ev.getAction() == KeyEvent.ACTION_DOWN;
        GeckoAppShell.sendEventToGecko(GeckoEvent.createGamepadButtonEvent(gamepad.id, key, pressed, pressed ? 1.0f : 0.0f));
        return true;
    }

    private static void scanForGamepads() {
        int[] deviceIds = InputDevice.getDeviceIds();
        if (deviceIds == null) {
            return;
        }
        for (int i=0; i < deviceIds.length; i++) {
            InputDevice device = InputDevice.getDevice(deviceIds[i]);
            if (device == null) {
                continue;
            }
            if ((device.getSources() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) {
                continue;
            }
            addGamepad(device);
        }
    }

    private static void addGamepad(InputDevice device) {
        //TODO: when we're using a newer SDK version, use these.
        //if (Build.VERSION.SDK_INT >= 12) {
        //int vid = device.getVendorId();
        //int pid = device.getProductId();
        //}
        sPendingGamepads.put(device.getId(), new ArrayList<KeyEvent>());
        GeckoAppShell.sendEventToGecko(GeckoEvent.createGamepadAddRemoveEvent(device.getId(), true));
    }

    private static void removeGamepad(int deviceId) {
        Gamepad gamepad = sGamepads.get(deviceId);
        GeckoAppShell.sendEventToGecko(GeckoEvent.createGamepadAddRemoveEvent(gamepad.id, false));
        sGamepads.remove(deviceId);
    }

    private static void addDeviceListener() {
        if (Build.VERSION.SDK_INT < 16) {
            // Poll known gamepads to see if they've disappeared.
            sPollTimer = new Timer();
            sPollTimer.scheduleAtFixedRate(new TimerTask() {
                    public void run() {
                        for (Integer deviceId : sGamepads.keySet()) {
                            if (InputDevice.getDevice(deviceId) == null) {
                                removeGamepad(deviceId);
                            }
                        }
                    }
                }, POLL_TIMER_PERIOD, POLL_TIMER_PERIOD);
            return;
        }
        sListener = new InputManager.InputDeviceListener() {
                public void onInputDeviceAdded(int deviceId) {
                    InputDevice device = InputDevice.getDevice(deviceId);
                    if (device == null) {
                        return;
                    }
                    if ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                        addGamepad(device);
                    }
                }

                public void onInputDeviceRemoved(int deviceId) {
                    if (sPendingGamepads.containsKey(deviceId)) {
                        // Got removed before Gecko's ack reached us.
                        // gamepadAdded will deal with it.
                        sPendingGamepads.remove(deviceId);
                        return;
                    }
                    if (sGamepads.containsKey(deviceId)) {
                        removeGamepad(deviceId);
                    }
                }

                public void onInputDeviceChanged(int deviceId) {
                }
            };
        ((InputManager)GeckoAppShell.getContext().getSystemService(Context.INPUT_SERVICE)).registerInputDeviceListener(sListener, ThreadUtils.getUiHandler());
    }

    private static void removeDeviceListener() {
        if (Build.VERSION.SDK_INT < 16) {
            if (sPollTimer != null) {
                sPollTimer.cancel();
                sPollTimer = null;
            }
            return;
        }
        ((InputManager)GeckoAppShell.getContext().getSystemService(Context.INPUT_SERVICE)).unregisterInputDeviceListener(sListener);
        sListener = null;
    }
}
