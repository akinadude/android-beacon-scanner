package com.bridou_n.beaconscanner

import android.content.Intent
import android.util.Log.ERROR
import androidx.multidex.MultiDexApplication
import com.bridou_n.beaconscanner.dagger.AppComponent
import com.bridou_n.beaconscanner.dagger.ContextModule
import com.bridou_n.beaconscanner.dagger.DaggerAppComponent
import com.bridou_n.beaconscanner.features.settings.SettingsActivity
import com.bridou_n.beaconscanner.utils.BuildTypes
import com.crashlytics.android.Crashlytics
import com.google.firebase.analytics.FirebaseAnalytics
import org.altbeacon.beacon.Region
import org.altbeacon.beacon.startup.BootstrapNotifier
import org.altbeacon.beacon.startup.RegionBootstrap
import timber.log.Timber
import javax.inject.Inject

/**
 * Created by bridou_n on 30/09/2016.
 */

class AppSingleton : MultiDexApplication()/*, BootstrapNotifier*/ {

    private val TAG = ".MyAppName"
    private val appPackageName = "com.bridou_n.beaconscanner"
    //private lateinit var regionBootstrap: RegionBootstrap

    companion object {
        lateinit var appComponent: AppComponent
    }

    @Inject lateinit var tracker: FirebaseAnalytics

    override fun onCreate() {
        super.onCreate()

        // Dagger
        appComponent = DaggerAppComponent.builder()
                .contextModule(ContextModule(this))
                .build()
        appComponent.inject(this)

        // Timber
        Timber.plant(CrashReportingTree())

        // Analytics
        tracker.setAnalyticsCollectionEnabled(BuildTypes.isRelease())

        /*Timber.d("App started up")
        //val beaconManager = BeaconManager.getInstanceForApplication(this)
        val region = Region("com.bridou_n.beaconscanner", null, null, null)
        regionBootstrap = RegionBootstrap(this, region)*/
    }

    /*override fun didDetermineStateForRegion(state: Int, region: Region?) {
        Timber.d("Got a didDetermineStateForRegion call, state: $state, region: $region")
        val intent = Intent(this, SettingsActivity::class.java)
        // IMPORTANT: in the AndroidManifest.xml definition of this activity, you must set android:launchMode="singleInstance" or you will get two instances
        // created when a user launches the activity manually and it gets launched from here.
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        this.startActivity(intent)
    }

    override fun didEnterRegion(region: Region?) {
        Timber.d("Got a didEnterRegion call")
        // This call to disable will make the activity below only gets launched the first time a beacon is seen (until the next time the app is launched)
        // if you want the Activity to launch every single time a beacon come into view, remove this call.
        //regionBootstrap.disable()
    }

    override fun didExitRegion(region: Region?) {
        Timber.d("Got a didExitRegion call")
    }*/
}

/** A tree which logs important information for crash reporting.  */
class CrashReportingTree : Timber.DebugTree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, message, t) // Do the regular timber debug

        Crashlytics.log(priority, tag, message)

        t?.let {
            if (priority == ERROR) {
                Crashlytics.logException(t)
            }
        }
    }
}