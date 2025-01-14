/*
 * Copyright (C) 2019 CypherOS
 * Copyright (C) 2014-2020 Paranoid Android
 * Copyright (C) 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yaap.device.DeviceSettings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.UserHandle;
import android.view.View
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class AlertSliderPlugin : OverlayPlugin {
    private lateinit var pluginContext: Context
    private lateinit var handler: NotificationHandler
    private lateinit var ambientConfig: AmbientDisplayConfiguration

    private data class NotificationInfo(
        val position: Int,
        val mode: Int,
    )

    private val updateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.SLIDER_UPDATE_ACTION -> {
                    val ringer = intent.getIntExtra("mode", NONE)
                        .takeIf { it != NONE } ?: return

                    handler.obtainMessage(
                        MSG_DIALOG_UPDATE, NotificationInfo(
                            intent.getIntExtra("position", Constants.POSITION_BOTTOM),
                            ringer
                        )
                    ).sendToTarget()
                    handler.sendEmptyMessage(MSG_DIALOG_SHOW)
                }
            }
        }
    }

    override fun onCreate(context: Context, plugin: Context) {
        pluginContext = plugin
        handler = NotificationHandler(plugin)
        ambientConfig = AmbientDisplayConfiguration(context)

        plugin.registerReceiver(updateReceiver, IntentFilter(Constants.SLIDER_UPDATE_ACTION))
    }

    override fun onDestroy() {
        pluginContext.unregisterReceiver(updateReceiver)
    }

    override fun setup(statusBar: View, navBar: View) {}

    private inner class NotificationHandler(private val context: Context) : Handler(Looper.getMainLooper()) {
        private var dialog = AlertSliderDialog(context)
        private var currUIMode = context.getResources().getConfiguration().uiMode
        private var showing = false
            set(value) {
                if (field != value) {
                    // Remove pending messages
                    removeMessages(MSG_DIALOG_SHOW)
                    removeMessages(MSG_DIALOG_DISMISS)

                    // Show/hide dialog
                    if (value) {
                        handleResetTimeout()
                        handleDoze()
                        dialog.show()
                    } else {
                        dialog.dismiss()
                    }
                }

                field = value
            }

        override fun handleMessage(msg: Message) = when (msg.what) {
            MSG_DIALOG_SHOW -> handleShow()
            MSG_DIALOG_DISMISS -> handleDismiss()
            MSG_DIALOG_RESET -> handleResetTimeout()
            MSG_DIALOG_UPDATE -> handleUpdate(msg.obj as NotificationInfo)
            else -> {}
        }

        private fun handleShow() {
            showing = true
        }

        private fun handleDismiss() {
            showing = false
        }

        private fun handleResetTimeout() {
            removeMessages(MSG_DIALOG_DISMISS)
            sendMessageDelayed(
                handler.obtainMessage(MSG_DIALOG_DISMISS, MSG_DIALOG_RESET, 0), DIALOG_TIMEOUT
            )
        }

        private fun handleUpdate(info: NotificationInfo) {
            if (maybeRemake()) {
                handleShow()
                return
            }
            handleDoze()
            dialog.setState(info.position, info.mode)
        }

        private fun handleDoze() {
            if (!ambientConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT))
                return
            val intent = Intent("com.android.systemui.doze.pulse")
            context.sendBroadcastAsUser(intent, UserHandle.CURRENT)
        }

        private fun maybeRemake(): Boolean {
            // Remake if theme changed
            val uiMode = context.getResources().getConfiguration().uiMode
            if (uiMode != currUIMode) {
                currUIMode = uiMode
                // Remove pending messages
                removeMessages(MSG_DIALOG_SHOW)
                removeMessages(MSG_DIALOG_DISMISS)
                dialog.dismiss()
                dialog = AlertSliderDialog(context)
                return true
            }
            return false
        }
    }

    companion object {
        private const val TAG = "AlertSliderPlugin"

        // Handler
        private const val MSG_DIALOG_SHOW = 1
        private const val MSG_DIALOG_DISMISS = 2
        private const val MSG_DIALOG_RESET = 3
        private const val MSG_DIALOG_UPDATE = 4
        private const val DIALOG_TIMEOUT = 3000L

        // Ringer mode
        private const val NONE = -1
    }
}
