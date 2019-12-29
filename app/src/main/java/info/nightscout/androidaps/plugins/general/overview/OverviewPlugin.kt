package info.nightscout.androidaps.plugins.general.overview

import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.events.EventRefreshOverview
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification
import info.nightscout.androidaps.plugins.general.overview.notifications.NotificationStore
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.SP
import info.nightscout.androidaps.utils.plusAssign
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverviewPlugin @Inject constructor(
    private val rxBus: RxBusWrapper,
    private val notificationStore: NotificationStore
) : PluginBase(PluginDescription()
    .mainType(PluginType.GENERAL)
    .fragmentClass(OverviewFragment::class.qualifiedName)
    .alwaysVisible(true)
    .alwaysEnabled(true)
    .pluginName(R.string.overview)
    .shortName(R.string.overview_shortname)
    .preferencesId(R.xml.pref_overview)
    .description(R.string.description_overview)) {

    init {
        INSTANCE = this
    }

    companion object {
        @JvmStatic
        @Deprecated("Get via Dagger. Will be removed once fully transitioned to Dagger")
        lateinit var INSTANCE: OverviewPlugin //TODO: remove as soon as Dagger is fully set up
    }

    private var disposable: CompositeDisposable = CompositeDisposable()

    var bgTargetLow = 80.0
    var bgTargetHigh = 180.0

    override fun onStart() {
        super.onStart()
        notificationStore.createNotificationChannel()
        disposable += rxBus
            .toObservable(EventNewNotification::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ n ->
                if (notificationStore.add(n.notification))
                    rxBus.send(EventRefreshOverview("EventNewNotification"))
            }, {
                FabricPrivacy.logException(it)
            })
        disposable += rxBus
            .toObservable(EventDismissNotification::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ n ->
                if (notificationStore.remove(n.id))
                    rxBus.send(EventRefreshOverview("EventDismissNotification"))
            }, {
                FabricPrivacy.logException(it)
            })
    }

    override fun onStop() {
        disposable.clear()
        super.onStop()
    }

    fun determineHighLine(): Double {
        var highLineSetting = SP.getDouble(R.string.key_high_mark, bgTargetHigh)
        if (highLineSetting < 1) highLineSetting = Constants.HIGHMARK
        highLineSetting = Profile.toCurrentUnits(highLineSetting)
        return highLineSetting
    }

    fun determineLowLine(): Double {
        var lowLineSetting = SP.getDouble(R.string.key_low_mark, bgTargetLow)
        if (lowLineSetting < 1) lowLineSetting = Constants.LOWMARK
        lowLineSetting = Profile.toCurrentUnits(lowLineSetting)
        return lowLineSetting
    }
}
