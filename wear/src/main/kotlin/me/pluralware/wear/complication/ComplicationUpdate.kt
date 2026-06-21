package me.pluralware.wear.complication

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Ask the framework to re-request data for our fronter complication.
 *
 * Our manifest declares `UPDATE_PERIOD_SECONDS = 0` (no periodic polling),
 * because PluralKit switches are user-initiated and the platform floors any
 * non-zero period at 300s. Instead we push: call this right after a switch is
 * registered so every hosted instance refreshes immediately.
 *
 * Safe to call with the application context; the requester holds no UI state.
 */
fun requestFronterComplicationUpdate(context: Context) {
    val appContext = context.applicationContext
    ComplicationDataSourceUpdateRequester
        .create(appContext, ComponentName(appContext, FronterComplicationService::class.java))
        .requestUpdateAll()
}
