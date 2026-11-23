package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** A small "type something" box (the TV's on-screen keyboard opens). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    hint: String? = null,
    label: String = "Name",
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
    }
    ModalLayer(onDismiss) {
        Column(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 620.dp).background(TunerColors.Panel, RoundedCornerShape(16.dp)).padding(24.dp)
        ) {
            Text(title, color = TunerColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            hint?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = TunerColors.TextSecondary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = keyboard),
                keyboardActions = KeyboardActions(onDone = {
                    onDone(text.trim())
                    onDismiss()
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Save", {
                    onDone(text.trim())
                    onDismiss()
                })
                PillButton("Cancel", onDismiss)
            }
        }
    }
}
