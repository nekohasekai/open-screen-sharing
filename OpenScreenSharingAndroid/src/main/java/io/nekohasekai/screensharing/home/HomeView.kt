package io.nekohasekai.screensharing.home

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertHost
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.LocalScaffoldPadding
import io.nekohasekai.screensharing.app.LocalTopBarController
import io.nekohasekai.screensharing.app.TopBarController
import io.nekohasekai.screensharing.app.slideInFromLeft
import io.nekohasekai.screensharing.app.slideInFromRight
import io.nekohasekai.screensharing.app.slideOutToLeft
import io.nekohasekai.screensharing.app.slideOutToRight
import io.nekohasekai.screensharing.discovery.AvailabilityMonitor
import io.nekohasekai.screensharing.discovery.LocalNetworkAccess
import io.nekohasekai.screensharing.discovery.NearbyBrowser
import io.nekohasekai.screensharing.discovery.ServiceResolver
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.RemoteLink
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.SessionCredentials
import io.nekohasekai.screensharing.session.SessionRoute
import io.nekohasekai.screensharing.sessionui.SessionActivity
import io.nekohasekai.screensharing.sessionui.SessionTutorial
import io.nekohasekai.screensharing.sessionui.SessionTutorialView
import io.nekohasekai.screensharing.settings.settingsDestinations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class TopLevelRoute(val route: String, @StringRes val title: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    Connections("connections", R.string.home_connections, Icons.Filled.Computer, Icons.Outlined.Computer),
    Settings("settings", R.string.settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

private class LinkLookup(val name: String, val job: Job)

@Composable
fun HomeView(pendingLink: String?, onLinkHandled: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val localNetworkAccess = LocalNetworkAccess.permission.rememberRequester()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentRootRoute = currentRoute?.substringBefore('/')
    val topBarController = remember { TopBarController(mutableStateOf(emptyList())) }
    var connectionsReselections by rememberSaveable { mutableIntStateOf(0) }
    var setupLink by remember { mutableStateOf<RemoteLink?>(null) }
    var alert by remember { mutableStateOf<AlertState?>(null) }
    var linkLookup by remember { mutableStateOf<LinkLookup?>(null) }
    var tutorials by remember { mutableStateOf(SessionTutorial.unseen()) }

    fun cancelLookup() {
        linkLookup?.job?.cancel()
        linkLookup = null
    }

    fun select(destination: TopLevelRoute) {
        cancelLookup()
        val route = navController.currentDestination?.route
        if (route?.substringBefore('/') == destination.route) {
            if (route != destination.route) {
                navController.popBackStack(destination.route, inclusive = false)
            } else if (destination == TopLevelRoute.Connections) {
                connectionsReselections++
            }
            return
        }
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun connect(route: SessionRoute) {
        cancelLookup()
        SessionActivity.start(context, route)
    }

    fun newConnection(link: RemoteLink?) {
        cancelLookup()
        setupLink = link
        navController.navigate("connections/new") {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
        }
    }

    fun open(link: RemoteLink, address: ComputerAddress) {
        val computer = ComputerStore.computer(address, link.remoteProtocol, link.username)
        if (computer == null) {
            newConnection(link.copy(address = address))
            return
        }
        var route = SessionRoute(computer)
        link.username?.let { route = route.copy(username = it) }
        link.password?.let { password ->
            route = route.copy(credentials = route.username?.let { SessionCredentials.Account(it, password) } ?: SessionCredentials.VncPassword(password))
        }
        connect(route)
    }

    fun open(url: String) {
        cancelLookup()
        val link = RemoteLink.parse(url)
        if (link == null) {
            alert = AlertState.failure(App.instance.getString(R.string.home_couldnt_open_link), App.instance.getString(R.string.home_invalid_address, url))
            return
        }
        val serviceName = link.address.bonjourServiceName
        if (serviceName == null) {
            open(link, link.address)
            return
        }
        val nearbyAddress = NearbyBrowser.shared.computers.firstOrNull { it.remoteProtocol == RemoteProtocol.Vnc && it.name == serviceName }?.address
        if (nearbyAddress != null) {
            open(link, nearbyAddress)
            return
        }
        val job = scope.launch {
            try {
                localNetworkAccess.request()
                val address = ServiceResolver.resolve(serviceName)
                open(link, address)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                linkLookup = null
                alert = AlertState.failure(App.instance.getString(R.string.home_couldnt_open_link), error)
            }
        }
        linkLookup = LinkLookup(serviceName, job)
    }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val watching = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && currentRootRoute == TopLevelRoute.Connections.route
    DisposableEffect(watching) {
        if (watching) {
            NearbyBrowser.shared.start()
            AvailabilityMonitor.shared.start()
        }
        onDispose {
            if (watching) {
                AvailabilityMonitor.shared.stop()
                NearbyBrowser.shared.stop()
            }
        }
    }
    var requestedLocalNetworkAccess by rememberSaveable { mutableStateOf(false) }
    val discovers = watching && LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(discovers) {
        if (!discovers || requestedLocalNetworkAccess || LocalNetworkAccess.permission.isGranted) return@LaunchedEffect
        requestedLocalNetworkAccess = true
        localNetworkAccess.request()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancelLookup() }
    DisposableEffect(Unit) { onDispose { cancelLookup() } }

    val isReady = navBackStackEntry != null
    val pending = HomeRequest.pending
    LaunchedEffect(pending, isReady) {
        if (pending == null || !isReady) return@LaunchedEffect
        HomeRequest.pending = null
        when (pending) {
            HomeRequest.Pending.NewConnection -> newConnection(null)
            HomeRequest.Pending.Settings -> select(TopLevelRoute.Settings)
        }
    }
    LaunchedEffect(pendingLink, isReady) {
        if (pendingLink == null || !isReady) return@LaunchedEffect
        onLinkHandled()
        open(pendingLink)
    }

    val multiWindow = activity?.isInMultiWindowMode == true || LocalConfiguration.current.smallestScreenWidthDp >= 600
    val usesRail = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val topBarContent: @Composable () -> Unit = {
        topBarController.current?.invoke()
    }

    val scaffoldContent: @Composable (PaddingValues) -> Unit = { paddingValues ->
        CompositionLocalProvider(LocalScaffoldPadding provides paddingValues) {
            Box(Modifier.fillMaxSize().consumeWindowInsets(paddingValues)) {
                NavHost(
                    navController = navController,
                    startDestination = TopLevelRoute.Connections.route,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(TopLevelRoute.Connections.route) {
                        ComputersView(
                            connect = ::connect,
                            openInNewWindow = if (multiWindow) {
                                { route -> SessionActivity.start(context, route, newWindow = true) }
                            } else {
                                null
                            },
                            edit = { navController.navigate("connections/edit/${it.id}") },
                            newConnection = { newConnection(null) },
                            reselections = connectionsReselections,
                        )
                    }
                    composable(
                        "connections/new",
                        enterTransition = slideInFromRight,
                        exitTransition = slideOutToLeft,
                        popEnterTransition = slideInFromLeft,
                        popExitTransition = slideOutToRight,
                    ) {
                        ConnectionSetupView(link = setupLink, connect = ::connect, dismiss = { navController.navigateUp() })
                    }
                    composable(
                        "connections/edit/{id}",
                        enterTransition = slideInFromRight,
                        exitTransition = slideOutToLeft,
                        popEnterTransition = slideInFromLeft,
                        popExitTransition = slideOutToRight,
                    ) { entry ->
                        val id = entry.arguments?.getString("id")
                        val computer = ComputerStore.computers.firstOrNull { it.id.toString() == id }
                        if (computer == null) {
                            LaunchedEffect(Unit) { navController.navigateUp() }
                        } else {
                            ComputerEditor(computer, setup = null) { navController.navigateUp() }
                        }
                    }
                    settingsDestinations(navController)
                }
            }
        }
    }

    CompositionLocalProvider(LocalTopBarController provides topBarController) {
        if (usesRail) {
            Row(Modifier.fillMaxSize()) {
                Surface(tonalElevation = 1.dp) {
                    NavigationRail(Modifier.fillMaxHeight()) {
                        for (destination in TopLevelRoute.entries) {
                            val selected = currentRootRoute == destination.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { select(destination) },
                                icon = { Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.title)) },
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier
                        .weight(1f)
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                    contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                    topBar = topBarContent,
                ) { paddingValues ->
                    scaffoldContent(paddingValues)
                }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = topBarContent,
                bottomBar = {
                    NavigationBar {
                        for (destination in TopLevelRoute.entries) {
                            val selected = currentRootRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { select(destination) },
                                icon = { Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.title)) },
                            )
                        }
                    }
                },
            ) { paddingValues ->
                scaffoldContent(paddingValues)
            }
        }
    }

    linkLookup?.let { lookup ->
        AlertDialog(
            onDismissRequest = ::cancelLookup,
            confirmButton = {
                TextButton(onClick = ::cancelLookup) { Text(stringResource(R.string.cancel)) }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_looking_for, lookup.name))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            properties = DialogProperties(dismissOnClickOutside = false),
        )
    }
    if (tutorials.isNotEmpty()) {
        SessionTutorialView(tutorials) { tutorials = emptyList() }
    }
    AlertHost(alert) { alert = null }
}

@Composable
internal fun OnReselect(reselections: Int, action: suspend () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(reselections) }
    LaunchedEffect(reselections) {
        if (reselections != handled) {
            handled = reselections
            action()
        }
    }
}
