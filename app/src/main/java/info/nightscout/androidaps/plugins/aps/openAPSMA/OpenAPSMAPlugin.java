package info.nightscout.androidaps.plugins.aps.openAPSMA;

import org.json.JSONException;

import javax.inject.Inject;
import javax.inject.Singleton;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.MealData;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.interfaces.APSInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.aps.loop.APSResult;
import info.nightscout.androidaps.plugins.aps.loop.ScriptReader;
import info.nightscout.androidaps.plugins.aps.openAPSMA.events.EventOpenAPSUpdateGui;
import info.nightscout.androidaps.plugins.aps.openAPSMA.events.EventOpenAPSUpdateResultGui;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.utils.HardLimits;
import info.nightscout.androidaps.utils.Profiler;
import info.nightscout.androidaps.utils.Round;

import static info.nightscout.androidaps.utils.HardLimits.checkOnlyHardLimits;
import static info.nightscout.androidaps.utils.HardLimits.verifyHardLimits;

@Singleton
public class OpenAPSMAPlugin extends PluginBase implements APSInterface {
    private final AAPSLogger aapsLogger;

    // last values
    DetermineBasalAdapterMAJS lastDetermineBasalAdapterMAJS = null;
    long lastAPSRun = 0;
    DetermineBasalResultMA lastAPSResult = null;

    @Inject
    public OpenAPSMAPlugin(AAPSLogger aapsLogger) {
        super(new PluginDescription()
                .mainType(PluginType.APS)
                .fragmentClass(OpenAPSMAFragment.class.getName())
                .pluginName(R.string.openapsma)
                .shortName(R.string.oaps_shortname)
                .preferencesId(R.xml.pref_openapsma)
                .description(R.string.description_ma)
        );
        this.aapsLogger = aapsLogger;
    }

    @Override
    public boolean specialEnableCondition() {
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();
        return pump == null || pump.getPumpDescription().isTempBasalCapable;
    }

    @Override
    public boolean specialShowInListCondition() {
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();
        return pump == null || pump.getPumpDescription().isTempBasalCapable;
    }

    @Override
    public APSResult getLastAPSResult() {
        return lastAPSResult;
    }

    @Override
    public long getLastAPSRun() {
        return lastAPSRun;
    }

