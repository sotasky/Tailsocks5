package com.tsproxy.android

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/** The single DataStore used by both the UI and the foreground service. */
val Context.tsproxyDataStore by preferencesDataStore("tsproxy_prefs")

object TaildropPrefs {
    val ENABLED = booleanPreferencesKey("taildrop_enabled")
    val DEST = stringPreferencesKey("taildrop_dest")

    const val DEST_MEDIA = "media"
    private const val TREE_PREFIX = "tree:"

    fun encodeTree(uri: String): String = TREE_PREFIX + uri
    fun isTree(dest: String): Boolean = dest.startsWith(TREE_PREFIX)
    fun treeUri(dest: String): String = dest.removePrefix(TREE_PREFIX)
}
