package info.nightscout.androidaps.plugins.pump.danaR.activities

import android.content.Intent
import android.os.Bundle
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.MainApp
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.ErrorHelperActivity
import info.nightscout.androidaps.activities.NoSplashAppCompatActivity
import info.nightscout.androidaps.events.EventInitializationChanged
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.L
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin
import info.nightscout.androidaps.plugins.pump.danaR.DanaRPlugin
import info.nightscout.androidaps.plugins.pump.danaR.DanaRPump
import info.nightscout.androidaps.plugins.pump.danaRS.DanaRSPlugin
import info.nightscout.androidaps.plugins.pump.danaRv2.DanaRv2Plugin
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.plusAssign
import info.nightscout.androidaps.utils.resources.ResourceHelper
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.danar_user_options_activity.*
import java.text.DecimalFormat
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

class DanaRUserOptionsActivity : NoSplashAppCompatActivity() {
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rxBus: RxBusWrapper
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var mainApp: MainApp
    @Inject lateinit var danaRSPlugin: DanaRSPlugin
    @Inject lateinit var danaRPlugin: DanaRPlugin
    @Inject lateinit var danaRv2Plugin: DanaRv2Plugin
    @Inject lateinit var configBuilderPlugin: ConfigBuilderPlugin

    private val disposable = CompositeDisposable()

    // This is for Dana pumps only
    private fun isRS() = danaRSPlugin.isEnabled(PluginType.PUMP)

    private fun isDanaR() = danaRPlugin.isEnabled(PluginType.PUMP)
    private fun isDanaRv2() = danaRv2Plugin.isEnabled(PluginType.PUMP)

    @Synchronized
    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ setData() }) { FabricPrivacy.logException(it) }
    }

    @Synchronized
    override fun onPause() {
        disposable.clear()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.danar_user_options_activity)

        save_user_options.setOnClickListener { onSaveClick() }
        val pump = DanaRPump.getInstance()

        if (L.isEnabled(L.PUMP))
            aapsLogger.debug(LTag.PUMP,
                "UserOptionsLoaded:" + (System.currentTimeMillis() - pump.lastConnection) / 1000 + " s ago"
                    + "\ntimeDisplayType:" + pump.timeDisplayType
                    + "\nbuttonScroll:" + pump.buttonScrollOnOff
                    + "\ntimeDisplayType:" + pump.timeDisplayType
                    + "\nlcdOnTimeSec:" + pump.lcdOnTimeSec
                    + "\nbackLight:" + pump.backlightOnTimeSec
                    + "\npumpUnits:" + pump.units
                    + "\nlowReservoir:" + pump.lowReservoirRate)

        danar_screentimeout.setParams(pump.lcdOnTimeSec.toDouble(), 5.0, 240.0, 5.0, DecimalFormat("1"), false, save_user_options)
        danar_backlight.setParams(pump.backlightOnTimeSec.toDouble(), 1.0, 60.0, 1.0, DecimalFormat("1"), false, save_user_options)
        danar_shutdown.setParams(pump.shutdownHour.toDouble(), 0.0, 24.0, 1.0, DecimalFormat("1"), true, save_user_options)
        danar_lowreservoir.setParams(pump.lowReservoirRate.toDouble(), 10.0, 60.0, 10.0, DecimalFormat("10"), false, save_user_options)
        when (pump.beepAndAlarm) {
            0x01  -> danar_pumpalarm_sound.isChecked = true
            0x02  -> danar_pumpalarm_vibrate.isChecked = true
            0x11  -> danar_pumpalarm_both.isChecked = true

            0x101 -> {
                danar_pumpalarm_sound.isChecked = true
                danar_beep.isChecked = true
            }

            0x110 -> {
                danar_pumpalarm_vibrate.isChecked = true
                danar_beep.isChecked = true
            }

            0x111 -> {
                danar_pumpalarm_both.isChecked = true
                danar_beep.isChecked = true
            }
        }
        if (pump.lastSettingsRead == 0L)
            aapsLogger.error(LTag.PUMP, "No settings loaded from pump!") else setData()
    }

    fun setData() {
        val pump = DanaRPump.getInstance()
        // in DanaRS timeDisplay values are reversed
        danar_timeformat.isChecked = !isRS() && pump.timeDisplayType != 0 || isRS() && pump.timeDisplayType == 0
        danar_buttonscroll.isChecked = pump.buttonScrollOnOff != 0
        danar_beep.isChecked = pump.beepAndAlarm > 4
        danar_screentimeout.value = pump.lcdOnTimeSec.toDouble()
        danar_backlight.value = pump.backlightOnTimeSec.toDouble()
        danar_units.isChecked = pump.getUnits() == Constants.MMOL
        danar_shutdown.value = pump.shutdownHour.toDouble()
        danar_lowreservoir.value = pump.lowReservoirRate.toDouble()
    }

    private fun onSaveClick() {
        //exit if pump is not DanaRS, DanaR, or DanaR with upgraded firmware
        if (!isRS() && !isDanaR() && !isDanaRv2()) return

        val pump = DanaRPump.getInstance()

        if (isRS()) // displayTime on RS is reversed
            pump.timeDisplayType = if (danar_timeformat.isChecked) 0 else 1
        else
            pump.timeDisplayType = if (danar_timeformat.isChecked) 1 else 0

        pump.buttonScrollOnOff = if (danar_buttonscroll.isChecked) 1 else 0
        pump.beepAndAlarm = when {
            danar_pumpalarm_sound.isChecked   -> 1
            danar_pumpalarm_vibrate.isChecked -> 2
            danar_pumpalarm_both.isChecked    -> 3
            else                              -> 1
        }
        if (danar_beep.isChecked) pump.beepAndAlarm += 4

        // step is 5 seconds, 5 to 240
        pump.lcdOnTimeSec = min(max(danar_screentimeout.value.toInt() / 5 * 5, 5), 240)
        // 1 to 60
        pump.backlightOnTimeSec = min(max(danar_backlight.value.toInt(), 1), 60)

        pump.units = if (danar_units.isChecked) 1 else 0

        pump.shutdownHour = min(danar_shutdown.value.toInt(), 24)

        // 10 to 50
        pump.lowReservoirRate = min(max(danar_lowreservoir.value.toInt() * 10 / 10, 10), 50)

        configBuilderPlugin.commandQueue.setUserOptions(object : Callback() {
            override fun run() {
                if (!result.success) {
                    val i = Intent(mainApp, ErrorHelperActivity::class.java)
                    i.putExtra("soundid", R.raw.boluserror)
                    i.putExtra("status", result.comment)
                    i.putExtra("title", resourceHelper.gs(R.string.pumperror))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    mainApp.startActivity(i)
                }
            }
        })
        finish()
    }
}