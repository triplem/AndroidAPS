package app.aaps.plugins.sync.healthconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodGlucoseRecord.Companion.RELATION_TO_MEAL_UNKNOWN
import androidx.health.connect.client.records.BloodGlucoseRecord.Companion.SPECIMEN_SOURCE_INTERSTITIAL_FLUID
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.units.BloodGlucose
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.source.HeatlhConnectSource
import app.aaps.core.interfaces.sync.Sync
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.main.utils.worker.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.impl.AppRepository
import dagger.android.HasAndroidInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectPlugin @Inject constructor(
    injector: HasAndroidInjector,
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val context: Context,
    config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.main.R.drawable.ic_dexcom_g6)
        .preferencesId(R.xml.pref_dexcom)
        .pluginName(R.string.dexcom_app_patched)
        .shortName(R.string.dexcom_short)
        .description(R.string.description_source_dexcom),
    aapsLogger, rh, injector
), Sync, HeatlhConnectSource {

    lateinit var healthConnectClient: HealthConnectClient

    override fun onStart() {
        super.onStart()
        provideGoogleHealthConnect()
    }

    class HealthConnectWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var injector: HasAndroidInjector
        @Inject lateinit var healthConnectPlugin: HealthConnectPlugin
        @Inject lateinit var sp: SP
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var dataWorkerStorage: DataWorkerStorage
        @Inject lateinit var repository: AppRepository
        @Inject lateinit var uel: UserEntryLogger
        @Inject lateinit var profileUtil: ProfileUtil

        override suspend fun doWorkAndLog(): Result {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            scope.launch {
                val granted = healthConnectPlugin.healthConnectClient
                    .permissionController.getGrantedPermissions()
                if (granted.containsAll(PERMISSIONS)) {
                    // Permissions already granted; proceed with inserting or reading data



                } else {
//            requestPermissions.launch(PERMISSIONS)
                }
            }

            return Result.success()
        }
    }

    fun provideGoogleHealthConnect() {
        val providerPackageName = "AAPS"
        val availabilityStatus = HealthConnectClient.getSdkStatus(context, providerPackageName)
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            return // early return as there is no viable integration
        }

        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            // Optionally redirect to package installer to find a provider, for example:
            val uriString = "market://details?id=$providerPackageName&url=healthconnect%3A%2F%2Fonboarding"
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage("com.android.vending")
                    data = Uri.parse(uriString)
                    putExtra("overlay", true)
                    putExtra("callerId", context.packageName)
                }
            )
            return
        }

        healthConnectClient = HealthConnectClient.getOrCreate(context)
    }

    fun sendGlucose(bgValue: GlucoseValue) {
        val bgRecord = BloodGlucoseRecord(
                                        bgValue.time(),
                                        bgValue.zoneOffset(),
                                        BloodGlucose.milligramsPerDeciliter(bgValue.value),
                                        SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
                                        MealType.MEAL_TYPE_UNKNOWN,
                                        RELATION_TO_MEAL_UNKNOWN,
                                        Metadata())
        healthConnectClient.insertRecords(listOf(bgRecord))


    }

    companion object {
        // Create a set of permissions for required data types
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getWritePermission(BloodGlucoseRecord::class)
        )
    }
}

fun GlucoseValue.time(): Instant = Instant.ofEpochMilli(this.timestamp)
fun GlucoseValue.zoneOffset(): ZoneOffset = ZoneOffset.of(ZoneOffset.systemDefault().id)