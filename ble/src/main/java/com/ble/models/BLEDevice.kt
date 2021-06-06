package com.ble.models

import android.bluetooth.le.ScanResult

data class BLEDevice(private var scanResult: ScanResult) {

	val device get () = scanResult.device

	val name get () = device.name

	val macAddress get () = device.address

	val rsii get () = scanResult.rssi

	val advertisingId get () = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) scanResult.advertisingSid else -1

	override fun toString(): String = "$name - $macAddress - ${rsii}dBm"

}