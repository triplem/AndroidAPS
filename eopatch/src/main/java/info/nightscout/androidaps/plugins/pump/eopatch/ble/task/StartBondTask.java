package info.nightscout.androidaps.plugins.pump.eopatch.ble.task;

import android.bluetooth.BluetoothDevice;

import info.nightscout.shared.logging.LTag;
import info.nightscout.androidaps.plugins.pump.eopatch.core.api.StartBonding;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.Single;

import static info.nightscout.androidaps.plugins.pump.eopatch.core.api.StartBonding.OPTION_NUMERIC;

/**
 * (주의) API 호출 후 본딩을 위해서 밑단 연결이 끊어짐.
 */
@Singleton
public class StartBondTask extends TaskBase {
    private StartBonding START_BOND;

    @Inject
    public StartBondTask() {
        super(TaskFunc.START_BOND);
        START_BOND = new StartBonding();
    }

    public Single<Boolean> start(String mac) {
        prefSetMacAddress(mac);
        patch.updateMacAddress(mac, false);

        return isReady()
                .concatMapSingle(v -> START_BOND.start(OPTION_NUMERIC))
                .doOnNext(this::checkResponse)
                .concatMap(response -> patch.observeBondState())
                .doOnNext(state -> {
                    if(state == BluetoothDevice.BOND_NONE) throw new Exception();
                })
                .filter(result -> result == BluetoothDevice.BOND_BONDED)
                .map(result -> true)
                .timeout(60, TimeUnit.SECONDS)
                .doOnNext(v -> prefSetMacAddress(mac))
                .doOnError(e -> {
                    prefSetMacAddress("");
                    aapsLogger.error(LTag.PUMPCOMM, e.getMessage());
                })
                .firstOrError();
    }

    private synchronized void prefSetMacAddress(String mac) {
        pm.getPatchConfig().setMacAddress(mac);
    }
}



