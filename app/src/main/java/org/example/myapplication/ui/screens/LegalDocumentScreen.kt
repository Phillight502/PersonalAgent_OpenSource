package org.example.myapplication.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 法律文档类型：对应 assets 根目录下随 APK 打包的两份 HTML
 *
 * @param title     TopAppBar 展示的中文标题
 * @param assetName assets 中的文件名（互链使用同名相对路径）
 */
enum class LegalDocument(val title: String, val assetName: String) {
    PRIVACY_POLICY("隐私政策", "privacy-policy.html"),
    USER_AGREEMENT("用户协议", "user-agreement.html")
}

/**
 * "隐私"中心页：设置 → 隐私 →（隐私政策 / 用户协议）的二级页面，
 * 替代原先的二选一弹窗。两行均为应用内 WebView 本地页面。
 *
 * 返回链路：系统返回键 / 左上角箭头均退回设置页；从文档页返回时回到本页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalHubScreen(
    onBack: () -> Unit,
    onOpenDocument: (LegalDocument) -> Unit,
    /** 点击"撤回授权"：由宿主页面弹二次确认并执行清数据退出 */
    onRevoke: () -> Unit
) {
    BackHandler(enabled = true) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "隐私", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                LegalHubRow(
                    icon = Icons.Default.Security,
                    title = "隐私政策",
                    subtitle = "数据如何收集、使用与保护"
                ) { onOpenDocument(LegalDocument.PRIVACY_POLICY) }
                Divider(modifier = Modifier.padding(start = 56.dp))
                LegalHubRow(
                    icon = Icons.Default.Description,
                    title = "用户协议",
                    subtitle = "服务条款与使用规则"
                ) { onOpenDocument(LegalDocument.USER_AGREEMENT) }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 撤回授权：删除全部本地数据（含 API Key、协议标记）并退出应用
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                )
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRevoke)
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "撤回授权并清除数据",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "删除全部本地数据并退出，重启需重新同意协议",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 隐私中心页中的单行：图标 + 标题/副标题 + 右侧箭头 */
@Composable
private fun LegalHubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 法律文档全屏查看页：应用内 WebView 加载打包进 assets 的本地 HTML，无需联网、无服务器依赖。
 *
 * 导航规则：
 * - file:///android_asset 链接（两份文档页脚互相跳转）在 WebView 内部加载；
 * - 任意外部 http(s) 链接（如智谱 BigModel 平台政策）转系统浏览器打开；
 * - 返回键优先在 WebView 历史内后退（处理文档间互跳），无历史时退出本页。
 *
 * 页面为纯静态 HTML，显式关闭 JavaScript，规避本地页脚本风险。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentScreen(
    document: LegalDocument,
    onBack: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var topBarTitle by remember(document) { mutableStateOf(document.title) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = topBarTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false          // 纯静态协议页，无需 JS
                    settings.allowFileAccess = true            // 加载 file:///android_asset 本地协议页
                    settings.setSupportZoom(true)             // 允许双指缩放
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val url = request.url
                            // 本地 asset / file 链接（两份文档互跳）交给 WebView 内部加载
                            if (url.scheme == "file" || url.scheme == "about") return false
                            // 外部链接（智谱平台政策等）转系统浏览器，无法打开时也拦截在本页之外
                            return runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, url))
                                true
                            }.getOrDefault(true)
                        }

                        // 文档间互跳时同步 TopAppBar 标题
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            topBarTitle = when {
                                url?.contains(LegalDocument.USER_AGREEMENT.assetName) == true ->
                                    LegalDocument.USER_AGREEMENT.title
                                url?.contains(LegalDocument.PRIVACY_POLICY.assetName) == true ->
                                    LegalDocument.PRIVACY_POLICY.title
                                else -> topBarTitle
                            }
                        }
                    }
                    loadUrl("file:///android_asset/${document.assetName}")
                    webViewRef = this
                }
            },
            // 页面退出组合时释放 WebView，避免内核资源泄露
            onRelease = { it.destroy() }
        )
    }

    // 返回键：WebView 内有历史先在文档间后退，否则退出本页
    BackHandler(enabled = true) {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            onBack()
        }
    }
}
