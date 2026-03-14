package tachiyomi.source.local

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.UnmeteredSource

expect class LocalSource : CatalogueSource, UnmeteredSource {
    companion object {
        val ID: Long
        val HELP_URL: String
    }
}
