package ohi.andre.consolelauncher.ui.device.info;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.features.HTMLExtractManager;
import ohi.andre.consolelauncher.features.NotesManager;
import ohi.andre.consolelauncher.features.TimeManager;
import ohi.andre.consolelauncher.features.TuiLocationManager;
import ohi.andre.consolelauncher.features.settings.SettingsManager;
import ohi.andre.consolelauncher.features.settings.options.Behavior;
import ohi.andre.consolelauncher.features.settings.options.Theme;
import ohi.andre.consolelauncher.features.settings.options.Ui;
import ohi.andre.consolelauncher.tuils.AllowEqualsSequence;
import ohi.andre.consolelauncher.tuils.NetworkUtils;
import ohi.andre.consolelauncher.tuils.Tuils;
import ohi.andre.consolelauncher.tuils.interfaces.OnBatteryUpdate;
import ohi.andre.consolelauncher.ui.UIManager;

public class DeviceInfoManager {
    private enum Label {
        ram,
        device,
        time,
        battery,
        storage,
        network,
        notes,
        weather,
        unlock
    }

    private final int RAM_DELAY = 3000;
    private final int TIME_DELAY = 1000;
    private final int STORAGE_DELAY = 60 * 1000;

    int mediumPercentage, lowPercentage;
    String batteryFormat;

    //    never access this directly, use getLabelView
    private TextView[] labelViews = new TextView[Label.values().length];

    private float[] labelIndexes = new float[labelViews.length];
    private int[] labelSizes = new int[labelViews.length];
    private CharSequence[] labelTexts = new CharSequence[labelViews.length];

    private TextView getLabelView(Label l) {
        return labelViews[(int) labelIndexes[l.ordinal()]];
    }

    private int notesMaxLines;
    private NotesManager notesManager;
    private NotesRunnable notesRunnable;
    private class NotesRunnable implements Runnable {

        int updateTime = 2000;

        @Override
        public void run() {
            if(notesManager != null) {
                if(notesManager.hasChanged) {
                    UIManager.this.updateText(Label.notes, Tuils.span(mContext, labelSizes[Label.notes.ordinal()], notesManager.getNotes()));
                }

                handler.postDelayed(this, updateTime);
            }
        }
    };

    private BatteryUpdate batteryUpdate;
    private class BatteryUpdate implements OnBatteryUpdate {

//        %(charging:not charging)

        //        final Pattern optionalCharging = Pattern.compile("%\\(([^\\/]*)\\/([^)]*)\\)", Pattern.CASE_INSENSITIVE);
        Pattern optionalCharging;
        final Pattern value = Pattern.compile("%v", Pattern.LITERAL | Pattern.CASE_INSENSITIVE);

        boolean manyStatus, loaded;
        int colorHigh, colorMedium, colorLow;

        boolean charging;
        float last = -1;

        @Override
        public void update(float p) {
            if(batteryFormat == null) {
                batteryFormat = SettingsManager.get(Behavior.battery_format);

                Intent intent = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if(intent == null) charging = false;
                else {
                    int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                    charging = plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB;
                }

                String optionalSeparator = "\\" + SettingsManager.get(Behavior.optional_values_separator);
                String optional = "%\\(([^" + optionalSeparator + "]*)" + optionalSeparator + "([^)]*)\\)";
                optionalCharging = Pattern.compile(optional, Pattern.CASE_INSENSITIVE);
            }

            if(p == -1) p = last;
            last = p;

            if(!loaded) {
                loaded = true;

                manyStatus = SettingsManager.getBoolean(Ui.enable_battery_status);
                colorHigh = SettingsManager.getColor(Theme.battery_color_high);
                colorMedium = SettingsManager.getColor(Theme.battery_color_medium);
                colorLow = SettingsManager.getColor(Theme.battery_color_low);
            }

            int percentage = (int) p;

            int color;

            if(manyStatus) {
                if(percentage > mediumPercentage) color = colorHigh;
                else if(percentage > lowPercentage) color = colorMedium;
                else color = colorLow;
            } else {
                color = colorHigh;
            }

            String cp = batteryFormat;

            Matcher m = optionalCharging.matcher(cp);
            while (m.find()) {
                cp = cp.replace(m.group(0), m.groupCount() == 2 ? m.group(charging ? 1 : 2) : Tuils.EMPTYSTRING);
            }

            cp = value.matcher(cp).replaceAll(String.valueOf(percentage));
            cp = Tuils.patternNewline.matcher(cp).replaceAll(Tuils.NEWLINE);

            UIManager.this.updateText(Label.battery, Tuils.span(mContext, cp, color, labelSizes[Label.battery.ordinal()]));
        }

        @Override
        public void onCharging() {
            charging = true;
            update(-1);
        }

        @Override
        public void onNotCharging() {
            charging = false;
            update(-1);
        }
    };

    private StorageRunnable storageRunnable;
    private class StorageRunnable implements Runnable {

        private final String INT_AV = "%iav";
        private final String INT_TOT = "%itot";
        private final String EXT_AV = "%eav";
        private final String EXT_TOT = "%etot";

        private List<Pattern> storagePatterns;
        private String storageFormat;

        int color;

