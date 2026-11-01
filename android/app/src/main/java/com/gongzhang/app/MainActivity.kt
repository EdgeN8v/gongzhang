package com.gongzhang.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gongzhang.app.data.AppUpdater
import com.gongzhang.app.data.Prefs
import com.gongzhang.app.data.UpdateInfo
import com.gongzhang.app.ui.*

class MainActivity : ComponentActivity() {
    private val vm: Vm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GzTheme { Root(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.checkUpdate()
        if (vm.loggedIn) lifecycleScope.launch { vm.sync() }
    }
}

@Composable
private fun Root(vm: Vm) {
    val context = LocalContext.current
    LaunchedEffect(vm.message) { vm.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.message = null } }

    // One check per launch: a newer build on the server pops the one-tap update dialog.
    vm.update?.let { UpdateDialog(it) { vm.dismissUpdate() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!vm.loggedIn) LoginScreen(vm) else Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    listOf("✏️" to "记账", "📊" to "汇总", "👷" to "人员车辆", "⚙️" to "我的").forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(selected = vm.tab == i, onClick = { vm.tab = i }, icon = { Text(icon, fontSize = 22.sp) }, label = { Text(label, fontSize = 13.sp) })
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                when (vm.tab) {
                    0 -> TodayScreen(vm)
                    1 -> ReportScreen(vm)
                    2 -> PeopleScreen(vm)
                    else -> MeScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(vm: Vm) {
    val context = LocalContext.current
    var server by remember { mutableStateOf(Prefs.server(context)) }
    var user by remember { mutableStateOf(Prefs.user(context)) }
    var pass by remember { mutableStateOf("") }
    var showServer by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(72.dp))
        Text("工账", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("每天记账 · 一键算工资", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        Spacer(Modifier.height(32.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField2("用户名", user, { user = it })
                OutlinedTextField(pass, { pass = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                if (showServer) TextField2("服务器地址", server, { server = it })
                BigButton(if (vm.syncing) "登录中…" else "登录") { if (!vm.syncing) vm.login(server, user, pass) }
                TextButton(onClick = { showServer = !showServer }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (showServer) "收起" else "服务器地址", fontSize = 14.sp) }
            }
        }
    }
}
