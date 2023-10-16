package app.aaps.core.interfaces.source

interface HeatlhConnectSource {

    fun isEnabled(): Boolean
    fun requestPermissionIfNeeded()

}