package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A list shown a page at a time in a fixed grid, ‹ › to turn pages: nothing on the phone scrolls vertically.
    `startAt` opens on the page holding that item (the current preset, scale …). */
@Composable
fun <T> Paged(items: List<T>, columns: Int, rows: Int, startAt: Int = 0, rowHeight: Dp = 44.dp, key: Any? = null,
              cell: @Composable (index: Int, item: T) -> Unit) {
    val perPage = columns * rows
    val pages = maxOf(1, (items.size + perPage - 1) / perPage)
    var page by remember(key, items.size) { mutableIntStateOf((startAt.coerceAtLeast(0) / perPage).coerceIn(0, pages - 1)) }
    Column {
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until columns) {
                    val i = page * perPage + r * columns + c
                    Box(Modifier.weight(1f)) { if (i < items.size) cell(i, items[i]) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            PageButton("‹", page > 0) { page-- }
            Text("${page + 1} / $pages", color = C.quiet, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp))
            PageButton("›", page < pages - 1) { page++ }
        }
    }
}

@Composable
private fun PageButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(C.control).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) { Text(text, color = if (enabled) C.text else C.faint, fontSize = 18.sp, fontWeight = FontWeight.Black) }
}
