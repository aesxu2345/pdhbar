package com.pdyy.pdhbar.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdyy.pdhbar.runtime.UiCommand
import com.pdyy.pdhbar.runtime.std

@Composable
fun BreakfastApp() {
    val context = LocalContext.current
    var orderCode by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf<UiCommand.ShowCustomer?>(null) }

    LaunchedEffect(Unit) {
        std.run().pipefall.commands.collect { command ->
            when (command) {
                is UiCommand.Toast -> Toast.makeText(context, command.message, Toast.LENGTH_SHORT).show()
                is UiCommand.ShowCustomer -> customer = command
                UiCommand.CloseDialog -> customer = null
            }
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "早餐核销工作台",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "PDA 扫描导检单条码后，经 PipeFall 异步进入 Worker 处理。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = orderCode,
                        onValueChange = { orderCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("导检单条码 / order_code") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { std.run().uifurge.onScanInput(orderCode) }) {
                            Text("模拟扫码")
                        }
                        OutlinedButton(onClick = { std.run().uifurge.onBreakfastDoneClick(orderCode) }) {
                            Text("完成早餐")
                        }
                    }
                }
            }

            RuntimeStatusCard()
        }
    }

    customer?.let { data ->
        CustomerDialog(
            customer = data,
            onDone = { std.run().uifurge.onBreakfastDoneClick(data.orderCode) },
            onCancelBreakfast = { std.run().uifurge.onBreakfastCancelClick(data.orderCode) },
            onDismiss = { std.run().uifurge.onDialogCancelClick() }
        )
    }
}

@Composable
private fun RuntimeStatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Runtime", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("backend = ${std.run().api.target.scheme}://${std.run().api.target.host}:${std.run().api.target.port}")
            Text("std.systemIO = PipeFallIO 的 IBinder 锚点")
            Text("barscanner = ${if (std.run().barscanner.isRunning()) "运行中" else "已暂停"}")
        }
    }
}

@Composable
private fun CustomerDialog(
    customer: UiCommand.ShowCustomer,
    onDone: () -> Unit,
    onCancelBreakfast: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("客户早餐信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("导检单：${customer.orderCode}")
                Text("姓名：${customer.name}")
                Text("套餐：${customer.packageName ?: "-"}")
                Text("早餐状态：${if (customer.hasBreakfast) "已登记" else "未登记"}")
            }
        },
        confirmButton = {
            Button(onClick = onDone) {
                Text("完成早餐")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancelBreakfast) {
                    Text("取消登记")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}