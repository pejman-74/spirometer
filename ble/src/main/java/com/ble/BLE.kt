package com.ble

import android.Manifest.permission
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresFeature
import androidx.annotation.RequiresPermission
import com.ble.exceptions.*
import com.ble.models.BLEDevice
import com.ble.utils.PermissionUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.*
import kotlin.coroutines.resume

internal const val DEFAULT_TIMEOUT = 10000L

class BLE(private var context: Context) {

    /* Bluetooth related variables */
    private var manager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null

    /* Scan related variables */
    private val defaultScanSettings by lazy {
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                //.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)

        builder.build()
    }
    var isScanRunning = false
        private set

    /***
     * Indicates whether additional information should be logged
     ***/
    var verbose = false


    /***
     * Instantiates a new Bluetooth scanner instance
     *
     * @throws HardwareNotPresentException If no hardware is present on the running device
     ***/
    fun setup() {
        verifyBluetoothHardwareFeature()
        manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager?.adapter
        scanner = adapter?.bluetoothLeScanner
    }


    private fun verifyBluetoothHardwareFeature() {
        log("Checking bluetooth hardware on device...")

        context.packageManager.let {
            if (!PermissionUtils.isBluetoothLowEnergyPresentOnDevice(it) || !PermissionUtils.isBluetoothPresentOnDevice(
                    it
                )
            ) {
                log("No bluetooth hardware detected on this device!")
                throw HardwareNotPresentException()
            } else {
                log("Detected bluetooth hardware on this device!")
            }
        }
    }


    /**
     * Checks if the bluetooth adapter is active
     *
     * If not, automatically requests it's activation to the user
     *
     * @see verifyBluetoothAdapterState For a variation using callbacks
     *
     * @return True when the bluetooth adapter is active
     ***/
    @RequiresPermission(permission.BLUETOOTH_ADMIN)
    suspend fun verifyBluetoothAdapterState(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (adapter == null || adapter?.isEnabled == false) {
                log("Bluetooth adapter turned off!")
                continuation.cancel(DisabledAdapterException())
            } else continuation.resume(true)

        }
    }


    /***
     * Starts a scan for bluetooth devices
     * Only runs with a [duration] defined
     *
     * If only one device is required consider using [scanFor]
     *
     * @see scan For a variation using callbacks
     *
     * @param filters Used to specify attributes of the devices on the scan
     * @param settings Native object to specify the scan settings (The default setting is only recommended for really fast scans)
     * @param duration Scan time limit, when exceeded stops the scan <b>(Ignored when less then 0)</b>
     *
     * @throws IllegalArgumentException When a duration is not defined
     * @throws ScanFailureException When an error occurs
     *
     * @return An Array of Bluetooth devices found
     ***/
    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(permission.BLUETOOTH_ADMIN)
    fun scan(
        filters: List<ScanFilter>? = null,
        settings: ScanSettings? = null,
        duration: Long = DEFAULT_TIMEOUT
    ) = callbackFlow<BLEDevice> {


        // Validates the duration
        if (duration <= 0)
            close(IllegalArgumentException("In order to run a synchronous scan you'll need to specify a duration greater than 0ms!"))

        if (scanner == null)
            close(Exception("should call setup() first!"))

        log("Starting scan...")

        val scanCallbackInstance = object : ScanCallback() {

            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                // Gets the device from the result
                result?.device?.let { device ->
                    log("Scan result! ${device.name} (${device.address}) ${result.rssi}dBm")
                    offer(BLEDevice(result))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                log("Scan failed! $errorCode")
                // Calls the error callback
                close(ScanFailureException(errorCode))
            }

        }

        // Starts the scan
        isScanRunning = true

        scanner?.startScan(filters, settings ?: defaultScanSettings, scanCallbackInstance)

        // Automatically stops the scan if a duration is specified
        if (duration > 0) {
            log("Scan timeout reached!")

            // Waits for the specified timeout
            delay(duration)

            close()
        } else {
            log("Skipped timeout definition on scan!")
        }
        awaitClose { stopScan(scanCallbackInstance) }
    }


    /***
     * Stops the scan started by [scan]
     ***/
    fun stopScan(scanCallbackInstance: ScanCallback) {
        log("Stopping scan...")

        if (!isScanRunning) return

        isScanRunning = false
        scanner?.stopScan(scanCallbackInstance)
    }

    // region Utility methods
    private fun log(message: String) {
        if (verbose) Log.d("BluetoothMadeEasy", message)
    }


    /***
     * Establishes a connection with the specified bluetooth device
     *
     * @param device The device to be connected with
     *
     * @return A nullable [BluetoothConnection], null when not successful
     ***/
    suspend fun connect(device: BluetoothDevice): BluetoothConnection? {
        return suspendCancellableCoroutine { continuation ->
            log("Trying to establish a conecttion with device ${device.address}...")

            // Establishes a bluetooth connection to the specified device
            val connection = BluetoothConnection(device)
            connection.verbose = verbose
            connection.establish(context) { successful ->
                if (successful) {
                    log("Connected successfully with ${device.address}!")
                    continuation.resume(connection)
                } else {
                    log("Could not connect with ${device.address}")
                    continuation.resume(null)
                }
            }
        }
    }

    /***
     * Establishes a connection with the specified bluetooth device
     *
     * @param device The device to be connected with
     *
     * @return A nullable [BluetoothConnection], null when not successful
     ***/
    suspend fun connect(device: BLEDevice): BluetoothConnection? = connect(device.device)

}