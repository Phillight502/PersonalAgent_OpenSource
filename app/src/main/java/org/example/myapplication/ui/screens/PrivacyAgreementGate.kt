package org.example.myapplication.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 协议正文内可点击片段的标记：与 [LegalDocument] 对应 */
private const val ANNOTATION_USER_AGREEMENT = "user_agreement"
private const val ANNOTATION_PRIVACY_POLICY = "privacy_policy"
private val linkColor = Color(0xFF2B5FD9)

/**
 * 首次启动协议强制确认页（全屏 gate）。
 *
 * - 在用户同意《用户协议》与《隐私政策》前拦截整个应用，主界面仍在底层但不可交互；
 * - 协议名称可点击，由 [onOpenDocument] 打开应用内 WebView 文档页，返回后仍停留在本页；
 * - 点"不同意"或按系统返回键 → 二次确认对话框，确认后 [onExitApp] 退出应用；
 * - 点"同意并继续" → [onAgree] 持久化同意标记并进入应用。
 */
@Composable
fun PrivacyAgreementGate(
    onAgree: () -> Unit,
    onExitApp: () -> Unit,
    onOpenDocument: (LegalDocument) -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }

    // 未同意前系统返回键不走默认退出，统一进入"二次确认"流程
    BackHandler(enabled = true) { showExitConfirm = true }

    val annotatedText = buildAnnotatedString {
        append("欢迎使用 PersonalAgent！\n\n")
        append("在使用前，请您仔细阅读并充分理解")
        appendAgreementLink("《用户协议》", ANNOTATION_USER_AGREEMENT)
        append("与")
        appendAgreementLink("《隐私政策》", ANNOTATION_PRIVACY_POLICY)
        append("。两份文档将帮助您了解：我们提供的服务内容、AI 功能如何处理您的对话与日程数据、")
        append("数据在本地如何存储与保护，以及您享有的查阅、删除、撤回授权等权利。\n\n")
        append("您点击“同意并继续”即表示您已阅读并接受全部条款；若您不同意，将无法使用本应用。")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "服务协议与隐私政策",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                ClickableText(
                    text = annotatedText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(18.dp),
                    onClick = { offset ->
                        annotatedText.getStringAnnotations(start = offset, end = offset)
                            .firstOrNull()
                            ?.let { annotation ->
                                when (annotation.tag) {
                                    ANNOTATION_USER_AGREEMENT ->
                                        onOpenDocument(LegalDocument.USER_AGREEMENT)
                                    ANNOTATION_PRIVACY_POLICY ->
                                        onOpenDocument(LegalDocument.PRIVACY_POLICY)
                                }
                            }
                    }
                )
            }

            Spacer(modifier = Modifier.height(22.dp))
            Button(
                onClick = onAgree,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "同意并继续", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { showExitConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "不同意", fontSize = 14.sp)
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("提示", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "您尚未同意《用户协议》与《隐私政策》，将无法使用本应用。确定要退出吗？",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    onExitApp()
                }) { Text("退出应用", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("再看看") }
            }
        )
    }
}

/** 在协议正文中追加一个主题色、加粗的可点击协议名 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendAgreementLink(
    text: String,
    tag: String
) {
    pushStringAnnotation(tag = tag, annotation = tag)
    withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) {
        append(text)
    }
    pop()
}
