package io.github.holego.checkprint.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.holego.checkprint.MainViewModel
import io.github.holego.checkprint.R
import io.github.holego.checkprint.escpos.CodePage
import io.github.holego.checkprint.net.Protocol
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PrintScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setImage(uri)
    }
    val nothingMsg = stringResource(R.string.print_nothing)
    val badTargetMsg = stringResource(R.string.print_bad_target)
    val testTitle = stringResource(R.string.test_page_title)

    fun report(result: Result<Int>) {
        val message = result.fold(
            onSuccess = { context.getString(R.string.print_sent, it, "${vm.host.trim()}:${vm.port.trim()}") },
            onFailure = { e ->
                if (e is MainViewModel.BadTargetException) badTargetMsg
                else context.getString(R.string.print_failed, e.message ?: e.javaClass.simpleName)
            },
        )
        scope.launch { snackbar.showSnackbar(message) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------------------ target
        SectionCard(stringResource(R.string.print_target)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.host,
                    onValueChange = { vm.host = it },
                    label = { Text(stringResource(R.string.print_host)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = vm.port,
                    onValueChange = { vm.port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.print_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(stringResource(R.string.print_protocol), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Protocol.entries.forEach { p ->
                    FilterChip(selected = vm.protocol == p, onClick = { vm.protocol = p }, label = { Text(p.name) })
                }
            }
            if (vm.protocol == Protocol.LPD) {
                OutlinedTextField(
                    value = vm.lpdQueue,
                    onValueChange = { vm.lpdQueue = it },
                    label = { Text(stringResource(R.string.print_lpd_queue)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // -------------------------------------------------------------- text
        SectionCard(stringResource(R.string.print_text_section)) {
            OutlinedTextField(
                value = vm.text,
                onValueChange = { vm.text = it },
                placeholder = { Text(stringResource(R.string.print_text_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.print_width, vm.widthMul), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = vm.widthMul.toFloat(),
                onValueChange = { vm.widthMul = it.roundToInt().coerceIn(1, 8) },
                valueRange = 1f..8f,
                steps = 6,
            )
            Text(stringResource(R.string.print_height, vm.heightMul), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = vm.heightMul.toFloat(),
                onValueChange = { vm.heightMul = it.roundToInt().coerceIn(1, 8) },
                valueRange = 1f..8f,
                steps = 6,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.bold, onClick = { vm.bold = !vm.bold }, label = { Text(stringResource(R.string.print_bold)) })
                FilterChip(selected = vm.underline, onClick = { vm.underline = !vm.underline }, label = { Text(stringResource(R.string.print_underline)) })
            }
            Text(stringResource(R.string.print_align), style = MaterialTheme.typography.bodyMedium)
            AlignChips(vm.textAlign) { vm.textAlign = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DropdownField(
                    label = stringResource(R.string.print_codepage),
                    options = CodePage.entries,
                    selected = vm.codePage,
                    text = { it.label },
                    onSelect = vm::selectCodePage,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = vm.escTOverride,
                    onValueChange = { vm.escTOverride = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.print_esc_t)) },
                    placeholder = { Text(if (vm.codePage.escT < 0) "—" else vm.codePage.escT.toString()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
        }

        // ------------------------------------------------------------- image
        SectionCard(stringResource(R.string.print_image_section)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.print_pick_image))
                }
                if (vm.imageUri != null) {
                    TextButton(onClick = { vm.setImage(null) }) { Text(stringResource(R.string.print_remove_image)) }
                }
            }
            if (vm.imageError) {
                Text(stringResource(R.string.print_image_error), color = MaterialTheme.colorScheme.error)
            }
            val preview = vm.previewBitmap
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(Color.White),
                )
                Text(
                    stringResource(R.string.print_image_size, preview.width, preview.height),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (vm.imageUri != null) {
                CircularProgressIndicator(Modifier.padding(8.dp))
            }
            if (vm.imageUri != null) {
                Text(stringResource(R.string.print_image_width), style = MaterialTheme.typography.bodyMedium)
                ImageWidthChips(vm.imageWidth, vm::updateImageWidth)
                LabeledSwitch(stringResource(R.string.print_image_dither), vm.dither, vm::updateDither)
                Text(stringResource(R.string.print_image_threshold, vm.threshold), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = vm.threshold.toFloat(),
                    onValueChange = { vm.updateThreshold(it.roundToInt()) },
                    valueRange = 1f..254f,
                )
                Text(stringResource(R.string.print_align), style = MaterialTheme.typography.bodyMedium)
                AlignChips(vm.imageAlign) { vm.imageAlign = it }
            }
        }

        // ----------------------------------------------------------- options
        SectionCard(stringResource(R.string.print_options_section)) {
            LabeledSwitch(stringResource(R.string.print_order), vm.imageFirst) { vm.imageFirst = it }
            Stepper(stringResource(R.string.print_feed), vm.feedLines, 0..10) { vm.feedLines = it }
            LabeledSwitch(stringResource(R.string.print_cut), vm.cut) { vm.cut = it }
        }

        // ----------------------------------------------------------- actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val job = vm.buildJob()
                    if (job == null) scope.launch { snackbar.showSnackbar(nothingMsg) }
                    else vm.send(job, ::report)
                },
                enabled = !vm.sending,
                modifier = Modifier.weight(1f),
            ) {
                if (vm.sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.print_sending))
                } else {
                    Icon(Icons.Filled.Print, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.print_send))
                }
            }
            OutlinedButton(
                onClick = { vm.send(vm.buildTestPage(testTitle), ::report) },
                enabled = !vm.sending,
            ) {
                Text(stringResource(R.string.print_test))
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun AlignChips(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.align_left, R.string.align_center, R.string.align_right)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, res ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(stringResource(res)) })
        }
    }
}

@Composable
private fun ImageWidthChips(width: Int, onSelect: (Int) -> Unit) {
    var custom by remember(width) { mutableStateOf(if (width == 384 || width == 576) "" else width.toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = width == 384, onClick = { onSelect(384) }, label = { Text(stringResource(R.string.print_image_width_58)) })
        FilterChip(selected = width == 576, onClick = { onSelect(576) }, label = { Text(stringResource(R.string.print_image_width_80)) })
        OutlinedTextField(
            value = custom,
            onValueChange = { v ->
                custom = v.filter(Char::isDigit).take(4)
                custom.toIntOrNull()?.takeIf { it in 8..2048 }?.let(onSelect)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(88.dp),
        )
    }
}
