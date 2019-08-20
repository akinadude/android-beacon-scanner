package com.bridou_n.beaconscanner.features.beaconList

import android.os.RemoteException
import androidx.room.EmptyResultSetException
import com.bridou_n.beaconscanner.API.LoggingService
import com.bridou_n.beaconscanner.Database.AppDatabase
import com.bridou_n.beaconscanner.models.BeaconSaved
import com.bridou_n.beaconscanner.models.LoggingRequest
import com.bridou_n.beaconscanner.utils.BluetoothManager
import com.bridou_n.beaconscanner.utils.PreferencesHelper
import com.bridou_n.beaconscanner.utils.extensionFunctions.log
import com.google.firebase.analytics.FirebaseAnalytics
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.Region
import timber.log.Timber
import java.util.*


/**
 * Created by bridou_n on 22/08/2017.
 */

class BeaconListPresenter(val view: BeaconListContract.View,
                          val prefs: PreferencesHelper,
                          val db: AppDatabase,
                          val loggingService: LoggingService,
                          val bluetoothState: BluetoothManager,
                          val tracker: FirebaseAnalytics) : BeaconListContract.Presenter {

    private var bluetoothStateDisposable: Disposable? = null
    private var beaconManager: BeaconManager? = null
    private var listQuery: Disposable? = null

    private var numberOfScansSinceLog = 0
    private var loggingRequests = CompositeDisposable()

    private var isScanning = false

    override fun setBeaconManager(bm: BeaconManager) {
        beaconManager = bm
    }

    override fun start() {
        // Setup an observable on the bluetooth changes
        bluetoothStateDisposable = bluetoothState.asFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { newState ->
                    view.updateBluetoothState(newState, bluetoothState.isEnabled())

                    if (newState == BeaconListActivity.BluetoothState.STATE_OFF) {
                        stopScan()
                    }
                }

        listQuery = db.beaconsDao().getBeacons(blocked = false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { list ->
                    Timber.d("list: $list")

                    view.showEmptyView(list.size == 0)
                    view.submitData(list)
                }

        // Show the tutorial if needed
        if (!prefs.hasSeenTutorial()) {
            prefs.setHasSeenTutorial(view.showTutorial())
        }

        // Start scanning if the scan on open is activated or if we were previously scanning
        if (prefs.isScanOnOpen || prefs.wasScanning()) {
            isScanning = true
            startScan()
        }
    }

    //todo BootstrapNotifier. What is that?
    //todo RangeNotifier
    // MonitorNotifier
    // BackgroundPowerSaver

    //todo JobScheduler is used internally.

    //todo need to grasp the idea how scanning and finding a beacon is performed.

    //todo background service and foreground service.
    //todo is there ability to have constantly working service with small power consumption?
    //todo what is the best for scheduled work — work manager of service?

    //todo try example code with BootstrapNotifier.

    //==========================================================

    //todo read docs https://altbeacon.github.io/android-beacon-library/documentation.html
    // good to know if an app can be started by `some entity` when it is dropped from memory.

    //todo wake the app when it is dropped from memory.
    // It must be some system service that sends global event that app can catch and react.

    //todo watch on service in ooma app that works with location update, change wi-fi, and activity recognition.

    //================================

    //todo is there any broadcast receiver event that triggers when a beacon is detected through bluetooth connection.

    override fun toggleScan() {
        if (!isScanning()) {
            tracker.logEvent("start_scanning_clicked", null)
            return startScan()
        }
        tracker.logEvent("stop_scanning_clicked", null)
        stopScan()
    }

    override fun startScan() {
        if (!view.hasCoarseLocationPermission()) {
            return view.askForCoarseLocationPermission()
        }

        if (!bluetoothState.isEnabled() || beaconManager == null) {
            return view.showBluetoothNotEnabledError()
        }

        if (!(beaconManager?.isBound(view) ?: false)) {
            Timber.d("binding beaconManager")
            beaconManager?.bind(view)
        }

        if (prefs.preventSleep) {
            view.keepScreenOn(true)
        }

        view.showScanningState(true)
        isScanning = true
    }

    //todo Does it use a service (android app component) inside?
    override fun onBeaconServiceConnect() {
        Timber.d("beaconManager is bound, ready to start scanning")
        beaconManager?.addRangeNotifier { beacons, region -> //todo what is this callback?
            if (isScanning) {
                storeBeaconsAround(beacons)
                logToWebhookIfNeeded()
            }
        }

        //because of unregister second Monitor notifier issue
        //https://altbeacon.github.io/android-beacon-library/javadoc/org/altbeacon/beacon/startup/RegionBootstrap.html
        /*beaconManager?.addMonitorNotifier(object: MonitorNotifier {
            override fun didDetermineStateForRegion(state: Int, region: Region?) {
                Timber.d("didDetermineStateForRegion, state: $state, region: $region")
            }

            override fun didEnterRegion(region: Region?) {
                Timber.d("didEnterRegion, region: $region")
            }

            override fun didExitRegion(region: Region?) {
                Timber.d("didExitRegion, region: $region")
            }
        })*/

        try {
            beaconManager?.startRangingBeaconsInRegion(Region("com.bridou_n.beaconscanner", null, null, null))
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun onLocationPermissionGranted() {
        tracker.log("permission_granted", null)
        startScan()
    }

    override fun onLocationPermissionDenied(requestCode: Int, permList: List<String>) {
        tracker.log("permission_denied")

        // If the user refused the permission, we just disabled the scan on open
        prefs.isScanOnOpen = false
        if (view.hasSomePermissionPermanentlyDenied(permList)) {
            tracker.log("permission_denied_permanently")
            view.showEnablePermissionSnackbar()
        }
    }

    override fun storeBeaconsAround(beacons: Collection<Beacon>) {
        loggingRequests.add(Observable.fromIterable(beacons)
                .map {
                    val beaconInDb = try {
                        db.beaconsDao().getBeaconById(it.hashCode()).blockingGet()
                    } catch (e: EmptyResultSetException) {
                        null
                    }

                    BeaconSaved.createFromBeacon(it, isBlocked = beaconInDb?.isBlocked ?: false)
                }
                .doOnNext {
                    db.beaconsDao().insertBeacon(it)
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    Timber.d("Beacon inserted")
                }, { err ->
                    Timber.e(err)
                    view.showGenericError(err?.message ?: "")
                }))
    }

    fun logToWebhookIfNeeded() {
        if (prefs.isLoggingEnabled && prefs.loggingEndpoint != null &&
                ++numberOfScansSinceLog >= prefs.getLoggingFrequency()) {

            numberOfScansSinceLog = 0
            loggingRequests.add(db.beaconsDao().getBeaconsSeenAfter(prefs.lasLoggingCall)
                    .filter { it.isNotEmpty() }
                    .doOnSuccess { Timber.d("list to log: $it") }
                    .map { LoggingRequest(prefs.loggingDeviceName ?: "", it) }
                    .flatMapCompletable {
                        loggingService.postLogs(prefs.loggingEndpoint ?: "", it)
                    }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        Timber.d("Logged successfully")
                        prefs.lasLoggingCall = Date().time
                    }, { err ->
                        Timber.e(IllegalStateException("Got err $err"))
                    }))
        }
    }

    override fun stopScan() {
        unbindBeaconManager()
        view.showScanningState(false)
        view.keepScreenOn(false)
        isScanning = false
    }

    override fun onBluetoothToggle() {
        bluetoothState.toggle()
        tracker.log("action_bluetooth")
    }

    override fun onSettingsClicked() {
        tracker.log("action_settings")
        view.startSettingsActivity()
    }

    override fun onClearClicked() {
        tracker.log("action_clear")
        view.showClearDialog()
    }

    override fun onClearAccepted() {
        tracker.log("action_clear_accepted")
        loggingRequests.add(
                Completable.fromCallable {
                    db.beaconsDao().clearBeacons()
                }.subscribeOn(Schedulers.io()).subscribe()
        )
    }

    fun isScanning() = isScanning

    override fun stop() {
        prefs.setScanningState(isScanning())
        unbindBeaconManager()
        listQuery?.dispose()
        loggingRequests.clear()
        bluetoothStateDisposable?.dispose()
        view.keepScreenOn(false)
    }

    fun unbindBeaconManager() {
        if (beaconManager?.isBound(view) == true) {
            Timber.d("Unbinding from beaconManager")
            beaconManager?.unbind(view)
        }
    }
}