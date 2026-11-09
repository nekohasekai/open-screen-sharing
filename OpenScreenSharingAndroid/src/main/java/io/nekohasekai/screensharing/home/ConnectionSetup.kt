package io.nekohasekai.screensharing.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertHost
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.FormButton
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.slideTransition
import io.nekohasekai.screensharing.discovery.NearbyBrowser
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.RemoteLink
import io.nekohasekai.screensharing.session.LoginCheck
import io.nekohasekai.screensharing.session.ProbeResult
import io.nekohasekai.screensharing.session.ProbedServer
import io.nekohasekai.screensharing.session.ServerAuthentication
import io.nekohasekai.screensharing.session.ServerProber
import io.nekohasekai.screensharing.session.SessionCredentials
import io.nekohasekai.screensharing.session.SessionFailure
import io.nekohasekai.screensharing.session.SessionMemory
import io.nekohasekai.screensharing.session.SessionRoute
import io.nekohasekai.screensharing.sessionui.OtherConnectionSection
import io.nekohasekai.screensharing.sessionui.title
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class NewComputer(
    val name: String,
    val model: String?,
    val username: String = "",
    val password: String = "",
    val server: ProbedServer,
) {
    fun signedIn(credentials: SessionCredentials, check: LoginCheck): SignedInComputer {
        val account = credentials.account
        val saved = Computer(name, server.address, server.remoteProtocol, account?.username ?: "").copy(
            password = account?.password ?: "",
            authenticates = account != null,
            model = model ?: "",
            trustedFingerprint = check.fingerprint,
            usesHighPerformance = check.supportsHighPerformance,
            usesNativeTouch = SessionMemory.defaultUsesNativeTouch,
        )
        return SignedInComputer(saved, credentials, server.authentication)
    }
}

data class SignedInComputer(
    val computer: Computer,
    val credentials: SessionCredentials,
    val server: ServerAuthentication?,
)

private sealed interface SetupStep {
    data object ConnectTo : SetupStep
    data class SignIn(val computer: NewComputer) : SetupStep
    data class Configure(val computer: SignedInComputer, val previous: SetupStep) : SetupStep

    val depth: Int
        get() = when (this) {
            ConnectTo -> 0
            is SignIn -> 1
            is Configure -> 2
        }
}

@Composable
fun ConnectionSetupView(link: RemoteLink?, connect: (SessionRoute) -> Unit, dismiss: () -> Unit) {
    var step by remember { mutableStateOf<SetupStep>(SetupStep.ConnectTo) }
    val stateHolder = rememberSaveableStateHolder()
    DisposableEffect(Unit) {
        NearbyBrowser.shared.start()
        onDispose { NearbyBrowser.shared.stop() }
    }
    AnimatedContent(
        targetState = step,
        transitionSpec = { slideTransition(forward = targetState.depth >= initialState.depth) },
        contentKey = { it.depth },
        label = "setup",
    ) { shown ->
        when (shown) {
            SetupStep.ConnectTo -> stateHolder.SaveableStateProvider("connectTo") {
                ConnectToView(
                    link = link,
                    close = dismiss,
                    signIn = { step = SetupStep.SignIn(it) },
                    signedIn = { step = SetupStep.Configure(it, SetupStep.ConnectTo) },
                )
            }
            is SetupStep.SignIn -> {
                BackHandler { step = SetupStep.ConnectTo }
                SetupSignInView(
                    computer = shown.computer,
                    next = { signedIn, typed -> step = SetupStep.Configure(signedIn, SetupStep.SignIn(typed)) },
                    back = { step = SetupStep.ConnectTo },
                )
            }
            is SetupStep.Configure -> {
                val back = { step = shown.previous }
                BackHandler(onBack = back)
                ComputerEditor(
                    computer = shown.computer.computer,
                    setup = ComputerEditorSetup(shown.computer.credentials, shown.computer.server, connect, back),
                    dismiss = dismiss,
                )
            }
        }
    }
}

