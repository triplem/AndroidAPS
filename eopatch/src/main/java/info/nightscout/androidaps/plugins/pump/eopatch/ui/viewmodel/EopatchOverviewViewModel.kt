package info.nightscout.androidaps.plugins.pump.eopatch.ui.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import info.nightscout.androidaps.interfaces.ActivePlugin
import info.nightscout.androidaps.interfaces.ProfileFunction
import info.nightscout.androidaps.plugins.pump.eopatch.R
import info.nightscout.androidaps.plugins.pump.eopatch.core.scan.BleConnectionState
import info.nightscout.androidaps.plugins.pump.eopatch.ble.IPatchManager
import info.nightscout.androidaps.plugins.pump.eopatch.ble.IPreferenceManager
import info.nightscout.androidaps.plugins.pump.eopatch.code.EventType
import info.nightscout.androidaps.plugins.pump.eopatch.extension.observeOnMainThread
import info.nightscout.androidaps.plugins.pump.eopatch.extension.with
import info.nightscout.androidaps.plugins.pump.eopatch.ui.EoBaseNavigator
import info.nightscout.androidaps.plugins.pump.eopatch.ui.event.UIEvent
import info.nightscout.androidaps.plugins.pump.eopatch.ui.event.SingleLiveEvent
import info.nightscout.androidaps.plugins.pump.eopatch.vo.Alarms
import info.nightscout.androidaps.plugins.pump.eopatch.vo.PatchConfig
import info.nightscout.androidaps.plugins.pump.eopatch.vo.PatchState
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.roundToInt

