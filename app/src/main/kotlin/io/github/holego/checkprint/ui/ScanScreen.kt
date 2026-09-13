package io.github.holego.checkprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.holego.checkprint.MainViewModel
import io.github.holego.checkprint.R
import io.github.holego.checkprint.net.FoundHost
import io.github.holego.checkprint.net.PrinterService
import kotlin.math.roundToInt

@Composable
fun ScanScreen(vm: MainViewModel, onPickTarget: (ip: String, port: Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.padding(top = 8.dp))
        val ifaces = vm.interfaces
        Text(
            if (ifaces.isEmpty()) stringResource(R.string.scan_no_network)
            else stringResource(R.string.scan_your_ip, ifaces.joinToString("  ·  ") { it.toString() }),
            style = MaterialTheme.typography.bodySmall,
            color = if (ifaces.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = vm.rangeText,
            onValueChange = { vm.rangeText = it },
            label = { Text(stringResource(R.string.scan_range)) },
            singleLine = true,
            enabled = !vm.scanning,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = vm.portsText,
            onValueChange = { vm.portsText = it },
            label = { Text(stringResource(R.string.scan_ports)) },
            singleLine = true,
            enabled = !vm.scanning,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = vm::useMySubnet, enabled = !vm.scanning) { Text(stringResource(R.string.scan_my_subnet)) }
            TextButton(onClick = vm::resetPorts, enabled = !vm.scanning) { Text(stringResource(R.string.scan_reset_ports)) }
        }

        Text(stringResource(R.string.scan_timeout, vm.timeoutMs), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = vm.timeoutMs.toFloat(),
            onValueChange = { vm.timeoutMs = (it / 50f).roundToInt() * 50 },
            valueRange = 100f..2000f,
            enabled = !vm.scanning,
        )

        Button(
            onClick = { if (vm.scanning) vm.stopScan() else vm.startScan() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(if (vm.scanning) Icons.Filled.Stop else Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (vm.scanning) R.string.scan_stop else R.string.scan_start))
        }

        vm.scanError?.let { err ->
            Text(
                when (err) {
                    MainViewModel.ScanError.BadRange -> stringResource(R.string.scan_bad_range)
                    MainViewModel.ScanError.BadPorts -> stringResource(R.string.scan_bad_ports)
                    MainViewModel.ScanError.NoNetwork -> stringResource(R.string.scan_no_network)
                    is MainViewModel.ScanError.TooMany -> stringResource(R.string.scan_too_many, err.max)
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (vm.total > 0) {
            LinearProgressIndicator(
                progress = { vm.progress.toFloat() / vm.total },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.scan_progress, vm.progress, vm.total), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.scan_found, vm.hosts.size), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (vm.hosts.isEmpty()) {
            Text(
                stringResource(if (vm.scanFinished) R.string.scan_done_empty else R.string.scan_empty),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
        } else {
            Text(
                stringResource(R.string.scan_tap_port),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.hosts, key = { it.ip }) { host -> HostCard(host, onPickTarget) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostCard(host: FoundHost, onPickTarget: (String, Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Print, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(host.ip, style = MaterialTheme.typography.titleMedium)
                    host.hostname?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                host.ports.forEach { port ->
                    AssistChip(
                        onClick = { onPickTarget(host.ip, port) },
                        label = { Text("$port · ${serviceName(PrinterService.forPort(port))}") },
                    )
                }
            }
        }
    }
}

@Composable
fun serviceName(service: PrinterService): String = stringResource(
    when (service) {
        PrinterService.RAW -> R.string.service_raw
        PrinterService.IPP -> R.string.service_ipp
        PrinterService.LPD -> R.string.service_lpd
        PrinterService.HTTP -> R.string.service_http
        PrinterService.EPOS -> R.string.service_epos
        PrinterService.OTHER -> R.string.service_other
    }
)
