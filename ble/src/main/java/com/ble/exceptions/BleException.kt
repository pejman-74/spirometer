package com.ble.exceptions


class DisabledAdapterException : Exception("Could not turn bluetooth adapter on!")

class HardwareNotPresentException :
    Exception("Bluetooth and/or Bluetooth Low Energy feature not found!\nDid you forgot to enable it on manifest.xml?")

class ScanFailureException(val code: Int) : Exception("Scan failed to execute!\nError code: $code")


