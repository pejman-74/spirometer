package com.ble.typealiases

internal typealias EmptyCallback = () -> Unit

internal typealias Callback <T> = (T) -> Unit

internal typealias PermissionRequestCallback = (granted: Boolean) -> Unit