    @Override
    public void invoke(String initiator, boolean tempBasalFallback) {
        aapsLogger.debug(LTag.APS, "invoke from " + initiator + " tempBasalFallback: " + tempBasalFallback);
        lastAPSResult = null;
        DetermineBasalAdapterMAJS determineBasalAdapterMAJS;
        determineBasalAdapterMAJS = new DetermineBasalAdapterMAJS(new ScriptReader(MainApp.instance().getBaseContext()), aapsLogger);

        GlucoseStatus glucoseStatus = GlucoseStatus.getGlucoseStatusData();
        Profile profile = ProfileFunctions.getInstance().getProfile();
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();

        if (profile == null) {
            RxBus.Companion.getINSTANCE().send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.noprofileselected)));
            aapsLogger.debug(LTag.APS, MainApp.gs(R.string.noprofileselected));
            return;
        }

        if (pump == null) {
            RxBus.Companion.getINSTANCE().send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.nopumpselected)));
            aapsLogger.debug(LTag.APS, MainApp.gs(R.string.nopumpselected));
            return;
        }

        if (!isEnabled(PluginType.APS)) {
            RxBus.Companion.getINSTANCE().send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.openapsma_disabled)));
            aapsLogger.debug(LTag.APS, MainApp.gs(R.string.openapsma_disabled));
            return;
        }

        if (glucoseStatus == null) {
            RxBus.Companion.getINSTANCE().send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.openapsma_noglucosedata)));
            aapsLogger.debug(LTag.APS, MainApp.gs(R.string.openapsma_noglucosedata));
            return;
        }

        double maxBasal = ConstraintChecker.getInstance().getMaxBasalAllowed(profile).value();

        double minBg = profile.getTargetLowMgdl();
        double maxBg = profile.getTargetHighMgdl();
        double targetBg = profile.getTargetMgdl();

        minBg = Round.roundTo(minBg, 0.1d);
        maxBg = Round.roundTo(maxBg, 0.1d);

        long start = System.currentTimeMillis();
        TreatmentsPlugin.getPlugin().updateTotalIOBTreatments();
        TreatmentsPlugin.getPlugin().updateTotalIOBTempBasals();
        IobTotal bolusIob = TreatmentsPlugin.getPlugin().getLastCalculationTreatments();
        IobTotal basalIob = TreatmentsPlugin.getPlugin().getLastCalculationTempBasals();

        IobTotal iobTotal = IobTotal.combine(bolusIob, basalIob).round();

        MealData mealData = TreatmentsPlugin.getPlugin().getMealData();

        double maxIob = ConstraintChecker.getInstance().getMaxIOBAllowed().value();
        Profiler.log(aapsLogger, LTag.APS, "MA data gathering", start);

        minBg = verifyHardLimits(minBg, "minBg", HardLimits.VERY_HARD_LIMIT_MIN_BG[0], HardLimits.VERY_HARD_LIMIT_MIN_BG[1]);
        maxBg = verifyHardLimits(maxBg, "maxBg", HardLimits.VERY_HARD_LIMIT_MAX_BG[0], HardLimits.VERY_HARD_LIMIT_MAX_BG[1]);
        targetBg = verifyHardLimits(targetBg, "targetBg", HardLimits.VERY_HARD_LIMIT_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TARGET_BG[1]);

        TempTarget tempTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory(System.currentTimeMillis());
        if (tempTarget != null) {
            minBg = verifyHardLimits(tempTarget.low, "minBg", HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[1]);
            maxBg = verifyHardLimits(tempTarget.high, "maxBg", HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[1]);
            targetBg = verifyHardLimits(tempTarget.target(), "targetBg", HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[1]);
        }

        if (!checkOnlyHardLimits(profile.getDia(), "dia", HardLimits.MINDIA, HardLimits.MAXDIA))
            return;
        if (!checkOnlyHardLimits(profile.getIcTimeFromMidnight(Profile.secondsFromMidnight()), "carbratio", HardLimits.MINIC, HardLimits.MAXIC))
            return;
        if (!checkOnlyHardLimits(profile.getIsfMgdl(), "sens", HardLimits.MINISF, HardLimits.MAXISF))
            return;
        if (!checkOnlyHardLimits(profile.getMaxDailyBasal(), "max_daily_basal", 0.02, HardLimits.maxBasal()))
            return;
        if (!checkOnlyHardLimits(pump.getBaseBasalRate(), "current_basal", 0.01, HardLimits.maxBasal()))
            return;

        start = System.currentTimeMillis();
        try {
            determineBasalAdapterMAJS.setData(profile, maxIob, maxBasal, minBg, maxBg, targetBg, ConfigBuilderPlugin.getPlugin().getActivePump().getBaseBasalRate(), iobTotal, glucoseStatus, mealData);
        } catch (JSONException e) {
            FabricPrivacy.logException(e);
            return;
        }
        Profiler.log(aapsLogger, LTag.APS, "MA calculation", start);


        long now = System.currentTimeMillis();

        DetermineBasalResultMA determineBasalResultMA = determineBasalAdapterMAJS.invoke();
        if (determineBasalResultMA == null) {
            aapsLogger.error(LTag.APS, "MA calculation returned null");
            lastDetermineBasalAdapterMAJS = null;
            lastAPSResult = null;
            lastAPSRun = 0;
        } else {
            // Fix bug determinef basal
            if (determineBasalResultMA.rate == 0d && determineBasalResultMA.duration == 0 && !TreatmentsPlugin.getPlugin().isTempBasalInProgress())
                determineBasalResultMA.tempBasalRequested = false;

            determineBasalResultMA.iob = iobTotal;

            try {
                determineBasalResultMA.json.put("timestamp", DateUtil.toISOString(now));
            } catch (JSONException e) {
                aapsLogger.error(LTag.APS, "Unhandled exception", e);
            }

            lastDetermineBasalAdapterMAJS = determineBasalAdapterMAJS;
            lastAPSResult = determineBasalResultMA;
            lastAPSRun = now;
        }
        RxBus.Companion.getINSTANCE().send(new EventOpenAPSUpdateGui());
    }


}
