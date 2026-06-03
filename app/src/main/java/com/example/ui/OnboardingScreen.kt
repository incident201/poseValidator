package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.viewmodel.AppLanguage
import kotlinx.coroutines.launch

private sealed interface OnboardingPage {
    data object Language : OnboardingPage
    data class ImageTips(
        val imageRes: Int,
        val tipIds: List<Int>,
        val showResultIcons: Boolean
    ) : OnboardingPage
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnboardingScreen(
    language: AppLanguage,
    includeLanguageSlide: Boolean,
    onLanguageChanged: (AppLanguage) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pages = remember(includeLanguageSlide) {
        buildList {
            if (includeLanguageSlide) add(OnboardingPage.Language)
            add(OnboardingPage.ImageTips(R.drawable.slide_1, listOf(R.string.tip_1, R.string.tip_2, R.string.tip_3), false))
            add(OnboardingPage.ImageTips(R.drawable.slide_2, listOf(R.string.tip_4, R.string.tip_5), true))
            add(OnboardingPage.ImageTips(R.drawable.slide_3, listOf(R.string.tip_6, R.string.tip_7, R.string.tip_8), false))
            add(OnboardingPage.ImageTips(R.drawable.slide_4, listOf(R.string.tip_9, R.string.tip_10), true))
        }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        val compact = maxHeight < 700.dp || maxWidth < 360.dp
        val outerPadding = if (compact) 12.dp else 18.dp
        val sectionSpacing = if (compact) 10.dp else 14.dp
        val navHeight = if (compact) 42.dp else 48.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = outerPadding, vertical = outerPadding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { pageIndex ->
                when (val page = pages[pageIndex]) {
                    OnboardingPage.Language -> LanguageOnboardingPage(
                        language = language,
                        onLanguageChanged = onLanguageChanged,
                        colorScheme = colorScheme,
                        compact = compact,
                        modifier = Modifier.fillMaxSize()
                    )
                    is OnboardingPage.ImageTips -> ImageTipsOnboardingPage(
                        language = language,
                        page = page,
                        compact = compact,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.height(sectionSpacing))
            PageIndicator(
                pageCount = pages.size,
                currentPage = pagerState.currentPage,
                compact = compact,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(sectionSpacing))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(navHeight),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }
                    ) {
                        Text(localizedString(language, R.string.onboarding_previous))
                    }
                } else {
                    Spacer(Modifier.width(96.dp))
                }

                if (pagerState.currentPage == pages.lastIndex) {
                    Button(onClick = onFinished) {
                        Text(localizedString(language, R.string.onboarding_done))
                    }
                } else {
                    Button(
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    ) {
                        Text(localizedString(language, R.string.onboarding_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageOnboardingPage(
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    colorScheme: ColorScheme,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = if (compact) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = localizedString(language, R.string.language),
            color = colorScheme.onBackground,
            fontSize = if (compact) 26.sp else 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(if (compact) 20.dp else 28.dp))
        LanguageSelectorCard(
            language = language,
            onLanguageChanged = onLanguageChanged,
            colorScheme = colorScheme,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ImageTipsOnboardingPage(
    language: AppLanguage,
    page: OnboardingPage.ImageTips,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val spacing = if (compact) 10.dp else 14.dp
    Column(modifier = modifier) {
        OnboardingImageCard(
            imageRes = page.imageRes,
            showResultIcons = page.showResultIcons,
            compact = compact,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        Spacer(Modifier.height(spacing))
        OnboardingTipsList(
            language = language,
            tipIds = page.tipIds,
            compact = compact,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OnboardingImageCard(
    imageRes: Int,
    showResultIcons: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 22.dp else 28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageRes)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize()
        )
        if (showResultIcons) {
            ResultIconsOverlay(
                iconSize = if (compact) 56.dp else 64.dp,
                bottomPadding = if (compact) 14.dp else 18.dp
            )
        }
    }
}

@Composable
private fun BoxScope.ResultIconsOverlay(
    iconSize: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2E7D32).copy(alpha = 0.78f),
            modifier = Modifier.size(iconSize)
        )
        Icon(
            imageVector = Icons.Filled.Cancel,
            contentDescription = null,
            tint = Color(0xFFC62828).copy(alpha = 0.78f),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun OnboardingTipsList(
    language: AppLanguage,
    tipIds: List<Int>,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 14.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
        ) {
            tipIds.forEach { tipId ->
                Text(
                    text = "• ${localizedString(language, tipId)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (compact) 13.sp else 16.sp,
                    lineHeight = if (compact) 18.sp else 21.sp
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            val width by animateDpAsState(
                targetValue = if (selected) 22.dp else 8.dp,
                label = "onboardingIndicatorWidth"
            )
            val color by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                label = "onboardingIndicatorColor"
            )
            Box(
                modifier = Modifier
                    .height(if (compact) 7.dp else 8.dp)
                    .width(width)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}