@Composable
private fun SetupSignInView(computer: NewComputer, next: (SignedInComputer, NewComputer) -> Unit, back: () -> Unit) {
    var username by remember { mutableStateOf(computer.username) }
    var password by remember { mutableStateOf(computer.password) }
    var task by remember { mutableStateOf<Job?>(null) }
    var problem by remember { mutableStateOf<SetupProblem?>(null) }
    var alert by remember { mutableStateOf<AlertState?>(null) }
    val scope = rememberCoroutineScope()
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    var focusesPassword by remember { mutableIntStateOf(0) }

    val asksForUsername = computer.server.authentication.account
    val trimmedUsername = username.trim()
    val isValid = if (asksForUsername) trimmedUsername.isNotEmpty() else password.isNotEmpty()

    fun attempt(attempted: SessionCredentials, onFailure: (SessionFailure) -> Unit) {
        if (task != null) return
        problem = null
        val typed = computer.copy(username = username, password = password)
        task = scope.launch {
            val result = ServerProber.checkLogin(computer.server, attempted)
            task = null
            when (result) {
                is ProbeResult.Success -> next(computer.signedIn(attempted, result.value), typed)
                is ProbeResult.Failure if result.failure.kind == SessionFailure.Kind.UnverifiedServer ->
                    next(computer.signedIn(attempted, LoginCheck(supportsHighPerformance = false, fingerprint = "")), typed)
                is ProbeResult.Failure -> onFailure(result.failure)
            }
        }
    }

    fun signIn() {
        if (!isValid) return
        val credentials = if (asksForUsername) SessionCredentials.Account(trimmedUsername, password) else SessionCredentials.VncPassword(password)
        attempt(credentials) { failure ->
            problem = SetupProblem(failure, computer.server.address.host, asksForUsername)
            if (failure.kind == SessionFailure.Kind.AuthenticationFailed) focusesPassword++
        }
    }

    fun signInWith(account: SessionCredentials) {
        attempt(account) { failure ->
            alert = AlertState.failure(failure.title(computer.name, computer.server.remoteProtocol), failure.message)
        }
    }

    LaunchedEffect(focusesPassword) {
        if (focusesPassword > 0) passwordFocus.requestFocus()
    }

    LaunchedEffect(Unit) {
        withFrameNanos {}
        if (asksForUsername && trimmedUsername.isEmpty()) usernameFocus.requestFocus() else passwordFocus.requestFocus()
    }

    PageScaffold(
        title = computer.name,
        navigationIcon = {
            IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home_back))
            }
        },
    ) {
        if (asksForUsername) {
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    problem = null
                },
                modifier = FieldModifier.focusRequester(usernameFocus),
                enabled = task == null,
                label = { Text(stringResource(R.string.home_user_name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onAny = { passwordFocus.requestFocus() }),
                singleLine = true,
            )
        }
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                problem = null
            },
            label = stringResource(R.string.home_password),
            focusRequester = passwordFocus,
            imeAction = ImeAction.Go,
            onImeAction = ::signIn,
            enabled = task == null,
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val shownProblem = problem
            when {
                task != null -> InlineProgress(stringResource(R.string.home_signing_in))
                shownProblem != null -> SetupProblemView(shownProblem)
                else -> Text(
                    stringResource(if (asksForUsername) R.string.home_sign_in_account else R.string.home_sign_in_password, computer.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FormButton(stringResource(R.string.home_next), enabled = task == null && isValid, onClick = ::signIn)
        if (asksForUsername) {
            OtherConnectionSection(computerID = null, signIn = ::signInWith, enabled = task == null)
        }
    }
    AlertHost(alert) { alert = null }
}

data class SetupProblem(val summary: String, val error: String = "") {
    constructor(failure: SessionFailure, host: String, asksForUsername: Boolean) : this(summary(failure, host, asksForUsername), failure.message)

    private companion object {
        fun summary(failure: SessionFailure, host: String, asksForUsername: Boolean): String = when (failure.kind) {
            SessionFailure.Kind.AuthenticationFailed -> App.instance.getString(if (asksForUsername) R.string.home_wrong_account else R.string.home_wrong_password)
            SessionFailure.Kind.HostNotFound -> App.instance.getString(R.string.home_cant_find, host)
            else -> App.instance.getString(R.string.home_cant_connect, host)
        }
    }
}

@Composable
fun SetupProblemView(problem: SetupProblem, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(problem.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (problem.error.isNotEmpty()) {
            SelectionContainer {
                Text(problem.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
