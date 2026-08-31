package com.cylonid.nativealpha.matrix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R

/** 页边距(16×2) + 窗格间隔(8) 的近似宽度（fit 估算口径） */
private const val PAGE_AND_GAP_DP = 40

/**
 * 格内显示调节 sheet（P5 后 Q1 反转需求：格子视口小——页面缩放默认 80%
 * 起步、字体默认「小一级」90%，可实时调节并持久化到 matrix_session）。
 *
 * 交互：slider 本地实时显示数值，松手（onValueChangeFinished）一次性应用
 * ——页面缩放走 zoomBy 相对换算，避免拖动中高频缩放抖动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CellAdjustSheet(
    engine: MatrixEngine,
    cellIndex: Int,
    onDismiss: () -> Unit
) {
    val cell = engine.cells.value.getOrNull(cellIndex) ?: return
    var zoomPct by remember { mutableFloatStateOf(cell.zoomPercent.toFloat()) }
    var textPct by remember { mutableFloatStateOf(cell.textZoomPercent.toFloat()) }
    val configuration = LocalConfiguration.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.matrix_adjust),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // 适应宽度：zoom=格子宽/单屏宽——页面按接近单屏宽度布局整体
            // 缩进格子，显示效果与单屏一致（字号等比缩小，可再手动调）
            OutlinedButton(
                onClick = {
                    val screenCss = configuration.screenWidthDp
                    val cols = MatrixEngine.columnCountOf(
                        engine.windowCount.value, cellIndex
                    )
                    val cellCss = (screenCss - PAGE_AND_GAP_DP) / cols
                    val fit = MatrixEngine.fitZoomPercent(cellCss, screenCss)
                    zoomPct = fit.toFloat()
                    engine.applyCellAdjust(cellIndex, fit, textPct.toInt())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.matrix_fit_width))
            }

            Text(
                text = stringResource(R.string.matrix_zoom_page),
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = zoomPct,
                onValueChange = { zoomPct = it },
                onValueChangeFinished = {
                    engine.applyCellAdjust(cellIndex, zoomPct.toInt(), textPct.toInt())
                },
                valueRange = 30f..150f
            )
            Text(
                text = stringResource(R.string.matrix_zoom_page_value, zoomPct.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.matrix_zoom_text),
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = textPct,
                onValueChange = { textPct = it },
                onValueChangeFinished = {
                    engine.applyCellAdjust(cellIndex, zoomPct.toInt(), textPct.toInt())
                },
                valueRange = 50f..150f
            )
            Text(
                text = stringResource(R.string.matrix_zoom_text_value, textPct.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
