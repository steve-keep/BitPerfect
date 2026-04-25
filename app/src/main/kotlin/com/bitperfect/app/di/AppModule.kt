package com.bitperfect.app.di

import com.bitperfect.app.usb.UsbDriveDetector
import com.bitperfect.core.services.DriveOffsetRepository
import com.bitperfect.core.utils.SettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { SettingsManager(androidContext()) }
    single { DriveOffsetRepository(androidContext()) }
    single { UsbDriveDetector(androidContext()) }
}
