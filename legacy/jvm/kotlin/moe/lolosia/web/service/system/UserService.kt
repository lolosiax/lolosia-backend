package moe.lolosia.web.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.http.content.PartData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.lolosia.web.manager.SessionManager
import moe.lolosia.web.annotation.Service
import moe.lolosia.web.model.system.SysUserEntity
import moe.lolosia.web.model.system.SysUserRolesEntity
import moe.lolosia.web.model.system.ViewUserRoleEntity
import moe.lolosia.web.model.system.query.QSysRoleEntity
import moe.lolosia.web.model.system.query.QSysUserEntity
import moe.lolosia.web.model.system.query.QSysUserRolesEntity
import moe.lolosia.web.model.system.query.QViewUserRoleEntity
import moe.lolosia.web.util.bundle.*
import moe.lolosia.web.util.ebean.*
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.SessionMap
import moe.lolosia.web.util.session.createModel
import moe.lolosia.web.util.session.query
import moe.lolosia.web.util.session.transaction
import moe.lolosia.web.util.success
import io.ktor.utils.io.readAvailable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.springframework.security.crypto.password.PasswordEncoder
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * 用户服务。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Service
class UserService : KoinComponent {
    private val encoder: PasswordEncoder by inject()
    private val mapper: JsonMapper by inject()
    private val session: SessionManager by inject()
    private val userRoleService: UserRoleService by inject()

    init {
        Path("./work/image/avatar").createDirectories()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun list(ctx: Context, pageNo: Int = 1, pageSize: Int = 10, keys: String? = null): Pair<List<Bundle>, Int> {
        val arrList = bundleOf()
        if (!keys.isNullOrEmpty()) {
            // 查询User匹配信息
            ctx.query<QSysUserEntity> {
                or {
                    userName.contains(keys)
                    realName.contains(keys)
                    phone.contains(keys)
                    email.contains(keys)
                }
                select(id)
                val idList = toAsync().findSingleAttributeSet<UUID>()
                if (idList.isNotEmpty()) arrList["userId"] = idList
            }
            // 查询角色匹配信息
            ctx.query<QSysRoleEntity> {
                roleName.contains(keys)
                select(id)
                val rs = toAsync().findSingleAttributeSet<Int>()
                if (rs.isNotEmpty()) arrList["roleId"] = rs
            }
        }

        val offset = (pageNo - 1) * pageSize

        // 从视图中查询所有用户。
        val (userRoles, count) = ctx.query<QViewUserRoleEntity> {

            if (arrList.isNotEmpty()) {
                if (arrList.size > 1) or()
                arrList.forEach { (k, v) ->
                    when (k) {
                        "userId" -> userId.isIn(v as Set<UUID>)
                        "roleId" -> roleId.isIn(v as Set<Int>)
                    }
                }
                if (arrList.size > 1) endOr()
            }
            orderBy {
                roleId.asc()
                userName.asc()
            }
            setFirstRow(offset)
            setMaxRows(pageSize)

        }.toAsync().findPagedList().run {
            list.map(mapper::toBundle) to totalCount
        }

        if (userRoles.isNotEmpty()) {
            val users = ctx.query<QSysUserEntity> {
                id.isIn(userRoles.map { it.getAs<String>("userId")!!.toUuid() })
                id.asMapKey()
            }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundle(v) }
            userRoles.forEach {
                it.scope {
                    "realName" set ""
                    "roleType" set it.getAs<String>("type")
                    users[it["userId"].toString().toUuid()]?.let { user ->
                        user.remove("password")
                        it.putAll(user)
                    } ?: let {
                        "realName" set "<账户不存在>"
                        "isUse" set false
                    }
                }
            }
        }

        return userRoles to count
    }

    /**
     * 按照文本搜索用户，默认只显示10行
     */
    suspend fun userSearching(ctx: Context, keys: String?): List<SysUserEntity> {
        return ctx.query<QSysUserEntity> {
            if (!keys.isNullOrEmpty()) or {
                userName.contains(keys)
                realName.contains(keys)
                phone.contains(keys)
            }
            setMaxRows(10)
        }.toAsync().findList()
    }

    /**
     * 查找用户
     */
    suspend fun get(ctx: Context, idList: List<String?>): List<Bundle> {
        val list = ctx.query<QSysUserEntity> {
            id.isIn(idList.filterNotNull().map { it.toUuid() })
        }.toAsync().findList()
        return mapper.toBundle(list).onEach {
            it.remove("password")
        }
    }