        @Override
        public void run() {
            if(storageFormat == null) {
                storageFormat = SettingsManager.get(Behavior.storage_format);
                color = SettingsManager.getColor(Theme.storage_color);
            }

            if(storagePatterns == null) {
                storagePatterns = new ArrayList<>();

                storagePatterns.add(Pattern.compile(INT_AV + "tb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_AV + "gb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_AV + "mb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_AV + "kb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_AV + "b", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_AV + "%", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));

                storagePatterns.add(Pattern.compile(INT_TOT + "tb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_TOT + "gb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_TOT + "mb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_TOT + "kb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_TOT + "b", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));

                storagePatterns.add(Pattern.compile(EXT_AV + "tb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_AV + "gb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_AV + "mb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_AV + "kb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_AV + "b", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_AV + "%", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));

                storagePatterns.add(Pattern.compile(EXT_TOT + "tb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_TOT + "gb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_TOT + "mb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_TOT + "kb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_TOT + "b", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));

                storagePatterns.add(Tuils.patternNewline);

                storagePatterns.add(Pattern.compile(INT_AV, Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(INT_TOT, Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_AV, Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                storagePatterns.add(Pattern.compile(EXT_TOT, Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
            }

            double iav = Tuils.getAvailableInternalMemorySize(Tuils.BYTE);
            double itot = Tuils.getTotalInternalMemorySize(Tuils.BYTE);
            double eav = Tuils.getAvailableExternalMemorySize(Tuils.BYTE);
            double etot = Tuils.getTotalExternalMemorySize(Tuils.BYTE);

            String copy = storageFormat;

            copy = storagePatterns.get(0).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) iav, Tuils.TERA))));
            copy = storagePatterns.get(1).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) iav, Tuils.GIGA))));
            copy = storagePatterns.get(2).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) iav, Tuils.MEGA))));
            copy = storagePatterns.get(3).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) iav, Tuils.KILO))));
            copy = storagePatterns.get(4).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) iav, Tuils.BYTE))));
            copy = storagePatterns.get(5).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.percentage(iav, itot))));

            copy = storagePatterns.get(6).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) itot, Tuils.TERA))));
            copy = storagePatterns.get(7).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) itot, Tuils.GIGA))));
            copy = storagePatterns.get(8).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) itot, Tuils.MEGA))));
            copy = storagePatterns.get(9).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) itot, Tuils.KILO))));
            copy = storagePatterns.get(10).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) itot, Tuils.BYTE))));

            copy = storagePatterns.get(11).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) eav, Tuils.TERA))));
            copy = storagePatterns.get(12).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) eav, Tuils.GIGA))));
            copy = storagePatterns.get(13).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) eav, Tuils.MEGA))));
            copy = storagePatterns.get(14).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) eav, Tuils.KILO))));
            copy = storagePatterns.get(15).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) eav, Tuils.BYTE))));
            copy = storagePatterns.get(16).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.percentage(eav, etot))));

            copy = storagePatterns.get(17).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) etot, Tuils.TERA))));
            copy = storagePatterns.get(18).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) etot, Tuils.GIGA))));
            copy = storagePatterns.get(19).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) etot, Tuils.MEGA))));
            copy = storagePatterns.get(20).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) etot, Tuils.KILO))));
            copy = storagePatterns.get(21).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) etot, Tuils.BYTE))));

            copy = storagePatterns.get(22).matcher(copy).replaceAll(Matcher.quoteReplacement(Tuils.NEWLINE));

            copy = storagePatterns.get(23).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) iav, Tuils.GIGA))));
            copy = storagePatterns.get(24).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) itot, Tuils.GIGA))));
            copy = storagePatterns.get(25).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) eav, Tuils.GIGA))));
            copy = storagePatterns.get(26).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) etot, Tuils.GIGA))));

            updateText(Label.storage, Tuils.span(mContext, copy, color, labelSizes[Label.storage.ordinal()]));

            handler.postDelayed(this, STORAGE_DELAY);
        }
    };

    private TimeRunnable timeRunnable;
    private class TimeRunnable implements Runnable {

        boolean active;

        @Override
        public void run() {
            if(!active) {
                active = true;
            }

            updateText(Label.time, TimeManager.instance.getCharSequence(mContext, labelSizes[Label.time.ordinal()], "%t0"));
            handler.postDelayed(this, TIME_DELAY);
        }
    };

    private ActivityManager.MemoryInfo memory;
    private ActivityManager activityManager;

    private RamRunnable ramRunnable;
    private class RamRunnable implements Runnable {
        private final String AV = "%av";
        private final String TOT = "%tot";

        List<Pattern> ramPatterns;
        String ramFormat;

        int color;

        @Override
        public void run() {
            if(ramFormat == null) {
                ramFormat = SettingsManager.get(Behavior.ram_format);

                color = SettingsManager.getColor(Theme.ram_color);
            }

            if(ramPatterns == null) {
                ramPatterns = new ArrayList<>();

                ramPatterns.add(Pattern.compile(AV + "tb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(AV + "gb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(AV + "mb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(AV + "kb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(AV + "b", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(AV + "%", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));

                ramPatterns.add(Pattern.compile(TOT + "tb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(TOT + "gb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(TOT + "mb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(TOT + "kb", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));
                ramPatterns.add(Pattern.compile(TOT + "b", Pattern.CASE_INSENSITIVE | Pattern.LITERAL));

                ramPatterns.add(Tuils.patternNewline);
            }

            String copy = ramFormat;

            double av = Tuils.freeRam(activityManager, memory);
            double tot = Tuils.totalRam() * 1024L;

            copy = ramPatterns.get(0).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) av, Tuils.TERA))));
            copy = ramPatterns.get(1).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) av, Tuils.GIGA))));
            copy = ramPatterns.get(2).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) av, Tuils.MEGA))));
            copy = ramPatterns.get(3).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) av, Tuils.KILO))));
            copy = ramPatterns.get(4).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) av, Tuils.BYTE))));
            copy = ramPatterns.get(5).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.percentage(av, tot))));

            copy = ramPatterns.get(6).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) tot, Tuils.TERA))));
            copy = ramPatterns.get(7).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) tot, Tuils.GIGA))));
            copy = ramPatterns.get(8).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) tot, Tuils.MEGA))));
            copy = ramPatterns.get(9).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) tot, Tuils.KILO))));
            copy = ramPatterns.get(10).matcher(copy).replaceAll(Matcher.quoteReplacement(String.valueOf(Tuils.formatSize((long) tot, Tuils.BYTE))));

            copy = ramPatterns.get(11).matcher(copy).replaceAll(Matcher.quoteReplacement(Tuils.NEWLINE));

            updateText(Label.ram, Tuils.span(mContext, copy, color, labelSizes[Label.ram.ordinal()]));

            handler.postDelayed(this, RAM_DELAY);
        }
    };

    private NetworkRunnable networkRunnable;
    private class NetworkRunnable implements Runnable {
//        %() -> wifi
//        %[] -> data
//        %{} -> bluetooth

        final String zero = "0";
        final String one = "1";
        final String on = "on";
        final String off = "off";
        final String ON = on.toUpperCase();
        final String OFF = off.toUpperCase();
        final String _true = "true";
        final String _false = "false";
        final String TRUE = _true.toUpperCase();
        final String FALSE = _false.toUpperCase();

        final Pattern w0 = Pattern.compile("%w0", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern w1 = Pattern.compile("%w1", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern w2 = Pattern.compile("%w2", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern w3 = Pattern.compile("%w3", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern w4 = Pattern.compile("%w4", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern wn = Pattern.compile("%wn", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern d0 = Pattern.compile("%d0", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern d1 = Pattern.compile("%d1", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern d2 = Pattern.compile("%d2", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern d3 = Pattern.compile("%d3", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern d4 = Pattern.compile("%d4", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern b0 = Pattern.compile("%b0", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern b1 = Pattern.compile("%b1", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern b2 = Pattern.compile("%b2", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern b3 = Pattern.compile("%b3", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern b4 = Pattern.compile("%b4", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern ip4 = Pattern.compile("%ip4", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern ip6 = Pattern.compile("%ip6", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
        final Pattern dt = Pattern.compile("%dt", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);

//        final Pattern optionalWifi = Pattern.compile("%\\(([^/]*)/([^)]*)\\)", Pattern.CASE_INSENSITIVE);
//        final Pattern optionalData = Pattern.compile("%\\[([^/]*)/([^\\]]*)\\]", Pattern.CASE_INSENSITIVE);
//        final Pattern optionalBluetooth = Pattern.compile("%\\{([^/]*)/([^}]*)\\}", Pattern.CASE_INSENSITIVE);

        Pattern optionalWifi, optionalData, optionalBluetooth;

        String format, optionalValueSeparator;
        int color;

        WifiManager wifiManager;
        BluetoothAdapter mBluetoothAdapter;

        ConnectivityManager connectivityManager;

        Class cmClass;
        Method method;

        int maxDepth;
        int updateTime;

        @Override
        public void run() {
            if (format == null) {
                format = SettingsManager.get(Behavior.network_info_format);
                color = SettingsManager.getColor(Theme.network_info_color);
                maxDepth = SettingsManager.getInt(Behavior.max_optional_depth);

                updateTime = SettingsManager.getInt(Behavior.network_info_update_ms);
                if (updateTime < 1000)
                    updateTime = Integer.parseInt(Behavior.network_info_update_ms.defaultValue());

                connectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                wifiManager = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

                optionalValueSeparator = "\\" + SettingsManager.get(Behavior.optional_values_separator);

                String wifiRegex = "%\\(([^" + optionalValueSeparator + "]*)" + optionalValueSeparator + "([^)]*)\\)";
                String dataRegex = "%\\[([^" + optionalValueSeparator + "]*)" + optionalValueSeparator + "([^\\]]*)\\]";
                String bluetoothRegex = "%\\{([^" + optionalValueSeparator + "]*)" + optionalValueSeparator + "([^}]*)\\}";

                optionalWifi = Pattern.compile(wifiRegex, Pattern.CASE_INSENSITIVE);
                optionalBluetooth = Pattern.compile(bluetoothRegex, Pattern.CASE_INSENSITIVE);
                optionalData = Pattern.compile(dataRegex, Pattern.CASE_INSENSITIVE);

                try {
                    cmClass = Class.forName(connectivityManager.getClass().getName());
                    method = cmClass.getDeclaredMethod("getMobileDataEnabled");
                    method.setAccessible(true);
                } catch (Exception e) {
                    cmClass = null;
                    method = null;
                }
            }

//            wifi
            boolean wifiOn = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected();
            String wifiName = null;
            if (wifiOn) {
                WifiInfo connectionInfo = wifiManager.getConnectionInfo();
                if (connectionInfo != null) {
                    wifiName = connectionInfo.getSSID();
                }
            }

//            mobile data
            boolean mobileOn = false;
            try {
                mobileOn = method != null && connectivityManager != null && (Boolean) method.invoke(connectivityManager);
            } catch (Exception e) {
            }

            String mobileType = null;
            if (mobileOn) {
                mobileType = Tuils.getNetworkType(mContext);
            } else {
                mobileType = "unknown";
            }

//            bluetooth
            boolean bluetoothOn = mBluetoothAdapter != null && mBluetoothAdapter.isEnabled();

            String copy = format;

            if (maxDepth > 0) {
                copy = apply(1, copy, new boolean[]{wifiOn, mobileOn, bluetoothOn}, optionalWifi, optionalData, optionalBluetooth);
                copy = apply(1, copy, new boolean[]{mobileOn, wifiOn, bluetoothOn}, optionalData, optionalWifi, optionalBluetooth);
                copy = apply(1, copy, new boolean[]{bluetoothOn, wifiOn, mobileOn}, optionalBluetooth, optionalWifi, optionalData);
            }

            copy = w0.matcher(copy).replaceAll(wifiOn ? one : zero);
            copy = w1.matcher(copy).replaceAll(wifiOn ? on : off);
            copy = w2.matcher(copy).replaceAll(wifiOn ? ON : OFF);
            copy = w3.matcher(copy).replaceAll(wifiOn ? _true : _false);
            copy = w4.matcher(copy).replaceAll(wifiOn ? TRUE : FALSE);
            copy = wn.matcher(copy).replaceAll(wifiName != null ? wifiName.replaceAll("\"", Tuils.EMPTYSTRING) : "null");
            copy = d0.matcher(copy).replaceAll(mobileOn ? one : zero);
            copy = d1.matcher(copy).replaceAll(mobileOn ? on : off);
            copy = d2.matcher(copy).replaceAll(mobileOn ? ON : OFF);
            copy = d3.matcher(copy).replaceAll(mobileOn ? _true : _false);
            copy = d4.matcher(copy).replaceAll(mobileOn ? TRUE : FALSE);
            copy = b0.matcher(copy).replaceAll(bluetoothOn ? one : zero);
            copy = b1.matcher(copy).replaceAll(bluetoothOn ? on : off);
            copy = b2.matcher(copy).replaceAll(bluetoothOn ? ON : OFF);
            copy = b3.matcher(copy).replaceAll(bluetoothOn ? _true : _false);
            copy = b4.matcher(copy).replaceAll(bluetoothOn ? TRUE : FALSE);
            copy = ip4.matcher(copy).replaceAll(NetworkUtils.getIPAddress(true));
            copy = ip6.matcher(copy).replaceAll(NetworkUtils.getIPAddress(false));
            copy = dt.matcher(copy).replaceAll(mobileType);
            copy = Tuils.patternNewline.matcher(copy).replaceAll(Tuils.NEWLINE);

            updateText(Label.network, Tuils.span(mContext, copy, color, labelSizes[Label.network.ordinal()]));
            handler.postDelayed(this, updateTime);
        }

        private String apply(int depth, String s, boolean[] on, Pattern... ps) {

            if(ps.length == 0) return s;

            Matcher m = ps[0].matcher(s);
            while (m.find()) {
                if(m.groupCount() < 2) {
                    s = s.replace(m.group(0), Tuils.EMPTYSTRING);
                    continue;
                }

                String g1 = m.group(1);
                String g2 = m.group(2);

                if(depth < maxDepth) {
                    for(int c = 0; c < ps.length - 1; c++) {

                        boolean[] subOn = new boolean[on.length - 1];
                        subOn[0] = on[c+1];

                        Pattern[] subPs = new Pattern[ps.length - 1];
                        subPs[0] = ps[c+1];

                        for(int j = 1, k = 1; j < subOn.length; j++, k++) {
                            if(k == c+1) {
                                j--;
                                continue;
                            }

                            subOn[j] = on[k];
                            subPs[j] = ps[k];
                        }

                        g1 = apply(depth + 1, g1, subOn, subPs);
                        g2 = apply(depth + 1, g2, subOn, subPs);
                    }
                }

                s = s.replace(m.group(0), on[0] ? g1 : g2);
            }

            return s;
        }
    }

    private int weatherDelay;

    private double lastLatitude, lastLongitude;
    private String location;
    private boolean fixedLocation = false;

    private boolean weatherPerformedStartupRun = false;
    private WeatherRunnable weatherRunnable;
    private int weatherColor;
    boolean showWeatherUpdate;

    private class WeatherRunnable implements Runnable {

        String key;
        String url;

        public WeatherRunnable() {

            if(SettingsManager.wasChanged(Behavior.weather_key, false)) {
                weatherDelay = SettingsManager.getInt(Behavior.weather_update_time);
                key = SettingsManager.get(Behavior.weather_key);
            } else {
                key = Behavior.weather_key.defaultValue();
                weatherDelay = 60 * 60;
            }
            weatherDelay *= 1000;

            String where = SettingsManager.get(Behavior.weather_location);
            if(where == null || where.length() == 0 || (!Tuils.isNumber(where) && !where.contains(","))) {
//                Tuils.location(mContext, new Tuils.ArgsRunnable() {
//                    @Override
//                    public void run() {
//                        setUrl(
//                                "lat=" + get(int.class, 0) + "&lon=" + get(int.class, 1),
//                                finalKey,
//                                XMLPrefsManager.get(Behavior.weather_temperature_measure));
//                        WeatherRunnable.this.run();
//                    }
//                }, new Runnable() {
//                    @Override
//                    public void run() {
//                        updateText(Label.weather, Tuils.span(mContext, mContext.getString(R.string.location_error), XMLPrefsManager.getColor(Theme.weather_color), labelSizes[Label.weather.ordinal()]));
//                    }
//                }, handler);

//                Location l = Tuils.getLocation(mContext);
//                if(l != null) {
//                    setUrl(
//                            "lat=" + l.getLatitude() + "&lon=" + l.getLongitude(),
//                            finalKey,
//                            XMLPrefsManager.get(Behavior.weather_temperature_measure));
//                    WeatherRunnable.this.run();
//                } else {
//                    updateText(Label.weather, Tuils.span(mContext, mContext.getString(R.string.location_error), XMLPrefsManager.getColor(Theme.weather_color), labelSizes[Label.weather.ordinal()]));
//                }

                TuiLocationManager l = TuiLocationManager.instance(mContext);
                l.add(ACTION_WEATHER_GOT_LOCATION);

            } else {
                fixedLocation = true;

                if(where.contains(",")) {
                    String[] split = where.split(",");
                    where = "lat=" + split[0] + "&lon=" + split[1];
                } else {
                    where = "id=" + where;
                }

                setUrl(where);
            }
        }

        @Override
        public void run() {
            weatherPerformedStartupRun = true;
            if(!fixedLocation) setUrl(lastLatitude, lastLongitude);

            send();

            if(handler != null) handler.postDelayed(this, weatherDelay);
        }

        private void send() {
            if(url == null) return;

            Intent i = new Intent(HTMLExtractManager.ACTION_WEATHER);
            i.putExtra(SettingsManager.VALUE_ATTRIBUTE, url);
            i.putExtra(HTMLExtractManager.BROADCAST_COUNT, HTMLExtractManager.broadcastCount);
            LocalBroadcastManager.getInstance(mContext.getApplicationContext()).sendBroadcast(i);
        }

        private void setUrl(String where) {
            url = "http://api.openweathermap.org/data/2.5/weather?" + where + "&appid=" + key + "&units=" + SettingsManager.get(Behavior.weather_temperature_measure);
        }

        private void setUrl(double latitude, double longitude) {
            url = "http://api.openweathermap.org/data/2.5/weather?" + "lat=" + latitude + "&lon=" + longitude + "&appid=" + key + "&units=" + SettingsManager.get(Behavior.weather_temperature_measure);
        }
    }

    //    you need to use labelIndexes[i]
    private void updateText(Label l, CharSequence s) {
        labelTexts[l.ordinal()] = s;

        int base = (int) labelIndexes[l.ordinal()];

        List<Float> indexs = new ArrayList<>();
        for(int count = 0; count < Label.values().length; count++) {
            if((int) labelIndexes[count] == base && labelTexts[count] != null) indexs.add(labelIndexes[count]);
        }
//        now I'm sorting the labels on the same line for decimals (2.1, 2.0, ...)
        Collections.sort(indexs);

        CharSequence sequence = "";

        for(int c = 0; c < indexs.size(); c++) {
            float i = indexs.get(c);

            for(int a = 0; a < Label.values().length; a++) {
                if(i == labelIndexes[a] && labelTexts[a] != null) sequence = TextUtils.concat(sequence, labelTexts[a]);
            }
        }

        if(sequence.length() == 0) labelViews[base].setVisibility(View.GONE);
        else {
            labelViews[base].setVisibility(View.VISIBLE);
            labelViews[base].setText(sequence);
        }
    }

    private Handler handler;

    // todo: a single string for all the infos
    public DeviceInfoManager(View rootView) {
        SettingsManager settingsManager = SettingsManager.getInstance();

        labelSizes[Label.time.ordinal()] = SettingsManager.getInt(Ui.time_size);
        labelSizes[Label.ram.ordinal()] = SettingsManager.getInt(Ui.ram_size);
        labelSizes[Label.battery.ordinal()] = SettingsManager.getInt(Ui.battery_size);
        labelSizes[Label.storage.ordinal()] = SettingsManager.getInt(Ui.storage_size);
        labelSizes[Label.network.ordinal()] = SettingsManager.getInt(Ui.network_size);
        labelSizes[Label.notes.ordinal()] = SettingsManager.getInt(Ui.notes_size);
        labelSizes[Label.device.ordinal()] = SettingsManager.getInt(Ui.device_size);
        labelSizes[Label.weather.ordinal()] = SettingsManager.getInt(Ui.weather_size);
        labelSizes[Label.unlock.ordinal()] = SettingsManager.getInt(Ui.unlock_size);

        labelViews = new TextView[]{
                rootView.findViewById(R.id.tv0),
                rootView.findViewById(R.id.tv1),
                rootView.findViewById(R.id.tv2),
                rootView.findViewById(R.id.tv3),
                rootView.findViewById(R.id.tv4),
                rootView.findViewById(R.id.tv5),
                rootView.findViewById(R.id.tv6),
                rootView.findViewById(R.id.tv7),
                rootView.findViewById(R.id.tv8),
        };

        boolean[] show = new boolean[Label.values().length];
        show[Label.notes.ordinal()] = SettingsManager.getBoolean(Ui.show_notes);
        show[Label.ram.ordinal()] = SettingsManager.getBoolean(Ui.show_ram);
        show[Label.device.ordinal()] = SettingsManager.getBoolean(Ui.show_device_name);
        show[Label.time.ordinal()] = SettingsManager.getBoolean(Ui.show_time);
        show[Label.battery.ordinal()] = SettingsManager.getBoolean(Ui.show_battery);
        show[Label.network.ordinal()] = SettingsManager.getBoolean(Ui.show_network_info);
        show[Label.storage.ordinal()] = SettingsManager.getBoolean(Ui.show_storage_info);
        show[Label.weather.ordinal()] = SettingsManager.getBoolean(Ui.show_weather);
        show[Label.unlock.ordinal()] = SettingsManager.getBoolean(Ui.show_unlock_counter);

        float[] indexes = new float[Label.values().length];
        indexes[Label.notes.ordinal()] = show[Label.notes.ordinal()] ? SettingsManager.getFloat(Ui.notes_index) : Integer.MAX_VALUE;
        indexes[Label.ram.ordinal()] = show[Label.ram.ordinal()] ? SettingsManager.getFloat(Ui.ram_index) : Integer.MAX_VALUE;
        indexes[Label.device.ordinal()] = show[Label.device.ordinal()] ? SettingsManager.getFloat(Ui.device_index) : Integer.MAX_VALUE;
        indexes[Label.time.ordinal()] = show[Label.time.ordinal()] ? SettingsManager.getFloat(Ui.time_index) : Integer.MAX_VALUE;
        indexes[Label.battery.ordinal()] = show[Label.battery.ordinal()] ? SettingsManager.getFloat(Ui.battery_index) : Integer.MAX_VALUE;
        indexes[Label.network.ordinal()] = show[Label.network.ordinal()] ? SettingsManager.getFloat(Ui.network_index) : Integer.MAX_VALUE;
        indexes[Label.storage.ordinal()] = show[Label.storage.ordinal()] ? SettingsManager.getFloat(Ui.storage_index) : Integer.MAX_VALUE;
        indexes[Label.weather.ordinal()] = show[Label.weather.ordinal()] ? SettingsManager.getFloat(Ui.weather_index) : Integer.MAX_VALUE;
        indexes[Label.unlock.ordinal()] = show[Label.unlock.ordinal()] ? SettingsManager.getFloat(Ui.unlock_index) : Integer.MAX_VALUE;

        int[] statusLineAlignments = getListOfIntValues(SettingsManager.get(Ui.status_lines_alignment), 9, -1);

        String[] statusLinesBgRectColors = getListOfStringValues(SettingsManager.get(Theme.status_lines_bgrectcolor), 9, "#ff000000");
        String[] bgRectColors = new String[statusLinesBgRectColors.length + otherBgRectColors.length];
        System.arraycopy(statusLinesBgRectColors, 0, bgRectColors, 0, statusLinesBgRectColors.length);

        String[] statusLineBgColors = getListOfStringValues(SettingsManager.get(Theme.status_lines_bg), 9, "#00000000");

        String[] bgColors = new String[statusLineBgColors.length + otherBgColors.length];
        System.arraycopy(statusLineBgColors, 0, bgColors, 0, statusLineBgColors.length);

        String[] statusLineOutlineColors = getListOfStringValues(SettingsManager.get(Theme.status_lines_shadow_color), 9, "#00000000");

        String[] outlineColors = new String[statusLineOutlineColors.length + otherOutlineColors.length];
        System.arraycopy(statusLineOutlineColors, 0, outlineColors, 0, statusLineOutlineColors.length);
        System.arraycopy(otherOutlineColors, 0, outlineColors, 9, otherOutlineColors.length);

        AllowEqualsSequence sequence = new AllowEqualsSequence(indexes, Label.values());

        LinearLayout lViewsParent = (LinearLayout) labelViews[0].getParent();

        int effectiveCount = 0;
        for (int count = 0; count < labelViews.length; count++) {
            labelViews[count].setOnTouchListener(this);

            Object[] os = sequence.get(count);

//            views on the same line
            for (int j = 0; j < os.length; j++) {
//                i is the object gave to the constructor
                int i = ((Label) os[j]).ordinal();
//                v is the adjusted index (2.0, 2.1, 2.2, ...)
                float v = (float) count + ((float) j * 0.1f);

                labelIndexes[i] = v;
            }

            if (count >= sequence.getMinKey() && count <= sequence.getMaxKey() && os.length > 0) {
                labelViews[count].setTypeface(Tuils.getTypeface(context));

                int ec = effectiveCount++;

//                -1 = left     0 = center     1 = right
                int p = statusLineAlignments[ec];
                if (p >= 0)
                    labelViews[count].setGravity(p == 0 ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT);

                if (count != labelIndexes[Label.notes.ordinal()]) {
                    labelViews[count].setVerticalScrollBarEnabled(false);
                }

                applyBgRect(labelViews[count], bgRectColors[count], bgColors[count], margins[0], strokeWidth, cornerRadius);
                applyShadow(labelViews[count], outlineColors[count], shadowXOffset, shadowYOffset, shadowRadius);
            } else {
                lViewsParent.removeView(labelViews[count]);
                labelViews[count] = null;
            }
        }

        if (show[Label.ram.ordinal()]) {
            ramRunnable = new RamRunnable();

            memory = new ActivityManager.MemoryInfo();
            activityManager = (ActivityManager) context.getSystemService(Activity.ACTIVITY_SERVICE);
            handler.post(ramRunnable);
        }

        if (show[Label.storage.ordinal()]) {
            storageRunnable = new StorageRunnable();
            handler.post(storageRunnable);
        }

        if (show[Label.device.ordinal()]) {
            Pattern USERNAME = Pattern.compile("%u", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
            Pattern DV = Pattern.compile("%d", Pattern.CASE_INSENSITIVE | Pattern.LITERAL);

            String deviceFormat = SettingsManager.get(Behavior.device_format);

            String username = SettingsManager.get(Ui.username);
            String deviceName = SettingsManager.get(Ui.deviceName);
            if (deviceName == null || deviceName.length() == 0) {
                deviceName = Build.DEVICE;
            }

            deviceFormat = USERNAME.matcher(deviceFormat).replaceAll(Matcher.quoteReplacement(username != null ? username : "null"));
            deviceFormat = DV.matcher(deviceFormat).replaceAll(Matcher.quoteReplacement(deviceName));
            deviceFormat = Tuils.patternNewline.matcher(deviceFormat).replaceAll(Matcher.quoteReplacement(Tuils.NEWLINE));

            updateText(Label.device, Tuils.span(context, deviceFormat, SettingsManager.getColor(Theme.device_color), labelSizes[Label.device.ordinal()]));
        }

        if (show[Label.time.ordinal()]) {
            timeRunnable = new TimeRunnable();
            handler.post(timeRunnable);
        }

        if (show[Label.battery.ordinal()]) {
            batteryUpdate = new BatteryUpdate();

            mediumPercentage = SettingsManager.getInt(Behavior.battery_medium);
            lowPercentage = SettingsManager.getInt(Behavior.battery_low);

            Tuils.registerBatteryReceiver(context, batteryUpdate);
        } else {
            batteryUpdate = null;
        }

        if (show[Label.network.ordinal()]) {
            networkRunnable = new NetworkRunnable();
            handler.post(networkRunnable);
        }

        final TextView notesView = getLabelView(Label.notes);
        notesManager = new NotesManager(context, notesView);
        if (show[Label.notes.ordinal()]) {
            notesRunnable = new NotesRunnable();
            handler.post(notesRunnable);

            notesView.setMovementMethod(new LinkMovementMethod());

            notesMaxLines = SettingsManager.getInt(Ui.notes_max_lines);
            if (notesMaxLines > 0) {
                notesView.setMaxLines(notesMaxLines);
                notesView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
//                notesView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
//                notesView.setVerticalScrollBarEnabled(true);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB && SettingsManager.getBoolean(Ui.show_scroll_notes_message)) {
                    notesView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {

                        int linesBefore = Integer.MIN_VALUE;

                        @Override
                        public void onGlobalLayout() {
                            if (notesView.getLineCount() > notesMaxLines && linesBefore <= notesMaxLines) {
                                Tuils.sendOutput(Color.RED, context, R.string.note_max_reached);
                            }

                            linesBefore = notesView.getLineCount();
                        }
                    });
                }
            }
        }

        if (show[Label.weather.ordinal()]) {
            weatherRunnable = new WeatherRunnable();

            weatherColor = SettingsManager.getColor(Theme.weather_color);

            String where = SettingsManager.get(Behavior.weather_location);
            if (where.contains(",") || Tuils.isNumber(where)) handler.post(weatherRunnable);

            showWeatherUpdate = SettingsManager.getBoolean(Behavior.show_weather_updates);
        }

        if (show[Label.unlock.ordinal()]) {
            unlockTimes = preferences.getInt(UNLOCK_KEY, 0);

            unlockColor = SettingsManager.getColor(Theme.unlock_counter_color);
            unlockFormat = SettingsManager.get(Behavior.unlock_counter_format);
            notAvailableText = SettingsManager.get(Behavior.not_available_text);
            unlockTimeDivider = SettingsManager.get(Behavior.unlock_time_divider);
            unlockTimeDivider = Tuils.patternNewline.matcher(unlockTimeDivider).replaceAll(Tuils.NEWLINE);

            String start = SettingsManager.get(Behavior.unlock_counter_cycle_start);
            Pattern p = Pattern.compile("(\\d{1,2}).(\\d{1,2})");
            Matcher m = p.matcher(start);
            if (!m.find()) {
                m = p.matcher(Behavior.unlock_counter_cycle_start.defaultValue());
                m.find();
            }

            unlockHour = Integer.parseInt(m.group(1));
            unlockMinute = Integer.parseInt(m.group(2));

            unlockTimeOrder = SettingsManager.getInt(Behavior.unlock_time_order);

            nextUnlockCycleRestart = preferences.getLong(NEXT_UNLOCK_CYCLE_RESTART, 0);
//            Tuils.log("set", nextUnlockCycleRestart);

            m = timePattern.matcher(unlockFormat);
            if (m.find()) {
                String s = m.group(3);
                if (s == null || s.length() == 0) s = "1";

                lastUnlocks = new long[Integer.parseInt(s)];

                Arrays.fill(lastUnlocks, -1);

                registerLockReceiver();
                handler.post(unlockTimeRunnable);
            } else {
                lastUnlocks = null;
            }
        }
    }

    private final long A_DAY = (1000 * 60 * 60 * 24);

    private int unlockColor, unlockTimeOrder;

    private int unlockTimes, unlockHour, unlockMinute, cycleDuration = (int) A_DAY;
    private long lastUnlockTime = -1, nextUnlockCycleRestart;
    private String unlockFormat, notAvailableText, unlockTimeDivider;

    private final int UP_DOWN = 1;

    public static String UNLOCK_KEY = "unlockTimes", NEXT_UNLOCK_CYCLE_RESTART = "nextUnlockRestart";

    //    last unlocks are stored here in this way
//    0 - the first
//    1 - the second
//    2 - ...
    private long[] lastUnlocks;

    private void onUnlock() {
        if (System.currentTimeMillis() - lastUnlockTime < 1000 || lastUnlocks == null) return;
        lastUnlockTime = System.currentTimeMillis();

        unlockTimes++;

        System.arraycopy(lastUnlocks, 0, lastUnlocks, 1, lastUnlocks.length - 1);
        lastUnlocks[0] = lastUnlockTime;

        preferences.edit()
                .putInt(UNLOCK_KEY, unlockTimes)
                .apply();

        invalidateUnlockText();
    }

    final int UNLOCK_RUNNABLE_DELAY = cycleDuration / 24;
    //    this invalidates the text and checks the time values
    Runnable unlockTimeRunnable = new Runnable() {
        @Override
        public void run() {
//            log("run");
            long delay = nextUnlockCycleRestart - System.currentTimeMillis();
//            log("nucr", nextUnlockCycleRestart);
//            log("now", System.currentTimeMillis());
//            log("delay", delay);
            if (delay <= 0) {
                unlockTimes = 0;

                if (lastUnlocks != null) {
                    for (int c = 0; c < lastUnlocks.length; c++) {
                        lastUnlocks[c] = -1;
                    }
                }

                Calendar now = Calendar.getInstance();
//                log("nw", now.toString());

                int hour = now.get(Calendar.HOUR_OF_DAY), minute = now.get(Calendar.MINUTE);
                if (unlockHour < hour || (unlockHour == hour && unlockMinute <= minute)) {
                    now.set(Calendar.DAY_OF_YEAR, now.get(Calendar.DAY_OF_YEAR) + 1);
                }
                Calendar nextRestart = now;
                nextRestart.set(Calendar.HOUR_OF_DAY, unlockHour);
                nextRestart.set(Calendar.MINUTE, unlockMinute);
                nextRestart.set(Calendar.SECOND, 0);
//                log("nr", nextRestart.toString());

                nextUnlockCycleRestart = nextRestart.getTimeInMillis();
//                log("new setted", nextUnlockCycleRestart);

                preferences.edit()
                        .putLong(NEXT_UNLOCK_CYCLE_RESTART, nextUnlockCycleRestart)
                        .putInt(UNLOCK_KEY, 0)
                        .apply();

                delay = nextUnlockCycleRestart - System.currentTimeMillis();
                if (delay < 0) delay = 0;
            }

            invalidateUnlockText();

            delay = Math.min(delay, UNLOCK_RUNNABLE_DELAY);
//            log("with delay", delay);
            handler.postDelayed(this, delay);
        }
    };

    Pattern unlockCount = Pattern.compile("%c", Pattern.CASE_INSENSITIVE);
    Pattern advancement = Pattern.compile("%a(\\d+)(.)");
    //    Pattern timePattern = Pattern.compile("(%t\\d*)(?:\\((?:(\\d+)([^\\)]*))\\)|\\((?:([^\\)]*)(\\d+))\\))?");
    Pattern timePattern = Pattern.compile("(%t\\d*)(?:\\(([^\\)]*)\\))?(\\d+)?");
    Pattern indexPattern = Pattern.compile("%i", Pattern.CASE_INSENSITIVE);
    String whenPattern = "%w";

    private void invalidateUnlockText() {
        String cp = unlockFormat;

        cp = unlockCount.matcher(cp).replaceAll(String.valueOf(unlockTimes));
        cp = patternNewline.matcher(cp).replaceAll(NEWLINE);

        Matcher m = advancement.matcher(cp);
        if (m.find()) {
            int denominator = Integer.parseInt(m.group(1));
            String divider = m.group(2);

            long lastCycleStart = nextUnlockCycleRestart - cycleDuration;

            int elapsed = (int) (System.currentTimeMillis() - lastCycleStart);
            int numerator = denominator * elapsed / cycleDuration;

            cp = m.replaceAll(numerator + divider + denominator);
        }

        CharSequence s = span(context, cp, unlockColor, labelSizes[Label.unlock.ordinal()]);

        Matcher timeMatcher = timePattern.matcher(cp);
        if (timeMatcher.find()) {
            String timeGroup = timeMatcher.group(1);
            String text = timeMatcher.group(2);
            if (text == null) text = whenPattern;

            CharSequence cs = EMPTYSTRING;

            int c, change;
            if (unlockTimeOrder == UP_DOWN) {
                c = 0;
                change = +1;
            } else {
                c = lastUnlocks.length - 1;
                change = -1;
            }

            for (int counter = 0; counter < lastUnlocks.length; counter++, c += change) {
                String t = text;
                t = indexPattern.matcher(t).replaceAll(String.valueOf(c + 1));

                cs = TextUtils.concat(cs, t);

                CharSequence time;
                if (lastUnlocks[c] > 0)
                    time = TimeManager.instance.getCharSequence(timeGroup, lastUnlocks[c]);
                else time = notAvailableText;

                if (time == null) continue;

                cs = TextUtils.replace(cs, new String[]{whenPattern}, new CharSequence[]{time});

                if (counter != lastUnlocks.length - 1) cs = TextUtils.concat(cs, unlockTimeDivider);
            }

            s = TextUtils.replace(s, new String[]{timeMatcher.group(0)}, new CharSequence[]{cs});
        }

        updateText(Label.unlock, s);
    }

    private void registerLockReceiver() {
        if (lockReceiver != null) return;

        final IntentFilter theFilter = new IntentFilter();

        theFilter.addAction(Intent.ACTION_SCREEN_ON);
        theFilter.addAction(Intent.ACTION_SCREEN_OFF);
        theFilter.addAction(Intent.ACTION_USER_PRESENT);

        lockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String strAction = intent.getAction();

                KeyguardManager myKM = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                if (strAction.equals(Intent.ACTION_USER_PRESENT) || strAction.equals(Intent.ACTION_SCREEN_OFF) || strAction.equals(Intent.ACTION_SCREEN_ON))
                    if (myKM.inKeyguardRestrictedInputMode()) onLock();
                    else onUnlock();
            }
        };

        context.getApplicationContext().registerReceiver(lockReceiver, theFilter);
    }

    private void unregisterLockReceiver() {
        if (lockReceiver != null) context.getApplicationContext().unregisterReceiver(lockReceiver);
    }

    private BroadcastReceiver lockReceiver = null;
}