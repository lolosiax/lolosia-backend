package moe.lolosia.web.service.oss

import moe.lolosia.web.database.repository.OssUserFileRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.dto.OssFile
import moe.lolosia.web.dto.toOssFile
import moe.lolosia.web.util.fs.FileStore
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.router.UploadedFile
import moe.lolosia.web.util.session.Context
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * 用户文件服务（OSS）。文件本体按 `<workDir>/files/<userId>/<md5>/<fileName>` 落盘，元数据进 `oss_user_file`。
 *
 * 与旧 JVM 版的差异：
 * - 落盘走 [FileStore]（native 侧是 `PosixFileStore`），不再直接用 `java.nio.file`；
 * - md5 由内存中的字节直接算（旧版是流式 + `MessageDigest`），因为自研路由的 multipart 解析
 *   已经把整个文件读进内存（[UploadedFile.bytes]）；
 * - 上传去重：同一 `user + md5 + service` 已存在则跳过（旧版会重复插行）。
 *
 * [ctx] 首参透传，本层不持有连接池。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class UserFileService(
    private val files: FileStore,
    private val metas: OssUserFileRepository,
) {

    /** 列出当前用户在某 service 下的全部文件。 */
    suspend fun list(ctx: Context, service: String?): List<OssFile> {
        val user = ctx.requireUserId()
        return metas.findAllByServiceAndCreatedBy(ctx.db, normalized(service), user).getOrThrow().map { it.toOssFile() }
    }

    /** 按 md5 取元数据（`@Query` 不支持 `IN`，逐个查；查不到的略过）。 */
    suspend fun getMetas(ctx: Context, service: String?, md5List: List<String>): List<OssFile> {
        val user = ctx.requireUserId()
        val svc = normalized(service)
        return md5List.mapNotNull { metas.findOneByMd5AndServiceAndCreatedBy(ctx.db, it, svc, user).getOrThrow() }
            .map { it.toOssFile() }
    }

    /** 批量探测：每个 md5 在当前用户名下是否已存在（不区分 service）。 */
    suspend fun check(ctx: Context, md5List: List<String>): List<Boolean> {
        val user = ctx.requireUserId()
        return md5List.map { metas.findOneByMd5AndCreatedBy(ctx.db, it, user).getOrThrow() != null }
    }

    /**
     * 上传：逐个文件算 md5、落盘、记元数据（已存在则跳过）。
     *
     * [references] 是前端「本地已存在、无需重传」的 md5 列表，为它们在当前 service 下补一条引用记录。
     */
    suspend fun upload(
        ctx: Context,
        uploads: List<UploadedFile>,
        references: List<String>,
        service: String?,
        operatorId: String?,
    ): MessageResult {
        val user = ctx.requireUserId()
        val svc = normalized(service)
        val operator = operatorId ?: user
        val now = Clock.System.now()

        uploads.forEach { file ->
            val md5 = md5Hex(file.bytes)
            if (metas.findOneByMd5AndServiceAndCreatedBy(ctx.db, md5, svc, user).getOrThrow() != null) return@forEach
            val name = file.fileName?.takeIf { it.isNotBlank() } ?: md5
            if (!files.write(path(user, md5, name), file.bytes)) throw ApiException(500, "文件写入失败")
            metas.executeInsert(ctx.db, Uuid.random().toString(), md5, name, svc, now, user, now, operator).getOrThrow()
        }

        references.forEach { md5 ->
            val existing = metas.findOneByMd5AndCreatedBy(ctx.db, md5, user).getOrThrow() ?: return@forEach
            if (metas.findOneByMd5AndServiceAndCreatedBy(ctx.db, md5, svc, user).getOrThrow() != null) return@forEach
            metas.executeInsert(ctx.db, Uuid.random().toString(), md5, existing.fileName, svc, now, user, now, operator)
                .getOrThrow()
        }
        return MessageResult("ok")
    }

    /**
     * 读取文件本体。`user` 走路径参数而不是 `ctx.userId` —— `/api/oss/get/` 在鉴权白名单里
     * （`<img src>` 带不了请求头），取件请求是匿名的，归属只能由路径给出。
     */
    suspend fun read(ctx: Context, user: String, md5: String): OssFileContent {
        val row = metas.findOneByMd5AndCreatedBy(ctx.db, md5, user).getOrThrow()
            ?: throw ApiException(404, "文件不存在")
        val bytes = files.read(path(user, md5, row.fileName)) ?: throw ApiException(404, "文件不存在")
        return OssFileContent(row.fileName, bytes)
    }

    /** 文件字节与其文件名（控制器靠文件名判 MIME）。 */
    class OssFileContent(val name: String, val bytes: ByteArray)

    private fun path(user: String, md5: String, name: String): String = "$FILE_DIR/$user/$md5/$name"

    /** 空 / 空白 service 归一到默认值，避免「同一批文件落到 '' 与 'default' 两个分组」。 */
    private fun normalized(service: String?): String = service?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVICE

    private fun md5Hex(bytes: ByteArray): String = bytes.toByteString().md5().hex()

    companion object {
        /** 文件在 [FileStore] 根目录下的相对目录。 */
        const val FILE_DIR: String = "files"

        /** 未指定 service 时的分组名。 */
        const val DEFAULT_SERVICE: String = "default"
    }
}
