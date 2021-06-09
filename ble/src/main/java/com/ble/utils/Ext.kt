package com.ble.utils

import android.bluetooth.BluetoothGattCharacteristic
import android.os.ParcelUuid
import java.util.*

fun BluetoothGattCharacteristic.isReadable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)
fun BluetoothGattCharacteristic.isNOTIFY(): Boolean =
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
