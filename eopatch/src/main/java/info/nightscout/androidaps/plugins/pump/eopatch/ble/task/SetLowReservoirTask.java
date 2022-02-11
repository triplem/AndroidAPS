package info.nightscout.androidaps.plugins.pump.eopatch.ble.task;

import info.nightscout.shared.logging.LTag;
import info.nightscout.androidaps.plugins.pump.eopatch.ble.IPreferenceManager;
import info.nightscout.androidaps.plugins.pump.eopatch.core.api.SetLowReservoirLevelAndExpireAlert;
import info.nightscout.androidaps.plugins.pump.eopatch.core.response.PatchBooleanResponse;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.Single;

@Singleton
public class SetLowReservoirTask extends TaskBase {

    @Inject
    IPreferenceManager pm;

    private SetLowReservoirLevelAndExpireAlert SET_LOW_RESERVOIR_N_EXPIRE_ALERT;

    @Inject
    public SetLowReservoirTask() {
        super(TaskFunc.LOW_RESERVOIR);
        SET_LOW_RESERVOIR_N_EXPIRE_ALERT = new SetLowReservoirLevelAndExpireAlert();
    }

    public Single<PatchBooleanResponse> set(int doseUnit, int hours) {
        return isReady()
                .concatMapSingle(v -> SET_LOW_RESERVOIR_N_EXPIRE_ALERT.set(
                        doseUnit,
                        hours))
                .doOnNext(this::checkResponse)
                .firstOrError()
                .doOnError(e -> aapsLogger.error(LTag.PUMPCOMM, e.getMessage()));
    }

    public synchronized void enqueue() {

        int alertTime = pm.getPatchConfig().getPatchExpireAlertTime();
        int alertSetting = pm.getPatchConfig().getLowReservoirAlertAmount();

        boolean ready = (disposable == null || disposable.isDisposed());

        if (ready) {
            disposable = set(alertSetting, alertTime)
                .timeout(TASK_ENQUEUE_TIME_OUT, TimeUnit.SECONDS)
                .subscribe();
        }
    }

    @Override
    protected void preCondition() throws Exception {
        checkPatchConnected();
    }
}
