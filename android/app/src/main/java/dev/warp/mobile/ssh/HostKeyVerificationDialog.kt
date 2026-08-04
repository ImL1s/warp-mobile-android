package dev.warp.mobile.ssh

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun HostKeyVerificationDialog(
    fingerprint: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Unknown Host Key") },
        text = { Text("The host key fingerprint is:\n$fingerprint\n\nDo you want to continue connecting?") },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Reject")
            }
        }
    )
}
