package com.spirometer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.coroutineScope
import com.ble.BLE
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion


const val SPIROMETER_CHARACTERISTIC = "0000FFE1-0000-1000-8000-00805F9B34FB"
const val TAG = "MyTag"

class MainActivity : AppCompatActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val ble = BLE(this)
        try {
            ble.setup()
        } catch (e: Exception) {
            Log.d("BluetoothMadeEasy", e.message.toString())
        }

        ble.verbose = true

        val devList = ArrayList<BluetoothDevice>()

        lifecycle.coroutineScope.launchWhenStarted {
            val scanFlow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                ble.scan(duration = 5000)
            else
                ble.underLScan(duration = 5000)

            scanFlow.catch { e ->

            }.onCompletion {
                devList.find { it.address.contains("D0:B5:C2:AF") }?.let { btDevice ->
                    /*  ble.connect(btDevice)?.startReadings(SPIROMETER_CHARACTERISTIC)?.collect {
                          Log.e(TAG, it)
                      }*/
                    val connection = ble.connect(btDevice)
                    connection?.readByNotification(SPIROMETER_CHARACTERISTIC)
                    connection?.notificationData?.collect {
                        Log.e(TAG, it?.decodeToString().toString())
                    }
                }
            }.collect {
                Log.e(TAG, "onCreate: ")
                devList.add(it)
            }
        }
    }
}