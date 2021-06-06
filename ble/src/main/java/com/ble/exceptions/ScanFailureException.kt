package com.ble.exceptions

class ScanFailureException(val code: Int) : Exception("Scan failed to execute!\nError code: $code")