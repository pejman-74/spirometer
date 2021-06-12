package com.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.util.Log
import com.ble.typealiases.Callback
import com.ble.typealiases.EmptyCallback
import com.ble.utils.isNotify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.nio.charset.Charset
import java.util.*


@Suppress("unused")
class BluetoothConnection(private val device: BluetoothDevice) {


    /* Bluetooth */
    private var bluetoothGatt: BluetoothGatt? = null
    private var closingConnection: Boolean = false
    private var connectionActive: Boolean = false

    /* Callbacks */
    private var connectionCallback: Callback<Boolean>? = null

    val notificationData = MutableStateFlow<ByteArray?>(null)

    /***
     * Indicates whether additional information should be logged
     ***/
    var verbose: Boolean = false

    /***
     * Called whenever a successful connection is established
     ***/
    var onConnect: EmptyCallback? = null

    /***
     * Called whenever a connection is lost
     ***/
    var onDisconnect: EmptyCallback? = null

    /***
     * Indicates whether the connection is active
     ***/
    val isActive get() = this.connectionActive

    /***
     * Indicates the connection signal strength (dBm)
     ***/
    var rsii: Int = 0
        private set

    private fun log(message: String) {
        if (this.verbose) Log.d("BluetoothConnection", message)
    }


    private fun setupGattCallback(): BluetoothGattCallback {
        return object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("Device ${device.address} connected!")

                    // Notifies that the connection has been established
                    if (!connectionActive) {
                        onConnect?.invoke()
                        connectionActive = true
                    }

                    // Starts the services discovery
                    this@BluetoothConnection.bluetoothGatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status == 133) {// HACK: Multiple reconnections handler
                        log("Found 133 connection failure! Reconnecting GATT...")
                    } else if (closingConnection) {// HACK: Workaround for Lollipop 21 and 22
                        endDisconnection()
                    } else {
                        // Notifies that the connection has been lost
                        if (connectionActive) {
                            log("Lost connection with ${device.address}")
                            onDisconnect?.invoke()
                            connectionActive = false
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    closingConnection = false
                    connectionCallback?.invoke(true)
                } else {
                    Log.e(
                        "BluetoothConnection",
                        "Error while discovering services at ${device.address}! Status: $status"
                    )
                    startDisconnection()
                    connectionCallback?.invoke(false)
                }

            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                if (characteristic?.isNotify() == true) {
                    val data = characteristic.value
                    notificationData.value = data
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                super.onReadRemoteRssi(gatt, rssi, status)

                // Update the internal rsii variable
                if (status == BluetoothGatt.GATT_SUCCESS) this@BluetoothConnection.rsii = rsii
            }
        }
    }

    private fun getCharacteristic(
        gatt: BluetoothGatt,
        characteristic: String
    ): BluetoothGattCharacteristic? {
        // Converts the specified string into an UUID
        val characteristicUuid = UUID.fromString(characteristic)

        // Iterates trough every service on the gatt
        gatt.services?.forEach { service ->
            // Iterate trough every characteristic on the service
            service.characteristics.forEach { characteristic ->
                // If matches the uuid, then return it
                if (characteristic.uuid == characteristicUuid) return characteristic
            }
        }

        return null
    }


    /***
     * Performs a write operation on a specific characteristic
     *
     * @see [write] For a variant that receives a [String] value
     *
     * @param characteristic The uuid of the target characteristic
     *
     * @return True when successfully written the specified value
     ***/
    fun write(characteristic: String, message: ByteArray): Boolean {
        // TODO: Add reliable writing implementation
        this.log("Writing to device ${device.address} (${message.size} bytes)")

        val characteristicUuid = UUID.fromString(characteristic)

        bluetoothGatt?.let { gatt ->
            gatt.services?.forEach { service ->
                service.characteristics.forEach { characteristic ->
                    if (characteristic.uuid == characteristicUuid) {
                        characteristic.value = message
                        return gatt.writeCharacteristic(characteristic)
                    }
                }
            }
        }

        Log.e(
            "BluetoothConnection",
            "Characteristic $characteristic not found on device ${device.address}!"
        )
        return false
    }

    /***
     * Performs a write operation on a specific characteristic
     *
     * @see [write] For a variant that receives a [ByteArray] value
     *
     * @param characteristic The uuid of the target characteristic
     *
     * @return True when successfully written the specified value
     ***/
    fun write(characteristic: String, message: String, charset: Charset = Charsets.UTF_8): Boolean =
        this.write(characteristic, message.toByteArray(charset))

    fun setNotification(uuid: String) {
        bluetoothGatt?.let { gatt ->
            val characteristic = getCharacteristic(gatt, uuid)
            log("can not get characteristic")
            characteristic ?: return
            if (!characteristic.isNotify()) {
                log("characteristic is not notifiable!")
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)

            characteristic.descriptors.forEach { desc ->
                desc.let {
                    it?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
            }

        }

    }

    /***
     * Performs a read operation on a specific characteristic
     *
     * @see [read] For a variant that returns a [String] value
     *
     * @param uuid The uuid of the target characteristic
     *
     * @return A nullable [ByteArray], null when failed to read
     ***/
    fun read(uuid: String): ByteArray? {

        // Null safe let of the generic attribute profile
        bluetoothGatt?.let { gatt ->
            // Searches for the characteristic
            val characteristic = getCharacteristic(gatt, uuid)

            if (characteristic != null) {
                // Tries to read its value, if successful return it
                if (gatt.readCharacteristic(characteristic)) {
                    return characteristic.value
                } else {
                    Log.e(
                        "BluetoothConnection",
                        "Failed to read characteristic $characteristic on device ${device.address}"
                    )
                }
            } else {
                Log.e(
                    "BluetoothConnection",
                    "Characteristic $characteristic not found on device ${device.address}!"
                )
            }
        }

        return null
    }

    fun startReadings(characteristic: String, pollingRate: Long = 1000L) = flow {
        var lastValue: String? = null
        while (true) {
            // Reads the characteristic from the device
            val currentValue = read(characteristic, Charsets.UTF_8)
            // Check if it has changed
            if (lastValue != currentValue) {
                currentValue?.let { emit(it) }

                // Update the lastValue to reflect the changes
                lastValue = currentValue
            }

            // Waits until the next reading
            delay(pollingRate)
        }

    }

    /***
     * Performs a read operation on a specific characteristic
     *
     * @see [read] For a variant that returns a [ByteArray] value
     *
     * @param uuid The uuid of the target characteristic
     *
     * @return A nullable [String], null when failed to read
     ***/
    fun read(uuid: String, charset: Charset = Charsets.UTF_8): String? =
        this.read(uuid)?.let { String(it, charset) }


    private fun startDisconnection() {
        try {
            closingConnection = true
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun endDisconnection() {
        log("Disconnected successfully from ${device.address}!\nClosing connection...")

        try {
            connectionActive = false
            connectionCallback?.invoke(false)
            onDisconnect?.invoke()
            bluetoothGatt?.close()
            bluetoothGatt = null
            closingConnection = false
        } catch (e: Exception) {
            log("Ignoring closing connection with ${device.address} exception -> ${e.message}")
        }
    }

    fun establish(context: Context, callback: Callback<Boolean>) {
        connectionCallback = callback

        bluetoothGatt = if (VERSION.SDK_INT < VERSION_CODES.M)
            device.connectGatt(context, false, setupGattCallback())
        else
            device.connectGatt(context, false, setupGattCallback(), BluetoothDevice.TRANSPORT_LE)

    }


}