    suspend fun create(ctx: Context, data: Bundle): ViewUserRoleEntity = ctx {
        val phone: String? = data("phone")
        val email: String? = data("email")

        if ((phone ?: email) == null) throw IllegalArgumentException("请输入手机号或邮箱")

        val roleId: Int = data("roleId")!!
        // 如果没有输入用户名，用户名将被设置为以手机号或邮箱
        if ("userName" !in data) data["userName"] = phone ?: email

        // 如果存在密码，则对密码进行加密
        data["password"]?.let {
            data["password"] = encoder.encode(it.toString())
        }

        val userName: String = data("userName")!!

        // 查询或创建用户
        val exists = query<QSysUserEntity> {
            or {
                phone?.let { this.phone.eq(it) }
                email?.let { this.email.eq(it) }
                this.userName.eq(userName)
            }
        }.toAsync().exists()

        if (exists) {
            throw IllegalStateException("同名用户已存在")
        }
        val userId = UUID.randomUUID()
        transaction { tran ->
            mapper.toModel<SysUserEntity>(data).apply {
                applyDatabase()
                id = userId
                withContext(Dispatchers.IO) {
                    insert(tran)
                }
            }

            val hasRole = query<QSysUserRolesEntity> {
                this.userId.eq(userId)
            }.toAsync().exists()

            if (!hasRole) {
                createModel<SysUserRolesEntity> {
                    this.userId = userId
                    this.roleId = roleId
                    this.createdBy = ctx.userIdOrNull ?: userId
                    withContext(Dispatchers.IO) {
                        insert(tran)
                    }
                }
            }
        }

        return@ctx query<QViewUserRoleEntity>().userId.eq(userId).findOne()!!
    }

    suspend fun getUserInfo(ctx: Context, id: String): Bundle = ctx {
        val id1 = id.toUuid()
        val userInfo = query<QSysUserEntity> {
            this.id.eq(id1)
        }.toAsync().findOne()?.let { mapper.toBundle(it) }

        userInfo ?: throw NoSuchElementException("找不到用户")

        val roleId = query<QSysUserRolesEntity> {
            userId.eq(id1)
        }.toAsync().findOne()
        userInfo["roleId"] = roleId?.roleId ?: 3
        return userInfo
    }

    /** 修改用户 */
    suspend fun update(ctx: Context, data: Bundle): Bundle = ctx {
        // 加密或移除密码
        data["password"]?.let {
            val str = it.toString()
            if (str.isNotEmpty()) {
                data["password"] = encoder.encode(str)
            } else data.remove("password")
        }
        transaction { tran ->
            // 更新用户信息
            val model = mapper.toModel<SysUserEntity>(data, true).apply { applyDatabase() }
            withContext(Dispatchers.IO) {
                model.update(tran)
            }

            // 更新角色信息
            data.getAs<Int>("roleId")?.let { roleId ->
                val id: UUID = data["id"]!!.toString().toUuid()
                val role = query<QSysUserRolesEntity>().userId.eq(id).toAsync().findOne()
                withContext(Dispatchers.IO) {
                    if (role != null && role.roleId != roleId) {
                        role.roleId = roleId
                        role.save(tran)
                    } else if (role == null) {
                        createModel<SysUserRolesEntity> {
                            this.roleId = roleId
                            this.userId = id
                        }.save(tran)
                    }
                }
            }
        }
        return success()
    }

    suspend fun delete(ctx: Context, ids: List<String>): Bundle = ctx {
        transaction { tran ->
            query<QSysUserRolesEntity> {
                this.userId.isIn(ids.map { it.toUuid() })
            }.toAsync().delete(tran)
        }
        return success()
    }

    suspend fun updatePassword(ctx: Context, id: String, origin: String, target: String): Bundle {
        val user = ctx.query<QSysUserEntity>().id.eq(id.toUuid()).toAsync().findOne()
            ?: throw NoSuchElementException("用户不存在")
        val encoded = user.password
        if (!encoded.isNullOrEmpty() && !encoder.matches(origin, encoded)) {
            throw IllegalStateException("密码错误！")
        }
        withContext(Dispatchers.IO) {
            user.password = encoder.encode(target)
            user.update()
        }
        return success()
    }

    suspend fun myUserInfo(ctx: Context): Bundle {
        val obj = ctx.session
        val user = ctx.query<QSysUserEntity> {
            id.eq(ctx.user.id)
            deleted.ne(true)
        }.toAsync().findOne()!!
        return bundleOf().also {
            it.putAll(obj)
            it.putAll(mapper.toBundle(user))
            it.remove("password")
        }
    }

    suspend fun myUserRole(ctx: Context): Bundle {
        return userRoleService.getRoleByUserId(ctx, bundleScope {
            "userId" set ctx.userId
        })
    }

    suspend fun mySession(ctx: Context): SessionMap? {
        return withContext(Dispatchers.IO) {
            session.mySession(ctx)
        }
    }

    /** 获取头像文件路径（下载由路由处理） */
    suspend fun avatarPathOf(ctx: Context, id: String): Path? {
        val avatar = ctx.query<QSysUserEntity> {
            this.id.eq(id.toUuid())
        }.toAsync().findOne()?.avatar ?: return null
        val path = Path("work/image/avatar", avatar)
        return if (path.exists()) path else null
    }

    /** 上传头像 */
    suspend fun saveAvatar(ctx: Context, id: String, file: PartData.FileItem): Bundle {
        val ext = file.originalFileName?.split(".")?.lastOrNull() ?: "png"
        val filename = "$id.$ext"
        val outFile = Path("work/image/avatar", filename).toFile()
        if (outFile.exists()) outFile.delete()
        withContext(Dispatchers.IO) {
            outFile.outputStream().use { os ->
                val channel = file.provider()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    os.write(buffer, 0, read)
                }
            }
        }
        val user = ctx.query<QSysUserEntity> {
            this.id.eq(id.toUuid())
        }.toAsync().findOne()!!
        user.avatar = filename
        user.save()
        return success()
    }
}
