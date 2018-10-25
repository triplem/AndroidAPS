package info.nightscout.androidaps.plugins.AutoReact;

import android.content.Context;

import com.squareup.otto.Subscribe;

import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.ConfigBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.Treatments.TreatmentsPlugin;
import info.nightscout.utils.DefaultValueHelper;

public class AutoReactPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.OVERVIEW);

    private static AutoReactPlugin autoReactPlugin;

    private final Context ctx;

    private boolean hypoTTset = false;

    public static AutoReactPlugin getPlugin() {
        if (autoReactPlugin == null)
            autoReactPlugin = new AutoReactPlugin(MainApp.instance());
        return autoReactPlugin;
    }

    public AutoReactPlugin(Context ctx) {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .alwayVisible(false)
                .alwaysEnabled(false)
                .pluginName(R.string.overview)
                .shortName(R.string.overview_shortname)
                .preferencesId(R.xml.pref_overview)
                .description(R.string.description_overview)
        );
        this.ctx = ctx;
    }

    @Override
    protected void onStart() {
        MainApp.bus().register(this);
        super.onStart();
    }

    @Subscribe
    public void onStatusEvent(final EventNewBG ev) {
        if (ev == null) return;
        if (ev.bgReading == null) return;

        final Profile currentProfile = ProfileFunctions.getInstance().getProfile();
        if (currentProfile == null) {
            return;
        }

        if (ev.bgReading.value < Constants.LOW_BS_THRESHOLD) {
            // set HypoTT

            String units = currentProfile.getUnits();

            DefaultValueHelper helper = new DefaultValueHelper();
            int hypoTTDuration = helper.determineHypoTTDuration();
            double hypoTT = helper.determineHypoTT(units);

            TempTarget tempTarget = new TempTarget()
                    .date(System.currentTimeMillis())
                    .duration(hypoTTDuration)
                    .reason(MainApp.gs(R.string.hypo))
                    .source(Source.USER)
                    .low(Profile.toMgdl(hypoTT, currentProfile.getUnits()))
                    .high(Profile.toMgdl(hypoTT, currentProfile.getUnits()));
            TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTarget);

            hypoTTset = true;
        }
    }


}
