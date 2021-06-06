package com.spirometer

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.coroutineScope
import com.ble.BLE
import com.ble.models.BLEDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion

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
        lifecycle.coroutineScope.launchWhenCreated {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {


            }
        }

        val devList = ArrayList<BLEDevice>()
        lifecycle.coroutineScope.launchWhenStarted {

            ble.scan(duration = 5000).catch { e ->
                Log.v("BluetoothMadeEasy", e.message + "00")
            }.onCompletion { e ->
                Log.v("BluetoothMadeEasy", e?.message + "11")
                devList.lastOrNull()?.let {
                    ble.connect(it)
                }
            }.collect {
                devList.add(it)
            }
        }
    }
}