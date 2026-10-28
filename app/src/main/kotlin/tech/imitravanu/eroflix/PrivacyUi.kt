package tech.imitravanu.eroflix

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun PinUnlockScreen(controller: PrivacyController,state: PrivacyUi) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val activity=androidx.activity.compose.LocalActivity.current as? MainActivity
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center) {
        Text("Eroflix is locked",style=MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        PinField(pin,{ pin=it },if(state.recoveryAuthorized) "New PIN" else "PIN")
        if(state.recoveryAuthorized) PinField(confirmation,{ confirmation=it },"Confirm new PIN")
        state.message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(state.recoveryAuthorized) {
            Button(enabled=!state.busy && pin.length>=4 && pin==confirmation,onClick={ val value=pin.toCharArray();pin="";confirmation="";controller.recoverPin(value) }) { Text("Save new PIN") }
            TextButton(enabled=!state.busy,onClick={ pin="";confirmation="";controller.cancelRecovery() }) { Text("Cancel PIN recovery") }
        } else {
            Button(enabled=!state.busy && pin.isNotEmpty(),onClick={ val value=pin.toCharArray();pin="";controller.unlock(value) }) { Text("Unlock") }
            if(state.biometricEnabled) {
                OutlinedButton(enabled=!state.busy && activity!=null,onClick={ activity?.authenticateFingerprint(false) }) { Text("Unlock with fingerprint") }
                TextButton(enabled=!state.busy && activity!=null,onClick={ pin="";activity?.authenticateFingerprint(false,true) }) { Text("Forgot PIN? Recover with fingerprint") }
            }
        }
        Text("Your PIN is separate from your phone's screen lock.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PinField(value: String,onChange: (String)->Unit,label: String) {
    OutlinedTextField(value=value,onValueChange={ if(it.length<=64 && it.all(Char::isDigit)) onChange(it) },
        label={ Text(label) },singleLine=true,visualTransformation=PasswordVisualTransformation(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth())
}

@Composable internal fun PrivacySettings(controller: PrivacyController) {
    val state by controller.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val activity=androidx.activity.compose.LocalActivity.current as? MainActivity
    val biometricAvailable=activity != null && androidx.biometric.BiometricManager.from(activity).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)==androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    Text("Privacy",style=MaterialTheme.typography.titleMedium)
    Text(if(state.enrolled) "PIN lock is enabled." else "PIN lock is off.")
    Text("Keep your PIN safe. If fingerprint access changes or is unavailable, your PIN is required. If neither works, your data is not automatically deleted.",style=MaterialTheme.typography.bodySmall)
    state.message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    if(state.enrolled) {
        if(!state.biometricEnabled && biometricAvailable) OutlinedButton(onClick={ activity?.authenticateFingerprint(true) },enabled=!state.busy) { Text("Enable fingerprint unlock") }
        if(state.biometricEnabled) Text("Fingerprint unlock is enabled.")
        OutlinedButton(onClick=controller::lock,enabled=!state.busy) { Text("Lock now") }
        OutlinedButton(onClick={ pin="";dialog="disable" },enabled=!state.busy) { Text("Disable PIN lock") }
    } else OutlinedButton(onClick={ pin="";confirm="";dialog="enable" },enabled=!state.busy) { Text("Set PIN lock") }
    if(dialog!=null) AlertDialog(onDismissRequest={ pin="";confirm="";dialog=null },
        title={ Text(if(dialog=="enable") "Set PIN" else "Disable PIN lock") },
        text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            PinField(pin,{ pin=it },if(dialog=="enable") "New PIN" else "Current PIN")
            if(dialog=="enable") PinField(confirm,{ confirm=it },"Confirm PIN")
            if(dialog=="enable") Text("Use 4 to 64 digits. If you forget this PIN, recovery is not yet available.")
        } },
        confirmButton={ TextButton(enabled=!state.busy && pin.length>=4 && (dialog!="enable" || pin==confirm),onClick={
            val value=pin.toCharArray();val enable=dialog=="enable";pin="";confirm="";dialog=null
            if(enable) controller.enroll(value) else controller.disable(value)
        }) { Text(if(dialog=="enable") "Enable" else "Disable") } },
        dismissButton={ TextButton(onClick={ pin="";confirm="";dialog=null }) { Text("Cancel") } })
}