class EopatchOverviewViewModel @Inject constructor(
    private val context: Context,
    val patchManager: IPatchManager,
    val preferenceManager: IPreferenceManager,
    val profileFunction: ProfileFunction,
    val activePlugin: ActivePlugin
) : EoBaseViewModel<EoBaseNavigator>() {
    private val _eventHandler = SingleLiveEvent<UIEvent<EventType>>()
    val UIEventTypeHandler : LiveData<UIEvent<EventType>>
        get() = _eventHandler

    private val _patchConfig = SingleLiveEvent<PatchConfig>()
    val patchConfig : LiveData<PatchConfig>
        get() = _patchConfig

    private val _patchState = SingleLiveEvent<PatchState>()
    val patchState : LiveData<PatchState>
        get() = _patchState

    private val _normalBasal = SingleLiveEvent<String>()
    val normalBasal : LiveData<String>
        get() = _normalBasal

    private val _tempBasal = SingleLiveEvent<String>()
    val tempBasal : LiveData<String>
        get() = _tempBasal

    private val _bleStatus = SingleLiveEvent<String>()
    val bleStatus : LiveData<String>
        get() = _bleStatus

    private val _status = SingleLiveEvent<String>()
    val status : LiveData<String>
        get() = _status

    private val _alarms = SingleLiveEvent<Alarms>()
    val alarms : LiveData<Alarms>
        get() = _alarms

    private val _patchRemainingInsulin = MutableLiveData(0f)

    private var mDisposable: Disposable? = null

    val patchRemainingInsulin: LiveData<String>
        get() = Transformations.map(_patchRemainingInsulin) { insulin ->
            when {
                insulin > 50f -> "50+ U"
                insulin < 1f -> "0 U"
                else -> "${insulin.roundToInt()} U"
            }
        }

    val isPatchConnected: Boolean
        get() = patchManager.patchConnectionState.isConnected

    init {
        preferenceManager.observePatchConfig()
            .observeOnMainThread()
            .subscribe { _patchConfig.value = it }
            .addTo()

        preferenceManager.observePatchState()
            .observeOnMainThread()
            .subscribe {
                _patchState.value = it
                _patchRemainingInsulin.value = it.remainedInsulin
                updateBasalInfo()
                updatePatchStatus()
            }
            .addTo()

        patchManager.observePatchConnectionState()
            .observeOnMainThread()
            .subscribe {
                _bleStatus.value = when(it){
                    BleConnectionState.CONNECTED -> "{fa-bluetooth}"
                    BleConnectionState.DISCONNECTED -> "{fa-bluetooth-b}"
                    else -> "{fa-bluetooth-b spin}  ${context.getString(R.string.string_connecting)}"
                }
            }
            .addTo()

        patchManager.observePatchLifeCycle()
            .observeOnMainThread()
            .subscribe {
                updatePatchStatus()
            }
            .addTo()

        preferenceManager.observeAlarm()
            .observeOnMainThread()
            .subscribe {
                _alarms.value = it
            }
            .addTo()

        if(preferenceManager.getPatchState().isNormalBasalPaused){
            startPeriodicallyUpdate()
        }else {
            updateBasalInfo()
        }
    }

    private fun updatePatchStatus(){
        if(patchManager.isActivated){
            var finishTimeMillis = patchConfig.value?.basalPauseFinishTimestamp?:System.currentTimeMillis()
            var remainTimeMillis = Math.max(finishTimeMillis - System.currentTimeMillis(), 0L)
            val h =  TimeUnit.MILLISECONDS.toHours(remainTimeMillis)
            val m =  TimeUnit.MILLISECONDS.toMinutes(remainTimeMillis - TimeUnit.HOURS.toMillis(h))
            _status.value = if(patchManager.patchState.isNormalBasalPaused)
                    "${context.getString(R.string.string_suspended)}\n" +
                        "${context.getString(R.string.string_temp_basal_remained_hhmm, h.toString(), m.toString())}"
                else
                    context.getString(R.string.string_running)
        }else{
            _status.value = ""
        }
    }

    private fun updateBasalInfo(){
        if(patchManager.isActivated){
            _normalBasal.value = if(patchManager.patchState.isNormalBasalRunning)
                "${preferenceManager.getNormalBasalManager().normalBasal.currentSegmentDoseUnitPerHour} U/hr"
            else
                ""
            _tempBasal.value = if(patchManager.patchState.isTempBasalActive)
                "${preferenceManager.getTempBasalManager().startedBasal?.doseUnitPerHour} U/hr"
            else
                ""

        }else{
            _normalBasal.value = ""
            _tempBasal.value = ""
        }
    }

    fun onClickActivation(){
        val profile = profileFunction.getProfile()

        if(profile != null && profile.getBasal() >= 0.05) {
            patchManager.preferenceManager.getNormalBasalManager().setNormalBasal(profile)
            patchManager.preferenceManager.flushNormalBasalManager()

            _eventHandler.postValue(UIEvent(EventType.ACTIVTION_CLICKED))
        }else if(profile != null && profile.getBasal() < 0.05){
            _eventHandler.postValue(UIEvent(EventType.INVALID_BASAL_RATE))
        }else{
            _eventHandler.postValue(UIEvent(EventType.PROFILE_NOT_SET))
        }
    }

    fun onClickDeactivation(){
        _eventHandler.postValue(UIEvent(EventType.DEACTIVTION_CLICKED))
    }

    fun onClickSuspendOrResume(){
        if(patchManager.patchState.isNormalBasalPaused) {
            _eventHandler.postValue(UIEvent(EventType.RESUME_CLICKED))
        }else{
            _eventHandler.postValue(UIEvent(EventType.SUSPEND_CLICKED))
        }
    }

    fun pauseBasal(pauseDurationHour: Float){
        patchManager.pauseBasal(pauseDurationHour)
            .with()
            .subscribe({
                if (it.isSuccess) {
                    navigator?.toast(R.string.string_suspended_insulin_delivery_message)
                    startPeriodicallyUpdate()
                } else {
                    UIEvent(EventType.PAUSE_BASAL_FAILED).apply { value = pauseDurationHour }.let { _eventHandler.postValue(it) }
                }
            }, {
                UIEvent(EventType.PAUSE_BASAL_FAILED).apply { value = pauseDurationHour }.let { _eventHandler.postValue(it) }
            }).addTo()
    }

    fun resumeBasal() {
        patchManager.resumeBasal()
            .with()
            .subscribe({
                if (it.isSuccess) {
                    navigator?.toast(R.string.string_resumed_insulin_delivery_message)
                    stopPeriodicallyUpdate()
                } else {
                    _eventHandler.postValue(UIEvent(EventType.RESUME_BASAL_FAILED))
                }
            },{
                _eventHandler.postValue(UIEvent(EventType.RESUME_BASAL_FAILED))
            }).addTo()
    }

    private fun startPeriodicallyUpdate(){
        if(mDisposable == null) {
            mDisposable = Observable.interval(30, TimeUnit.SECONDS)
                .observeOnMainThread()
                .subscribe { updatePatchStatus() }
        }
    }

    private fun stopPeriodicallyUpdate(){
        mDisposable?.dispose()
        mDisposable = null
    }
}