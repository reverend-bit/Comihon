package eu.kanade.tachiyomi.ui.comicdownloader

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object ComicDownloaderTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_comicdownloader_enter)
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.label_comic_downloader),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { ComicDownloaderScreenModel() }

        val tabs = persistentListOf(
            importTab(screenModel),
            readingListTab(screenModel),
            queueTab(screenModel),
        )

        val pagerState = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.label_comic_downloader,
            tabs = tabs,
            state = pagerState,
        )
    }
}
