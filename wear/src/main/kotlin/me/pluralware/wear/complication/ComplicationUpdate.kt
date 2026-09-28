package me.pluralware.wear.complication

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Ask the framework to re-request data for our fronter complication, in every
 * slot it's hosted in. Called through [FronterSurfaces] when the fronter or the
 * token changes; the manifest's update period is only the backstop for
 * switches made off the watch.
 *
 * Safe to call with the application context; the requester holds no UI state.
 */
fun requestFronterComplicationUpdate(context: Context) {
    val appContext = context.applicationContext
    ComplicationDataSourceUpdateRequester
        .create(appContext, ComponentName(appContext, FronterComplicationService::class.java))
        .requestUpdateAll()
}
