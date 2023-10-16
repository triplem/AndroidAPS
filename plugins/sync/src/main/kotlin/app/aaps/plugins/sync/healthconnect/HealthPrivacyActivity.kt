package app.aaps.plugins.sync.healthconnect

import android.os.Bundle
import android.view.View
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.source.R

class HealthPrivacyActivity : TranslatedDaggerAppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_privacy)
    }

    fun healthPrivacyClose(view: View?) {
        finish()
    }
}