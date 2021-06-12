package com.ble.utils

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.ParcelUuid
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.*

fun BluetoothGattCharacteristic.isReadable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

fun BluetoothGattCharacteristic.isNotify(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

fun BluetoothGattCharacteristic.isWritable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
    return properties and property != 0
}

fun String.toUUID() = UUID.fromString(this)

fun String.toParcelUuid(): ParcelUuid = ParcelUuid.fromString(this)


fun Context.doubleButtonAlertDialog(
    titleMessage: String,
    message: String,
    positiveButtonText: String,
    negativeButtonText: String,
    positiveButtonAction: (() -> Unit)? = null,
    negativeButtonAction: (() -> Unit)? = null
): AlertDialog {
    return MaterialAlertDialogBuilder(this).setTitle(titleMessage)
        .setMessage(message)
        .setPositiveButton(positiveButtonText) { dialog, _ ->
            positiveButtonAction?.let {
                it()
            }
        }.setNegativeButton(negativeButtonText) { dialog, _ ->
            negativeButtonAction?.let {
                it()
            }
            dialog.dismiss()
        }
        .setCancelable(false)
        .show()
}