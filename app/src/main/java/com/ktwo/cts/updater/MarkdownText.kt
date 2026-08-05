package com.ktwo.cts.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown 渲染, 对齐 AStop `i4/b.java` 的实现:
 * 仅支持两种行内语法 ——
 *   - `code`  → Monospace 字体 + 背景色, 内容两侧补空格
 *   - **bold** → 加粗
 *
 * 不支持标题/列表/链接/图片(AStop 的更新日志只用这两种)。
 *
 * @param text      原始 markdown 文本
 * @param maxLines  最大行数(用于折叠), Int.MAX_VALUE = 完全展开
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (text.isBlank()) return
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeText = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(text, codeBg, codeText) {
        buildAnnotatedMarkdown(text, codeBg, codeText)
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
        color = LocalContentColor.current,
        maxLines = maxLines,
    )
}

/**
 * 解析 markdown 文本为 AnnotatedString。
 * 实现: 逐字符扫描, 遇到 ` 找下一个 ` 包成 code 段; 遇到 ** 找下一个 ** 包成 bold 段。
 * 取两者中较早出现者先处理, 其余当普通文本输出。
 */
private fun buildAnnotatedMarkdown(
    markdown: String,
    codeBg: Color,
    codeText: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = markdown.length
    while (i < n) {
        val codeStart = markdown.indexOf('`', i)
        val boldStart = markdown.indexOf("**", i)
        val nextStart = when {
            codeStart < 0 && boldStart < 0 -> -1
            codeStart < 0 -> boldStart
            boldStart < 0 -> codeStart
            else -> minOf(codeStart, boldStart)
        }
        if (nextStart < 0) {
            append(markdown.substring(i, n))
            break
        }
        if (nextStart > i) append(markdown.substring(i, nextStart))
        if (nextStart == codeStart) {
            val codeEnd = markdown.indexOf('`', codeStart + 1)
            if (codeEnd < 0) {
                append('`')
                i = codeStart + 1
                continue
            }
            val content = markdown.substring(codeStart + 1, codeEnd)
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeText)) {
                append(' ')
                append(content)
                append(' ')
            }
            i = codeEnd + 1
        } else {
            val boldEnd = markdown.indexOf("**", boldStart + 2)
            if (boldEnd < 0) {
                append("**")
                i = boldStart + 2
                continue
            }
            val content = markdown.substring(boldStart + 2, boldEnd)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(content)
            }
            i = boldEnd + 2
        }
    }
}

/** 放在带圆角背景容器里展示更新日志的卡片版(用于代码块整体高亮)。 */
@Composable
fun MarkdownCard(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (text.isBlank()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        MarkdownText(text = text, maxLines = maxLines)
    }
}
