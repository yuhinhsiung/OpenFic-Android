package com.openfic.android.storage

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Makes an activity's resources resolve in the language the app is set to, rather than the
 * device language.
 *
 * The SPA owns the language setting — it is a synced backend preference — and publishes it
 * over the host bridge. Without this the native screens would keep following the device
 * locale while the web UI followed the app setting, so a user reading the app in Chinese
 * would still get an English instance manager.
 *
 * Falls back to the device locale when the app has not reported a language yet, which is the
 * case on first launch before any backend is configured.
 */
fun localizedContext(base: Context): Context {
    val tag = AppPreferencesStore(base).read().language ?: return base
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty()) return base

    Locale.setDefault(locale)
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(locale)
    return base.createConfigurationContext(configuration)
}
