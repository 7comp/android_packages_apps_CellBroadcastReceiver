/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cellbroadcastreceiver;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.telephony.CellBroadcastMessage;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.telephony.gsm.SmsCbConstants;

import static com.android.cellbroadcastreceiver.CellBroadcastReceiver.DBG;

/**
 * This service manages enabling and disabling ranges of message identifiers
 * that the radio should listen for. It operates independently of the other
 * services and runs at boot time and after exiting airplane mode.
 *
 * Note that the entire range of emergency channels is enabled. Test messages
 * and lower priority broadcasts are filtered out in CellBroadcastAlertService
 * if the user has not enabled them in settings.
 *
 * TODO: add notification to re-enable channels after a radio reset.
 */
public class CellBroadcastConfigService extends IntentService {
    private static final String TAG = "CellBroadcastConfigService";

    static final String ACTION_ENABLE_CHANNELS = "ACTION_ENABLE_CHANNELS";

    static final String EMERGENCY_BROADCAST_RANGE_GSM =
            "ro.cb.gsm.emergencyids";

    public CellBroadcastConfigService() {
        super(TAG);          // use class name for worker thread name
    }

    private static void setChannelRange(SmsManager manager, String ranges, boolean enable) {
        if (DBG)log("setChannelRange: " + ranges);

        try {
            for (String channelRange : ranges.split(",")) {
                int dashIndex = channelRange.indexOf('-');
                if (dashIndex != -1) {
                    int startId = Integer.decode(channelRange.substring(0, dashIndex).trim());
                    int endId = Integer.decode(channelRange.substring(dashIndex + 1).trim());
                    if (enable) {
                        if (DBG) log("enabling emergency IDs " + startId + '-' + endId);
                        manager.enableCellBroadcastRange(startId, endId);
                    } else {
                        if (DBG) log("disabling emergency IDs " + startId + '-' + endId);
                        manager.disableCellBroadcastRange(startId, endId);
                    }
                } else {
                    int messageId = Integer.decode(channelRange.trim());
                    if (enable) {
                        if (DBG) log("enabling emergency message ID " + messageId);
                        manager.enableCellBroadcast(messageId);
                    } else {
                        if (DBG) log("disabling emergency message ID " + messageId);
                        manager.disableCellBroadcast(messageId);
                    }
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Number Format Exception parsing emergency channel range", e);
        }

        // Make sure CMAS Presidential is enabled (See 3GPP TS 22.268 Section 6.2).
        if (DBG) log("setChannelRange: enabling CMAS Presidential");
        if (CellBroadcastReceiver.phoneIsCdma()) {
            manager.enableCellBroadcast(SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT);
        } else {
            manager.enableCellBroadcast(SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL);
        }
    }

    /**
     * Returns true if this is a standard or operator-defined emergency alert message.
     * This includes all ETWS and CMAS alerts, except for AMBER alerts.
     * @param message the message to test
     * @return true if the message is an emergency alert; false otherwise
     */
    static boolean isEmergencyAlertMessage(CellBroadcastMessage message) {
        if (message.isEmergencyAlertMessage()) {
            return true;
        }

        // Check for system property defining the emergency channel ranges to enable
        String emergencyIdRange = (CellBroadcastReceiver.phoneIsCdma()) ?
                "" : SystemProperties.get(EMERGENCY_BROADCAST_RANGE_GSM);

        if (TextUtils.isEmpty(emergencyIdRange)) {
            return false;
        }
        try {
            int messageId = message.getServiceCategory();
            for (String channelRange : emergencyIdRange.split(",")) {
                int dashIndex = channelRange.indexOf('-');
                if (dashIndex != -1) {
                    int startId = Integer.decode(channelRange.substring(0, dashIndex).trim());
                    int endId = Integer.decode(channelRange.substring(dashIndex + 1).trim());
                    if (messageId >= startId && messageId <= endId) {
                        return true;
                    }
                } else {
                    int emergencyMessageId = Integer.decode(channelRange.trim());
                    if (emergencyMessageId == messageId) {
                        return true;
                    }
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Number Format Exception parsing emergency channel range", e);
        }
        return false;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (ACTION_ENABLE_CHANNELS.equals(intent.getAction())) {
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                Resources res = getResources();
                boolean isCdma = CellBroadcastReceiver.phoneIsCdma();

                // Check for system property defining the emergency channel ranges to enable
                String emergencyIdRange = isCdma ?
                        "" : SystemProperties.get(EMERGENCY_BROADCAST_RANGE_GSM);

                boolean enableEmergencyAlerts = prefs.getBoolean(
                        CellBroadcastSettings.KEY_ENABLE_EMERGENCY_ALERTS, true);

                TelephonyManager tm = (TelephonyManager) getSystemService(
                        Context.TELEPHONY_SERVICE);

                boolean enableChannel50Support = res.getBoolean(R.bool.show_brazil_settings) ||
                        "br".equals(tm.getSimCountryIso());

                boolean enableChannel50Alerts = enableChannel50Support &&
                        prefs.getBoolean(CellBroadcastSettings.KEY_ENABLE_CHANNEL_50_ALERTS, true);

                SmsManager manager = SmsManager.getDefault();
                if (enableEmergencyAlerts) {
                    if (DBG) log("enabling emergency cell broadcast channels");
                    if (!TextUtils.isEmpty(emergencyIdRange)) {
                        setChannelRange(manager, emergencyIdRange, true);
                    } else if (isCdma){
                        // No emergency channel system property, enable all emergency channels
                        manager.enableCellBroadcastRange(
                                SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT,
                                SmsEnvelope.SERVICE_CATEGORY_CMAS_TEST_MESSAGE);

                        // CMAS Presidential must be on.
                        manager.enableCellBroadcast(
                                SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT);
                    } else {
                        // No emergency channel system property, enable all emergency channels
                        manager.enableCellBroadcastRange(
                                SmsCbConstants.MESSAGE_ID_PWS_FIRST_IDENTIFIER,
                                SmsCbConstants.MESSAGE_ID_PWS_LAST_IDENTIFIER);

                        // CMAS Presidential must be on (See 3GPP TS 22.268 Section 6.2).
                        manager.enableCellBroadcast(
                               SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL);
                    }
                    if (DBG) log("enabled emergency cell broadcast channels");
                } else {
                    // we may have enabled these channels previously, so try to disable them
                    if (DBG) log("disabling emergency cell broadcast channels");
                    if (!TextUtils.isEmpty(emergencyIdRange)) {
                        setChannelRange(manager, emergencyIdRange, false);
                    } else if (isCdma) {
                        // No emergency channel system property, disable all emergency channels
                        manager.disableCellBroadcastRange(
                                SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT,
                                SmsEnvelope.SERVICE_CATEGORY_CMAS_TEST_MESSAGE);

                        // CMAS Presidential must be on.
                        manager.enableCellBroadcast(
                                SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT);
                    } else {
                        // No emergency channel system property, disable all emergency channels
                        // except for CMAS Presidential (See 3GPP TS 22.268 Section 6.2)
                        manager.disableCellBroadcastRange(
                                SmsCbConstants.MESSAGE_ID_PWS_FIRST_IDENTIFIER,
                                SmsCbConstants.MESSAGE_ID_PWS_LAST_IDENTIFIER);

                        manager.enableCellBroadcast(
                                SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL);
                    }
                    if (DBG) log("disabled emergency cell broadcast channels");
                }

                if (isCdma) {
                    if (DBG) log("channel 50 is not aplicable for cdma");
                } else if (enableChannel50Alerts) {
                    if (DBG) log("enabling cell broadcast channel 50");
                    manager.enableCellBroadcast(50);
                    if (DBG) log("enabled cell broadcast channel 50");
                } else {
                    if (DBG) log("disabling cell broadcast channel 50");
                    manager.disableCellBroadcast(50);
                    if (DBG) log("disabled cell broadcast channel 50");
                }
            } catch (Exception ex) {
                Log.e(TAG, "exception enabling cell broadcast channels", ex);
            }
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
