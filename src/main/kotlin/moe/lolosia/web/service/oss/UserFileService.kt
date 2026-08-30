package moe.lolosia.web.service.oss

import io.ktor.http.content.PartData
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.lolosia.web.model.oss.UserFileEntity
import moe.lolosia.web.model.oss.query.QUserFileEntity
import moe.lolosia.web.annotation.Service
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.ebean.createModel
import moe.lolosia.web.util.ebean.database
import moe.lolosia.web.util.ebean.query
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.createModel
import moe.lolosia.web.util.session.database
import moe.lolosia.web.util.session.query
import moe.lolosia.web.util.success
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.outputStream

/**
 * 用户文件服务。
 * 文件按 `work/files/<userId>/<md5>/<fileName>` 存储，数据库记录元数据。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
@Service
class UserFileService {

    fun getFileMeta(ctx: Context, md5: String): UserFileEntity? {
        return ctx.query<QUserFileEntity> {
            this.md5.eq(md5)
            this.createdBy.eq(ctx.userIdOrNull)
        }.findOne()
    }

    fun getFilePath(ctx: Context, md5: String): Path? {
        val userId = ctx.userIdOrNull?.toString() ?: "guest"
        val dir = Path.of("work/files", userId, md5)
        if (!dir.exists()) return null
        return dir.toFile().listFiles()?.firstOrNull()?.toPath()
    }

    suspend fun fileCheck(ctx: Context, md5: List<String>): List<Boolean> {
        val userId = ctx.userIdOrNull
        val has = ctx.query<QUserFileEntity> {
            createdBy.eq(userId)
            this.md5.isIn(md5)
        }.findList().map { it.md5 }.toSet()
        return md5.map { it in has }
    }

    /**
     * 接收用户上传的文件
     * @param reference 本次不需要上传、直接引用的已有文件 md5 列表
     */
    suspend fun uploadFromFileItems(
        ctx: Context,
        files: List<PartData.FileItem>,
        reference: List<String>,
        service: String,
    ): Bundle {
        val userId = ctx.userIdOrNull
        val dir = Path.of("work/files/${userId ?: "guest"}")
        withContext(Dispatchers.IO) {
            Files.createDirectories(dir)
        }
        val models = mutableListOf<UserFileEntity>()
        val md5List = files.map { fileItem ->
            val fileName = fileItem.originalFileName ?: UUID.randomUUID().toString()
            val filePath = dir.resolve(fileName)
            val messageDigest = MessageDigest.getInstance("MD5")

            filePath.deleteIfExists()

            withContext(Dispatchers.IO) {
                filePath.outputStream().use { os ->
                    val channel = fileItem.provider()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read == -1) break
                        messageDigest.update(buffer, 0, read)
                        os.write(buffer, 0, read)
                    }
                }
            }

            val md5Hex = messageDigest.digest().joinToString("") { "%02x".format(it) }
            val fileTarget = dir.resolve(md5Hex)
            withContext(Dispatchers.IO) {
                Files.createDirectories(fileTarget)
                fileTarget.toFile().listFiles()?.forEach { it.delete() }
                Files.move(filePath, fileTarget.resolve(fileName))
            }

            models += ctx.createModel<UserFileEntity> {
                this.id = UUID.randomUUID()
                this.fileName = fileName
                this.md5 = md5Hex
                this.createdBy = userId
                this.service = service
            }
            md5Hex
        }

        // 用户上传了已存在的文件，添加一个引用
        if (reference.isNotEmpty()) {
            val refs = ctx.query<QUserFileEntity> {
                this.md5.isIn(reference)
                this.createdBy.eq(userId)
                this.service.ne(service)
            }.findList().map {
                it.id = UUID.randomUUID()
                it.service = service
                it.createdAt = null
                it
            }
            models += refs
        }

        ctx.database.insertAll(models)
        return success("ok")
    }
}
