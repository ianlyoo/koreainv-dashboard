package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.insight.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun InsightConnectionScreen(sessionManager:InsightSessionManager,onBackClick:()->Unit,onConnected:()->Unit,connectionRequired:Boolean=false,onClearCache:()->Unit={}) {
    val connection by sessionManager.state.collectAsState()
    // Never save credentials in savedInstanceState, logs, previews, or autofill state.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberCredentials by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { email="";password="" } }
    LaunchedEffect(connection.isUnlocked) { if(!connection.isUnlocked) { email="";password="" } }
    fun connect() {
        if(busy || email.isBlank() || password.isEmpty() || !connection.isUnlocked) return
        busy=true
        scope.launch {
            try {
                val result=sessionManager.connect(email.trim(),password,rememberCredentials)
                password=""
                message=result.message
                if(result.success) { email="";onConnected() }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { password="";message="연결하지 못했습니다. 다시 시도해 주세요." }
            finally { busy=false }
        }
    }
    DashboardScaffold(topBar={InsightTopBar(title="SaveTicker 연결",onBackClick=onBackClick)}) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal=20.dp,vertical=24.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(connectionRequired) Text("종목 인사이트를 보려면 SaveTicker 연결이 필요합니다. 연결하면 선택한 종목으로 돌아갑니다.",style=MaterialTheme.typography.bodyLarge)
            Text("연결",style=MaterialTheme.typography.titleLarge)
            Text("SaveTicker에서 미국 종목의 가격과 분석 정보를 직접 불러옵니다.",style=MaterialTheme.typography.bodyMedium)
            if(connection.isConnected || connection.status == InsightConnectionStatus.READY) {
                ResponsiveDetailRow("계정",connection.emailMasked ?: "연결된 계정")
                ResponsiveDetailRow("상태",if(connection.isConnected) "연결됨" else "연결 준비됨")
                ResponsiveDetailRow("연결 정보 저장",if(connection.remember) "기기 보안 저장소" else "현재 잠금 해제 세션만")
                HorizontalDivider()
                TextButton(onClick={onClearCache();message="인사이트 데이터 캐시를 삭제했습니다. 연결은 유지됩니다."},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("데이터 캐시 삭제") }
                TextButton(onClick={if(!busy) {busy=true;scope.launch {try {sessionManager.disconnect();message="연결과 저장 정보를 삭제했습니다."} catch(e:CancellationException) { throw e } catch(_:Exception) { message="연결 해제를 완료하지 못했습니다. 다시 시도해 주세요." } finally {busy=false}}}},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("연결 해제 및 저장 정보 삭제") }
            } else {
                TextField(value=email,onValueChange={email=it},label={Text("이메일")},singleLine=true,enabled=!busy && connection.isUnlocked,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email,imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(),colors=TextFieldDefaults.colors(focusedContainerColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedContainerColor=androidx.compose.ui.graphics.Color.Transparent))
                TextField(value=password,onValueChange={password=it},label={Text("비밀번호")},singleLine=true,enabled=!busy && connection.isUnlocked,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={connect()}),modifier=Modifier.fillMaxWidth(),colors=TextFieldDefaults.colors(focusedContainerColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedContainerColor=androidx.compose.ui.graphics.Color.Transparent))
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("이 기기에 연결 정보 저장",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                    Switch(checked=rememberCredentials,onCheckedChange={rememberCredentials=it},enabled=!busy,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp))
                }
                Text(if(rememberCredentials) "기기 화면 잠금이 설정된 경우 Android 보안 저장소에 암호화하여 저장합니다. 저장할 수 없으면 안내하며 평문으로 저장하지 않습니다." else "연결 정보와 쿠키는 현재 잠금 해제 세션의 메모리에만 유지됩니다. 앱이 잠기면 다시 입력해야 합니다.",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={connect()},enabled=!busy && email.isNotBlank() && password.isNotEmpty() && connection.isUnlocked,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).liquidGlass(role=GlassRole.Control)) { Text(if(busy) "연결 중…" else "연결") }
            }
            (message ?: connection.message)?.let { Text(it,style=MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
