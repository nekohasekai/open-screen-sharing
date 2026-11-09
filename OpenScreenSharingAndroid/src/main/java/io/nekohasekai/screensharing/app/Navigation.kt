package io.nekohasekai.screensharing.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith

val slideInFromRight: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(300))
}

val slideOutToRight: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(300))
}

val slideInFromLeft: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(300))
}

val slideOutToLeft: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(300))
}

fun AnimatedContentTransitionScope<*>.slideTransition(forward: Boolean): ContentTransform = if (forward) {
    slideInFromRight(this) togetherWith slideOutToLeft(this)
} else {
    slideInFromLeft(this) togetherWith slideOutToRight(this)
}
