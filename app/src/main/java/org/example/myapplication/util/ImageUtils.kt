package org.example.myapplication.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片处理工具
 *
 * 负责聊天发图的本地预处理链路：
 * 读取原图 -> 按 EXIF 方向矫正 -> 等比缩放（最长边 [MAX_EDGE]）-> JPEG 压缩 -> Base64 字符串
 *
 * 处理后的图片以 data URL 形式放入智谱多模态请求的 image_url 字段，
 * 既符合接口大小限制，也能显著降低视觉模型的 token 费用。
 */
object ImageUtils {

    private const val TAG = "ImageUtils"
    private const val MAX_EDGE = 1024          // 缩放后最长边像素
    private const val JPEG_QUALITY = 85        // JPEG 压缩质量（0-100）

    /**
     * 处理相册 Photo Picker 返回的 content:// Uri
     *
     * @return 压缩后的 JPEG Base64（不含 data: 前缀）；读取或解码失败返回 null
     */
    fun uriToJpegBase64(context: Context, uri: Uri): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            Log.e(TAG, "无法读取相册图片: $uri")
            null
        } else {
            // 框架 ExifInterface 的 InputStream 构造函数仅 API 30+ 可用，低版本相册图不做方向矫正
            val exif: ExifInterface? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            } else {
                null
            }
            encodeBytes(bytes, exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (e: Exception) {
        Log.e(TAG, "相册图片处理失败", e)
        null
    }

    /**
     * 处理拍照生成的临时文件
     * 走文件路径读取 EXIF（框架在 minSdk 24 即支持），可正确矫正相机的旋转方向
     *
     * @return 压缩后的 JPEG Base64；失败返回 null
     */
    fun fileToJpegBase64(context: Context, file: File): String? {
        return try {
            if (!file.exists()) {
                Log.e(TAG, "拍照临时文件不存在: ${file.absolutePath}")
                return null
            }
            val orientation = ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            encodeBytes(file.readBytes(), orientation)
        } catch (e: Exception) {
            Log.e(TAG, "拍照图片处理失败", e)
            null
        }
    }

    /**
     * 创建拍照用的临时文件（位于 cacheDir/images，由 FileProvider 共享给相机）
     */
    fun createCameraImageFile(context: Context): File {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        return File(dir, "capture_${System.currentTimeMillis()}.jpg")
    }

    /**
     * 核心处理：采样解码 -> EXIF 旋转 -> 精确缩放 -> JPEG 压缩 -> Base64
     */
    private fun encodeBytes(bytes: ByteArray, orientation: Int): String? {
        // 第一步：只解析尺寸，不加载像素
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        // 第二步：计算采样率，先粗略缩小，避免解码超大原图导致 OOM
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_EDGE * 2) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: return null

        // 第三步：按 EXIF 方向矫正（部分相机只写方向标记，不旋转像素）
        bitmap = rotateIfNeeded(bitmap, orientation)

        // 第四步：精确等比缩放到最长边 1024
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longestEdge
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        // 第五步：JPEG 压缩并转 Base64（NO_WRAP 保证可直接拼进 JSON）
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        bitmap.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 根据 EXIF orientation 返回旋转后的 Bitmap；无需旋转时原样返回
     */
    private fun rotateIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}
